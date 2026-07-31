import asyncio
from datetime import timedelta
from decimal import Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.dashboard import ScoreSimPoint, ScoreSimulationResponse
from app.dtos.risk_prediction import (
    CareStage,
    CohortDistributionResponse,
    RiskComparisonStatus,
    RiskPredictionCreateRequest,
    RiskPredictionCreateResponse,
    RiskPredictionHistoryItem,
    RiskPredictionHistoryResponse,
    RiskPredictionReassessRequest,
    RiskPredictionReassessResponse,
    RiskPredictionResponse,
)
from app.ml.cohort_density import DENSITY_METHOD
from app.ml.predictor import (
    AGE_MIN,
    AgeNotSupportedError,
    RiskPredictor,
    _cohort_age_key,
    features_from_health_profile,
    load_cohort_distribution,
    load_cohort_version,
    percentile_low,
)
from app.models.enums import ActivityInputSource, InputMethod, OnboardingStatus, RiskLevel
from app.models.health import HealthProfile
from app.models.predictions import RiskPrediction
from app.models.users import User
from app.repositories.dashboard_repository import DashboardRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.risk_prediction_repository import RiskPredictionRepository
from app.services.activity_metrics import derive_activity_day_counts


class RiskPredictionService:
    def __init__(self, session: AsyncSession, predictor: RiskPredictor | None = None):
        self.session = session
        self.dashboard_repo = DashboardRepository(session)
        self.profile_repo = HealthProfileRepository(session)
        self.prediction_repo = RiskPredictionRepository(session)
        self.predictor = predictor or RiskPredictor()

    async def create_prediction(
        self,
        user: User,
        data: RiskPredictionCreateRequest,
    ) -> RiskPredictionCreateResponse:
        profile = await self.profile_repo.get_profile(data.profile_id, user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        prediction = await self._predict_and_save(user, profile, complete_onboarding=True)
        return RiskPredictionCreateResponse(
            **self._to_response(prediction).model_dump(),
            onboarding_status=user.onboarding_status.value,
        )

    async def reassess_latest_profile(
        self,
        user: User,
        data: RiskPredictionReassessRequest,
    ) -> RiskPredictionReassessResponse:
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        reassessment_profile = await self._create_reassessment_profile(
            user=user,
            source_profile=profile,
            activity_window_days=data.activity_window_days,
        )
        prediction = await self._predict_and_save(user, reassessment_profile)
        return self._to_reassess_response(prediction)

    async def get_latest_prediction(self, user: User) -> RiskPredictionResponse:
        prediction = await self.prediction_repo.get_latest_prediction(user.user_id)
        if prediction is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Risk prediction not found.")
        return self._to_response(prediction)

    async def get_recent_predictions(self, user: User, limit: int = 7) -> RiskPredictionHistoryResponse:
        if limit <= 0:
            return RiskPredictionHistoryResponse(predictions=[])
        predictions = await self.prediction_repo.get_recent_predictions(user.user_id, limit)
        chronological = list(reversed(predictions))
        items: list[RiskPredictionHistoryItem] = []
        previous: RiskPrediction | None = None
        for prediction in chronological:
            items.append(self._to_history_item(prediction, previous))
            previous = prediction
        return RiskPredictionHistoryResponse(
            predictions=items,
        )

    async def get_score_simulation(self, user: User) -> ScoreSimulationResponse:
        """what-if 점수 곡선(#기록탭 §4): 걷기 0~7·근력 0~5 각 지점의 근육 건강 점수.

        신체값은 최신 프로필로 고정하고 일수만 바꿔 예측한다. 점수는 **예측기(#273)가 산출한 result.muscle_score**
        를 그대로 쓴다(앱·서버 어디서도 재계산하지 않음). 65세 미만(AgeNotSupportedError)·코호트 미조회면 각 지점 null.
        """
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        base_features = features_from_health_profile(profile)

        async def _score_at(overrides: dict[str, float]) -> tuple[int | None, str | None]:
            try:
                result = await self.predictor.predict({**base_features, **overrides})
            except AgeNotSupportedError:
                return None, None
            if result.muscle_score is None:
                return None, None
            return result.muscle_score, result.score_cohort_version

        # 각 지점 예측은 서로 독립이라 병렬 실행한다(리뷰 #272 perf) — 표 점화 후 14회 순차 예측 지연 방지.
        walk_results, musc_results = await asyncio.gather(
            asyncio.gather(*[_score_at({"walk_days": float(d)}) for d in range(0, 8)]),
            asyncio.gather(*[_score_at({"musc_days": float(d)}) for d in range(0, 6)]),
        )
        walk = [ScoreSimPoint(days=d, score=score) for d, (score, _) in enumerate(walk_results)]
        musc = [ScoreSimPoint(days=d, score=score) for d, (score, _) in enumerate(musc_results)]
        cohort_version = next(
            (cv for score, cv in [*walk_results, *musc_results] if cv is not None), None
        )
        return ScoreSimulationResponse(walk=walk, musc=musc, cohort_version=cohort_version)

    async def get_cohort_distribution(self, user: User) -> CohortDistributionResponse:
        """또래 분포 병합 차트(#193): 사용자 코호트의 quantiles·density 와 내 위치(lower_count)를 낸다.

        확률은 최신 예측(internal_risk_score)을 쓰므로 코호트도 **그 예측이 만들어진 시점의 프로필과
        model_variant 로부터 (feature_set × 성별 × 단일나이) 키**를 만들어 선택한다 — 예측 후 프로필이
        수정돼도 확률·분포의 기준 모델과 입력이 항상 일치한다. 65세 미만은 코호트 미지원이라 422.
        """
        prediction = await self.prediction_repo.get_latest_prediction(user.user_id)
        if prediction is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Risk prediction not found.")
        profile = await self.profile_repo.get_profile(prediction.profile_id, user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        features = features_from_health_profile(profile)
        age = features.get("age")
        sex = features.get("sex")
        if age is None or float(age) < AGE_MIN:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"Cohort distribution is provided for age >= {AGE_MIN} only.",
            )
        if sex is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Sex not available.")
        # 확률을 만든 모델과 같은 feature_set 코호트만 사용한다. 다른 feature_set 으로 폴백하면
        #   with_waist 확률을 minimal 분포에 대입하는 식의 잘못된 백분위가 나온다(리뷰 #301).
        feature_set = prediction.model_variant.value
        age_key = _cohort_age_key(float(age))
        table = load_cohort_distribution()
        dist = table.get((feature_set, int(sex), age_key))
        if dist is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Cohort distribution not found.")
        probability = float(prediction.internal_risk_score)
        # 백분위(선형보간) → 100명 환산 '나보다 위험이 낮은 사람 수'. [1,99] 클램프(0번째/101번째 방지, 스펙 §4.1).
        lower_count = min(99, max(1, round(percentile_low(probability, dist.quantiles))))
        return CohortDistributionResponse(
            probability=probability,
            sex=profile.sex.value,
            age_label=_format_cohort_age_label(age_key, dist.window),
            n=dist.n,
            lower_count=lower_count,
            quantiles=list(dist.quantiles),
            density=[list(point) for point in dist.density],
            density_method=DENSITY_METHOD,
            cohort_version=load_cohort_version(),
            # 산출물 meta.model_version 은 minimal 모델 버전이라 with_waist 경로에서 불일치한다(리뷰 #301).
            #   확률을 실제로 만든 예측의 버전을 그대로 노출한다.
            model_version=prediction.model_version,
        )

    async def _predict_and_save(
        self,
        user: User,
        profile: HealthProfile,
        *,
        complete_onboarding: bool = False,
    ) -> RiskPrediction:
        try:
            result = await self.predictor.predict(features_from_health_profile(profile))
        except AgeNotSupportedError as exc:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail={
                    "code": "sarcopenia_prediction_preparing",
                    "message": "근감소증 예측은 만 65세 이상부터 제공됩니다.",
                },
            ) from exc
        score_p_low = getattr(result, "score_p_low", None)
        score_p_high = getattr(result, "score_p_high", None)
        prediction = RiskPrediction(
            user_id=user.user_id,
            profile_id=profile.profile_id,
            model_version=result.model_version,
            model_variant=result.model_variant,
            internal_risk_score=Decimal(str(round(result.risk_score, 3))),
            internal_risk_level=result.risk_level,
            muscle_score=getattr(result, "muscle_score", None),
            score_band=getattr(result, "score_band", None),
            score_p_low=Decimal(str(round(score_p_low, 5))) if score_p_low is not None else None,
            score_p_high=Decimal(str(round(score_p_high, 5))) if score_p_high is not None else None,
            score_cohort_age=getattr(result, "score_cohort_age", None),
            score_cohort_version=getattr(result, "score_cohort_version", None),
            input_snapshot=result.input_snapshot,
        )
        await self.prediction_repo.create_risk_prediction(prediction)
        if complete_onboarding:
            user.onboarding_status = OnboardingStatus.COMPLETED
        await self.session.commit()
        await self.session.refresh(prediction)
        return prediction

    def _to_response(self, prediction: RiskPrediction) -> RiskPredictionResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionResponse(
            prediction_id=prediction.prediction_id,
            profile_id=prediction.profile_id,
            model_variant=prediction.model_variant.value,
            risk_score=self._public_risk_score(prediction),
            muscle_score=getattr(prediction, "muscle_score", None),
            score_band=getattr(prediction, "score_band", None),
            cohort_version=getattr(prediction, "score_cohort_version", None),
            care_stage=care_stage,
            display_message=self._display_message(care_stage),
        )

    def _to_reassess_response(self, prediction: RiskPrediction) -> RiskPredictionReassessResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionReassessResponse(
            profile_id=prediction.profile_id,
            prediction_id=prediction.prediction_id,
            risk_score=self._public_risk_score(prediction),
            muscle_score=getattr(prediction, "muscle_score", None),
            score_band=getattr(prediction, "score_band", None),
            cohort_version=getattr(prediction, "score_cohort_version", None),
            care_stage=care_stage,
            display_message=self._display_message(care_stage),
        )

    async def _create_reassessment_profile(
        self,
        *,
        user: User,
        source_profile: HealthProfile,
        activity_window_days: int,
    ) -> HealthProfile:
        end_date = today_kst()
        start_date = end_date - timedelta(days=activity_window_days - 1)
        activity_logs = await self.dashboard_repo.get_activity_logs_between(
            user.user_id,
            start_date,
            end_date,
        )
        walk_days, musc_days = derive_activity_day_counts(
            activity_logs,
            activity_window_days=activity_window_days,
        )
        profile = HealthProfile(
            user_id=user.user_id,
            session_id=None,
            birth_date=source_profile.birth_date,
            sex=source_profile.sex,
            height_cm=source_profile.height_cm,
            weight_kg=source_profile.weight_kg,
            bmi=source_profile.bmi,
            waist_cm=source_profile.waist_cm,
            walk_days=walk_days,
            musc_days=musc_days,
            activity_input_source=ActivityInputSource.SERVICE_LOG,
            activity_window_days=activity_window_days,
            kidney_status=source_profile.kidney_status,
            protein_restriction_status=source_profile.protein_restriction_status,
            protein_challenge_allowed=source_profile.protein_challenge_allowed,
            input_method=InputMethod.SERVICE_LOG,
            has_estimated_value=True,
        )
        await self.profile_repo.create_profile(profile)
        return profile

    @staticmethod
    def _to_history_item(
        prediction: RiskPrediction,
        previous: RiskPrediction | None = None,
    ) -> RiskPredictionHistoryItem:
        score = RiskPredictionService._public_risk_score(prediction)
        change_percentage_points: float | None = None
        if previous is None:
            comparison_status = RiskComparisonStatus.BASELINE
        elif previous.model_version != prediction.model_version:
            comparison_status = RiskComparisonStatus.MODEL_CHANGED
        else:
            comparison_status = RiskComparisonStatus.COMPARABLE
            previous_score = RiskPredictionService._public_risk_score(previous)
            change_percentage_points = round((score - previous_score) * 100, 1)

        return RiskPredictionHistoryItem(
            prediction_id=prediction.prediction_id,
            created_at=prediction.created_at,
            risk_score=score,
            muscle_score=getattr(prediction, "muscle_score", None),
            score_band=getattr(prediction, "score_band", None),
            cohort_version=getattr(prediction, "score_cohort_version", None),
            change_percentage_points=change_percentage_points,
            comparison_status=comparison_status,
            care_stage=RiskPredictionService._care_stage_from_risk_level(prediction.internal_risk_level),
        )

    @staticmethod
    def _public_risk_score(prediction: RiskPrediction) -> float:
        return float(prediction.internal_risk_score)

    @staticmethod
    def _care_stage_from_risk_level(level: RiskLevel) -> CareStage:
        if level == RiskLevel.HIGH:
            return CareStage.ACTION_NEEDED
        if level == RiskLevel.MEDIUM:
            return CareStage.MAINTAIN
        return CareStage.GOOD

    @staticmethod
    def _display_message(care_stage: CareStage) -> str:
        if care_stage == CareStage.ACTION_NEEDED:
            return "근력과 활동량을 더 챙기면 좋은 시점이에요. 무리하지 않는 범위에서 맞춤 운동을 천천히 시작해 봐요."
        if care_stage == CareStage.MAINTAIN:
            return "조금만 더 챙기면 좋은 단계예요. 걷기와 근력 운동을 꾸준히 이어가 봐요."
        return "지금 컨디션이 좋아요. 지금처럼 생활습관 미션을 이어가면 근력을 잘 지킬 수 있어요."


def _format_cohort_age_label(age_key: str, window: str | None) -> str:
    """헤드라인 연령대 라벨(#193 §5). '80+' -> '80세 이상', window 'lo-hi' -> 'lo–hi세'(en dash)."""
    if age_key.endswith("+"):
        return f"{age_key[:-1]}세 이상"
    if window and "-" in window:
        lo, hi = window.split("-", 1)
        return f"{lo}–{hi}세"
    return f"{age_key}세"
