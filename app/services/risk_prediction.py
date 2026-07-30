from datetime import timedelta
from decimal import Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.dashboard import ScoreSimPoint, ScoreSimulationResponse
from app.dtos.risk_prediction import (
    CareStage,
    RiskComparisonStatus,
    RiskPredictionCreateRequest,
    RiskPredictionCreateResponse,
    RiskPredictionHistoryItem,
    RiskPredictionHistoryResponse,
    RiskPredictionReassessRequest,
    RiskPredictionReassessResponse,
    RiskPredictionResponse,
)
from app.ml.predictor import RiskPredictor, calculate_age, features_from_health_profile
from app.models.enums import ActivityInputSource, InputMethod, OnboardingStatus, RiskLevel
from app.models.health import HealthProfile
from app.models.predictions import RiskPrediction
from app.models.users import User
from app.repositories.dashboard_repository import DashboardRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.risk_prediction_repository import RiskPredictionRepository
from app.services.activity_metrics import derive_activity_day_counts
from app.services.muscle_score import MuscleScore, compute_muscle_score


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
        muscle = self._muscle_score(prediction, profile)
        return RiskPredictionCreateResponse(
            **self._to_response(prediction, muscle).model_dump(),
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
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        return self._to_response(prediction, self._muscle_score(prediction, profile))

    async def get_recent_predictions(self, user: User, limit: int = 7) -> RiskPredictionHistoryResponse:
        if limit <= 0:
            return RiskPredictionHistoryResponse(predictions=[])
        predictions = await self.prediction_repo.get_recent_predictions(user.user_id, limit)
        chronological = list(reversed(predictions))
        # 점수 파생용 성별·나이는 사용자 단위로 안정적이라 최신 프로필 한 번만 조회해 전 이력에 재사용한다.
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        items: list[RiskPredictionHistoryItem] = []
        previous: RiskPrediction | None = None
        cohort_version: str | None = None
        for prediction in chronological:
            muscle = self._muscle_score(prediction, profile)
            if muscle is not None:
                cohort_version = muscle.cohort_version
            items.append(self._to_history_item(prediction, previous, muscle))
            previous = prediction
        return RiskPredictionHistoryResponse(predictions=items, cohort_version=cohort_version)

    async def get_score_simulation(self, user: User) -> ScoreSimulationResponse:
        """what-if 점수 곡선(#기록탭 §4): 걷기 0~7·근력 0~5 각 지점의 예측확률을 점수로 변환.

        나머지 신체값은 최신 프로필로 고정하고 일수만 바꿔 예측한다. 표 미도착/65세 미만이면 각 score=null.
        """
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        sex = profile.sex.value
        age = calculate_age(profile.birth_date)
        base_features = features_from_health_profile(profile)

        cohort_version: str | None = None

        async def _score_at(overrides: dict[str, float]) -> int | None:
            nonlocal cohort_version
            result = await self.predictor.predict({**base_features, **overrides})
            muscle = compute_muscle_score(
                probability=result.risk_score, sex=sex, age=age, model_variant=result.model_variant
            )
            if muscle is not None:
                cohort_version = muscle.cohort_version
            return muscle.score if muscle else None

        walk = [ScoreSimPoint(days=d, score=await _score_at({"walk_days": float(d)})) for d in range(0, 8)]
        musc = [ScoreSimPoint(days=d, score=await _score_at({"musc_days": float(d)})) for d in range(0, 6)]
        return ScoreSimulationResponse(walk=walk, musc=musc, cohort_version=cohort_version)

    def _muscle_score(self, prediction: RiskPrediction, profile: HealthProfile | None) -> MuscleScore | None:
        """예측확률 + 최신 프로필(성별·나이) + 예측 변형으로 근육 건강 점수를 파생. 전제 미충족 시 None."""
        if profile is None:
            return None
        return compute_muscle_score(
            probability=self._public_risk_score(prediction),
            sex=profile.sex.value,
            age=calculate_age(profile.birth_date),
            model_variant=prediction.model_variant,
        )

    async def _predict_and_save(
        self,
        user: User,
        profile: HealthProfile,
        *,
        complete_onboarding: bool = False,
    ) -> RiskPrediction:
        result = await self.predictor.predict(features_from_health_profile(profile))
        prediction = RiskPrediction(
            user_id=user.user_id,
            profile_id=profile.profile_id,
            model_version=result.model_version,
            model_variant=result.model_variant,
            internal_risk_score=Decimal(str(round(result.risk_score, 3))),
            internal_risk_level=result.risk_level,
            input_snapshot=result.input_snapshot,
        )
        await self.prediction_repo.create_risk_prediction(prediction)
        if complete_onboarding:
            user.onboarding_status = OnboardingStatus.COMPLETED
        await self.session.commit()
        await self.session.refresh(prediction)
        return prediction

    def _to_response(
        self, prediction: RiskPrediction, muscle: MuscleScore | None = None
    ) -> RiskPredictionResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionResponse(
            prediction_id=prediction.prediction_id,
            profile_id=prediction.profile_id,
            model_variant=prediction.model_variant.value,
            risk_score=self._public_risk_score(prediction),
            care_stage=care_stage,
            display_message=self._display_message(care_stage),
            score=muscle.score if muscle else None,
            score_band=muscle.band if muscle else None,
            cohort_version=muscle.cohort_version if muscle else None,
        )

    def _to_reassess_response(self, prediction: RiskPrediction) -> RiskPredictionReassessResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionReassessResponse(
            profile_id=prediction.profile_id,
            prediction_id=prediction.prediction_id,
            risk_score=self._public_risk_score(prediction),
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
        muscle: MuscleScore | None = None,
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
            change_percentage_points=change_percentage_points,
            comparison_status=comparison_status,
            care_stage=RiskPredictionService._care_stage_from_risk_level(prediction.internal_risk_level),
            score=muscle.score if muscle else None,
            score_band=muscle.band if muscle else None,
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
