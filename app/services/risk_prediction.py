import asyncio
from datetime import datetime, time, timedelta
from decimal import Decimal

from fastapi import HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.core.terms_catalog import CATALOG_BY_TYPE
from app.core.utils.clock import today_kst
from app.dtos.dashboard import ScoreSimPoint, ScoreSimulationResponse
from app.dtos.risk_prediction import (
    CareStage,
    CohortDistributionResponse,
    PredictionFeedbackRequest,
    PredictionFeedbackResponse,
    RiskComparisonStatus,
    RiskPredictionCreateRequest,
    RiskPredictionCreateResponse,
    RiskPredictionHistoryItem,
    RiskPredictionHistoryResponse,
    RiskPredictionReassessRequest,
    RiskPredictionReassessResponse,
    RiskPredictionResponse,
)
from app.ml.predictor import (
    AGE_MIN,
    AgeNotSupportedError,
    RiskPredictor,
    features_from_health_profile,
    load_cohort_distribution,
    load_cohort_version,
    percentile_low,
)
from app.models.enums import ActivityInputSource, InputMethod, OnboardingStatus, RiskLevel, TermsType
from app.models.health import HealthProfile
from app.models.predictions import PredictionFeedback, RiskPrediction
from app.models.users import User
from app.repositories.dashboard_repository import DashboardRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.risk_prediction_repository import RiskPredictionRepository
from app.repositories.terms_repository import TermsRepository
from app.services.activity_metrics import derive_activity_day_counts

DENSITY_METHOD = "boundary_reflected_gaussian_kde_v1"
DENSITY_PLOT_MAX_PROBABILITY = 0.5
# 코호트표 조회 키의 성별 인코딩 — 모델(`_normalize_sex`)과 같아야 한다: male=1, female=2.
#   0 같은 다른 값이 저장되면 표에 없는 키라 또래 분포가 실패한다(#408 리뷰 P1).
COHORT_SEX_CODES = frozenset({1, 2})


class RiskPredictionService:
    def __init__(self, session: AsyncSession, predictor: RiskPredictor | None = None):
        self.session = session
        self.dashboard_repo = DashboardRepository(session)
        self.profile_repo = HealthProfileRepository(session)
        self.prediction_repo = RiskPredictionRepository(session)
        self.terms_repo = TermsRepository(session)
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
        # 사용자 단위 직렬화(리뷰 P1) — **이 트랜잭션의 첫 읽기여야 한다.** 아래 '오늘 재평가 있나' 확인은
        #   check-then-insert 라, 잠금 없이는 동시 요청 둘이 모두 '없음'을 읽고 각각 저장해 하루 1회
        #   정책이 깨진다. 잠금을 먼저 잡으면 뒤 요청은 앞 요청 커밋 후에야 읽어 기존 예측을 보게 된다.
        await self.prediction_repo.lock_user_for_reassess(user.user_id)
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        # 하루 1회 정책(#388): 오늘 이미 재평가했다면 새로 계산·저장하지 않고 그 예측을 그대로 돌려준다.
        #   429 가 아니라 200 + 멱등인 이유 — 앱이 오류 분기 없이 결과를 보여주면 되고, 연타·재설치·API
        #   직접 호출 어느 경로로도 같은 날 예측 행이 늘지 않는다(추이 그래프가 오늘 점으로 덮이는 문제 차단).
        #   프로필 행도 함께 안 늘어난다 — 재평가 프로필 생성이 이 분기 뒤에 있기 때문(#388 결정 4).
        existing = await self.prediction_repo.get_today_reassessment(user.user_id)
        if existing is not None:
            return self._to_reassess_response(existing, recalculated=False)
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

    async def submit_feedback(
        self,
        user: User,
        prediction_id: int,
        data: PredictionFeedbackRequest,
    ) -> PredictionFeedbackResponse:
        """예측 체감 피드백 저장(#357 옵션 B) — 멱등 PUT 계약.

        같은 요청을 반복해도 결과가 같고, 응답을 바꾸면 마지막 값으로 덮어써 예측당 1행을
        유지한다(UNIQUE 보장). 응답은 주관적 체감 신호라 정답 라벨로 쓰지 않는다(이슈 #357).
        """
        # 동의 버전 게이트(#362 리뷰 P1): 체감 응답은 민감정보 항목이라, 이 수집·활용 목적이 명시된
        #   **현행** sensitive_health 문안(1.1+)에 동의한 사용자만 저장한다. 문서·카탈로그 버전 상향만으로는
        #   이미 온보딩을 마친 1.0 동의자가 재동의 없이 제출하는 경로가 닫히지 않는다 — 서버가 직접 막고
        #   403 으로 재동의를 유도한다(앱 재동의 게이트가 생겨도 우회 제출 방어로 유지).
        current_terms = CATALOG_BY_TYPE[str(TermsType.SENSITIVE_HEALTH)]
        agreement = await self.terms_repo.get_agreement(user.user_id, TermsType.SENSITIVE_HEALTH)
        if agreement is None or not agreement.agreed or agreement.version != current_terms.version:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    f"체감 피드백 저장에는 민감정보 동의 최신 버전({current_terms.version}) 동의가 필요합니다. "
                    "약관 재동의 후 다시 시도해 주세요."
                ),
            )
        prediction = await self.prediction_repo.get_prediction_for_user(prediction_id, user.user_id)
        if prediction is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Risk prediction not found.")
        feedback = await self.prediction_repo.get_feedback(prediction_id)
        if feedback is None:
            try:
                # 모바일 이중 요청이 동시에 들어오면 둘 다 '없음'을 보고 INSERT 를 경쟁할 수 있다.
                # SAVEPOINT 로 감싸 UNIQUE 충돌 시 기존 행을 갱신 경로로 복구한다(auth 선례).
                async with self.session.begin_nested():
                    feedback = await self.prediction_repo.add_feedback(
                        PredictionFeedback(prediction_id=prediction_id, response=data.response, reason=data.reason)
                    )
            except IntegrityError:
                feedback = await self.prediction_repo.get_feedback(prediction_id)
                if feedback is None:
                    raise
        feedback.response = data.response
        feedback.reason = data.reason
        await self.session.commit()
        await self.session.refresh(feedback)
        return PredictionFeedbackResponse(
            prediction_id=prediction_id,
            response=feedback.response,
            reason=feedback.reason,
            created_at=feedback.created_at,
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
        cohort_version = next((cv for score, cv in [*walk_results, *musc_results] if cv is not None), None)
        return ScoreSimulationResponse(walk=walk, musc=musc, cohort_version=cohort_version)

    async def get_cohort_distribution(self, user: User) -> CohortDistributionResponse:
        """또래 분포 병합 차트(#193): 사용자 코호트의 quantiles·density 와 내 위치(lower_count)를 낸다.

        확률은 최신 예측(internal_risk_score)을 쓰므로 코호트도 **그 예측이 저장한 조회 키**
        (score_cohort_age × score_cohort_sex × model_variant)로 선택한다 — 예측 후 프로필이
        수정되거나 생일이 지나도 확률·분포의 기준 모델과 입력이 항상 일치한다(리뷰 #301).
        65세 미만은 코호트 미지원이라 422.

        ⚠️ 이전에는 이 값들을 `input_snapshot`(예측 입력 원본 JSON)에서 읽었다. 서버 보관 최소화(#408)로
        원본 저장을 없애면서, 조회에 실제로 필요한 두 값만 컬럼으로 승격해 같은 고정 효과를 유지한다.
        """
        prediction = await self.prediction_repo.get_latest_prediction(user.user_id)
        if prediction is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Risk prediction not found.")
        profile = await self.profile_repo.get_profile(prediction.profile_id, user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        # 조회 키는 프로필에서 재계산하지 않고 예측이 저장한 값을 그대로 쓴다 — 같은 프로필 행이라도
        #   오늘 기준 나이 재계산은 생일 경계에서 예측과 다른 코호트를 고른다(리뷰 #301).
        age_key = prediction.score_cohort_age
        sex = prediction.score_cohort_sex
        if age_key is None or not _is_cohort_supported_age(age_key):
            # 저장된 예측은 만 65세 이상만 있어야 하지만(예측 시점에 422), 키가 없거나 지원 연령이
            #   아니면 코호트 대상이 아니라고 그대로 알린다 — 표 조회 실패(404)로 뭉뚱그리지 않는다.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"Cohort distribution is provided for age >= {AGE_MIN} only.",
            )
        if sex is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Sex not available.")
        # 확률을 만든 모델과 같은 feature_set 코호트만 사용한다. 다른 feature_set 으로 폴백하면
        #   with_waist 확률을 minimal 분포에 대입하는 식의 잘못된 백분위가 나온다(리뷰 #301).
        feature_set = prediction.model_variant.value
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
            # 산출물은 0~1 전체 도메인을 보존하고, 현재 차트가 표시하는 0~0.5 구간만 응답한다.
            # 0.5 초과 점을 보내면 앱의 x 클램프로 오른쪽 끝에 겹쳐 세로선이 그려진다.
            density=[list(point) for point in dist.density if point[0] <= DENSITY_PLOT_MAX_PROBABILITY],
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
            # 또래 분포 조회에 필요한 성별만 예측 시점 값으로 고정 저장한다(#408).
            score_cohort_sex=_snapshot_sex(result.input_snapshot),
            score_cohort_version=getattr(result, "score_cohort_version", None),
            # 입력 원본(input_snapshot)은 저장하지 않는다(#408 — 서버 보관 최소화). 화면·추이가 쓰는 값은
            #   위 파생 컬럼들로 충분하고, 원본을 예측마다 복제하면 프로필을 아무리 줄여도 의미가 없다.
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

    def _to_reassess_response(
        self,
        prediction: RiskPrediction,
        *,
        recalculated: bool = True,
    ) -> RiskPredictionReassessResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionReassessResponse(
            recalculated=recalculated,
            next_available_at=next_reassess_available_at(),
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


def _is_cohort_supported_age(age_key: str) -> bool:
    """코호트 조회 키가 지원 연령(만 [AGE_MIN] 이상)인지. `'80+'` 는 상단 top-code 라 항상 지원.

    입력 원본을 보관하지 않게 되면서(#408) 나이 숫자 대신 키로 판정한다 — 예측 시점에 이미 걸러지지만,
    저장된 값이 어긋난 경우 표 조회 실패(404)로 뭉뚱그리지 않고 '대상 아님'을 그대로 알리기 위한 방어다.
    """
    if age_key.endswith("+"):
        return True
    try:
        return int(age_key) >= AGE_MIN
    except ValueError:
        return False


def _snapshot_sex(snapshot: dict[str, object] | None) -> int | None:
    """예측 입력에서 코호트 조회용 성별만 뽑는다(#408).

    스냅샷 자체는 저장하지 않고 이 파생값만 컬럼에 남긴다 — 또래 분포가 예측 시점 성별로 고정된
    코호트를 골라야 하기 때문이다(리뷰 #301).

    **모델 인코딩(male=1, female=2)만 통과시킨다**(리뷰 P1). 임의의 숫자를 받아들이거나 `1.5 → 1`
    처럼 절삭하면 코호트표에 없는 키가 저장돼, 원본을 지운 뒤에는 또래 분포가 영구히 실패한다.
    계약에 맞지 않으면 None 을 남겨 조회 시 404 로 드러나게 한다(잘못된 값으로 조용히 채우지 않는다).
    """
    if not snapshot:
        return None
    value = snapshot.get("sex")
    # bool 은 int 의 하위형이라 먼저 배제한다(True → 1 로 새는 것을 막는다).
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value if value in COHORT_SEX_CODES else None


def _format_cohort_age_label(age_key: str, window: str | None) -> str:
    """헤드라인 연령대 라벨(#193 §5). '80+' -> '80세 이상', window 'lo-hi' -> 'lo–hi세'(en dash)."""
    if age_key.endswith("+"):
        return f"{age_key[:-1]}세 이상"
    if window and "-" in window:
        lo, hi = window.split("-", 1)
        return f"{lo}–{hi}세"
    return f"{age_key}세"


def next_reassess_available_at() -> datetime:
    """다음 재평가 가능 시각 = 다음 KST 자정(#388 하루 1회 정책).

    앱이 "내일 다시 계산할 수 있어요" 안내에 쓴다. 순수 계산이라 단위 테스트로 고정한다.
    """
    return datetime.combine(today_kst() + timedelta(days=1), time.min, tzinfo=config.TIMEZONE)
