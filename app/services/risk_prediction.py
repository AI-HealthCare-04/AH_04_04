import asyncio
from collections.abc import Sequence
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
    BaselineChangeReason,
    CareStage,
    CohortDistributionResponse,
    FeatureContributionResponse,
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
    AGE_TOPCODE,
    AgeNotSupportedError,
    FeatureContribution,
    RiskPredictor,
    features_from_health_profile,
    load_cohort_distribution,
    load_cohort_version,
    percentile_low,
)
from app.models.enums import ModelVariant, OnboardingStatus, RiskLevel, TermsType
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

# 활동 일수를 실기록으로 세기 시작하는 날(#422). 온보딩 완료일이 1일차이므로 8일차 = 완료 후 7일.
#   그 전에는 활동 창(7일)을 채울 기록이 없어, 실기록으로 세면 자가응답보다 무조건 낮게 나온다.
ACTIVITY_REFLECTION_START_DAY = 8

# 자동 예측이 쓰는 활동 창. 앱의 재평가 요청 기본값(RecordModels.kt)과 같은 7일로 맞춘다 —
#   두 경로가 다른 창을 쓰면 같은 날 수동/자동 점수가 달라져 사용자가 설명할 수 없는 차이를 본다.
DEFAULT_ACTIVITY_WINDOW_DAYS = 7


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
        prediction, contributions = await self._predict_and_save(user, profile, complete_onboarding=True)
        return RiskPredictionCreateResponse(
            **self._to_response(prediction, contributions).model_dump(),
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
            # recalculated=False: 재계산하지 않으므로 기여도는 빈 목록으로 나간다. 앱은 기존 로컬 캐시를 유지한다.
            return self._to_reassess_response(existing, recalculated=False)
        # 프로필 행을 새로 만들지 않는다(#408 A+3). 재평가에서 실제로 달라지는 값은 활동 일수뿐인데,
        #   그 둘을 담으려고 생년월일·성별·신체계측까지 매번 복제하고 있었다. 활동 일수는 예측 입력으로만
        #   쓰고, 예측 행은 사용자가 직접 입력한 프로필을 그대로 가리킨다.
        activity_override = await self._derive_activity_days(user, data.activity_window_days)
        prediction, contributions = await self._predict_and_save(
            user,
            profile,
            activity_override=activity_override,
            is_reassessment=True,
        )
        return self._to_reassess_response(prediction, contributions=contributions)

    async def create_auto_prediction(self, user: User) -> RiskPrediction | None:
        """자정 배치가 사용자 1명의 오늘 점수를 만든다(#422). 이미 있으면 만들지 않고 None.

        수동 재평가와 **카운터가 분리**돼 있어(`is_auto`) 이 호출이 사용자의 그날 재평가 권리를
        소진하지 않는다. 반대로 사용자가 먼저 재평가했더라도 자동 예측은 따로 남는다 — 추이는
        하루 한 점을 자동으로 보장하고, 수동 재평가는 그날의 최신 상태를 반영한다.

        활동 일수 규칙은 수동 재평가와 같다 — 온보딩 8일차 전이면 자가응답 값을 그대로 쓴다.

        ⚠️ **잠금을 먼저 잡는다**(리뷰 P1). 확인 후 저장은 그 자체로 check-then-insert 경쟁이고
        `(user_id, 날짜, is_auto)` 유일성 제약도 없다. 단일 워커여도 겹친다 — 기동 보정과 00:05
        cron 은 서로 다른 작업이라 `max_instances` 가 막지 못하고, 00:05 직전에 기동하면 둘이
        동시에 돈다. 수동 재평가와 **같은 users 행 잠금**을 써서 자동끼리는 물론 자동과 수동
        사이의 경합까지 한 줄에 세운다(커밋까지 유지된다).
        """
        await self.prediction_repo.lock_user_for_reassess(user.user_id)
        if await self.prediction_repo.get_today_auto_prediction(user.user_id) is not None:
            return None
        profile = await self.profile_repo.get_latest_profile(user.user_id)
        if profile is None:
            return None
        activity_override = await self._derive_activity_days(user, DEFAULT_ACTIVITY_WINDOW_DAYS)
        prediction, _ = await self._predict_and_save(
            user,
            profile,
            activity_override=activity_override,
            is_reassessment=True,
            is_auto=True,
        )
        return prediction

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
        # 신체 정보 변경 판정(#389 C)은 프로필 값 비교라 프로필이 필요하다. 예측마다 읽으면 N+1 이므로
        #   이력에 등장하는 프로필만 한 번에 읽는다.
        profiles = await self.profile_repo.get_profiles_by_ids(
            (p.profile_id for p in chronological),
            user.user_id,
        )
        items: list[RiskPredictionHistoryItem] = []
        previous: RiskPrediction | None = None
        for prediction in chronological:
            items.append(self._to_history_item(prediction, previous, profiles))
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
        if sex not in COHORT_SEX_CODES:
            # 저장값이 계약(male=1, female=2) 밖이면 표에 없는 키라 조회가 성립하지 않는다. None 뿐 아니라
            #   0 같은 잘못된 값도 여기서 명시적으로 걸러, `int(sex)` 나 조회 실패로 흘려보내지 않는다.
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
        activity_override: tuple[int, int] | None = None,
        is_reassessment: bool = False,
        is_auto: bool = False,
    ) -> tuple[RiskPrediction, list[FeatureContributionResponse]]:
        """예측을 계산·저장하고, (예측 행, 이번 계산의 SHAP 기여도)를 함께 돌려준다.

        기여도는 **저장하지 않고** 이 반환값으로만 흘려보낸다(#406 리뷰 P1 — 파생값도 허리둘레 역산이
        가능해 #408 최소화를 무력화하므로 컬럼에 남기지 않는다). 호출부가 create·재계산 응답에만 싣는다.
        """
        features = features_from_health_profile(profile)
        if activity_override is not None:
            # 재평가는 최근 기록에서 센 활동 일수로 예측한다. 프로필에 적힌 값(가입 시 자가응답)이
            #   아니라 이 값을 쓰므로, 프로필 행을 새로 만들지 않아도 재평가의 의미가 유지된다.
            walk_days, musc_days = activity_override
            features = {**features, "walk_days": walk_days, "musc_days": musc_days}
        try:
            result = await self.predictor.predict(features)
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
            is_reassessment=is_reassessment,
            is_auto=is_auto,
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
        # SHAP 기여도(#406)는 저장하지 않고, 예측기가 **점수와 같은 모델 번들로** 계산해 result 에 실어준 값을
        #   그대로 응답 DTO 로 흘려보낸다(리뷰 P1 — 서비스가 전역 아티팩트를 재선택하지 않는다).
        return prediction, self._contributions(getattr(result, "score_contributions", ()))

    @staticmethod
    def _contributions(
        contributions: Sequence[FeatureContribution] | None,
    ) -> list[FeatureContributionResponse]:
        """예측기가 result 에 실어준 SHAP 기여도(#406)를 응답 DTO 로 변환한다.

        서비스는 계산하지 않고 전달만 한다 — 점수를 낸 것과 같은 모델 번들 보장은 예측기(``predict_sync``)가
        진다(리뷰 P1). 저장·재조회 없이 create·재계산 경로에서만 전달되고, 없으면 빈 목록이다(#408).
        """
        return [
            FeatureContributionResponse(
                feature=c.feature,
                effect_on_score_log_odds=c.effect_on_score_log_odds,
            )
            for c in (contributions or [])
        ]

    def _to_response(
        self,
        prediction: RiskPrediction,
        contributions: list[FeatureContributionResponse] | None = None,
    ) -> RiskPredictionResponse:
        # contributions 는 create 시점에만 전달된다. /me/latest 등 조회 경로는 None → 빈 목록(앱 캐시 사용).
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
            contributions=contributions or [],
        )

    def _to_reassess_response(
        self,
        prediction: RiskPrediction,
        *,
        recalculated: bool = True,
        contributions: list[FeatureContributionResponse] | None = None,
    ) -> RiskPredictionReassessResponse:
        # 기여도는 recalculated=True(새로 계산)일 때만 전달된다. recalculated=False 는 None → 빈 목록.
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
            contributions=contributions or [],
        )

    async def _derive_activity_days(self, user: User, activity_window_days: int) -> tuple[int, int] | None:
        """최근 N일 실제 기록에서 걷기·근력 일수를 센다(#408 A+3 — 프로필 행 없이 예측 입력만 만든다).

        **온보딩 완료 8일차부터만 센다**(#422). 그 전에는 None 을 돌려 온보딩 자가응답 값을 그대로
        쓴다 — 창(7일)을 채울 기록이 아직 없는데 실기록으로 세면 "걷기 주 5일"이라 답한 사용자가
        가입 이튿날 재평가를 눌렀을 때 `walk_days=0` 이 되어 점수가 급락한다. 사용자는 아무것도
        하지 않았는데 떨어진 그래프를 보게 되고, 원인도 활동 부족이 아니다.

        8일차 = 완료일 당일을 1일차로 세어 7일이 지난 날. 그날부터는 창이 완료 이후 기록으로
        가득 차므로 실기록만으로 판단할 수 있다.
        """
        if not await self._activity_window_is_ready(user):
            return None
        end_date = today_kst()
        start_date = end_date - timedelta(days=activity_window_days - 1)
        activity_logs = await self.dashboard_repo.get_activity_logs_between(
            user.user_id,
            start_date,
            end_date,
        )
        return derive_activity_day_counts(activity_logs, activity_window_days=activity_window_days)

    async def _activity_window_is_ready(self, user: User) -> bool:
        """실기록 반영을 시작해도 되는 날인가(#422). 온보딩 완료일이 없으면 아직 아니다."""
        completed_on = await self.profile_repo.get_onboarding_completed_on(user.user_id)
        if completed_on is None:
            return False
        return (today_kst() - completed_on).days >= ACTIVITY_REFLECTION_START_DAY - 1

    @staticmethod
    def _to_history_item(
        prediction: RiskPrediction,
        previous: RiskPrediction | None = None,
        profiles: dict[int, HealthProfile] | None = None,
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
            baseline_change_reason=RiskPredictionService._baseline_change_reason(prediction, previous),
            profile_changed=RiskPredictionService._profile_changed(prediction, previous, profiles or {}),
            care_stage=RiskPredictionService._care_stage_from_risk_level(prediction.internal_risk_level),
        )

    @staticmethod
    def _baseline_change_reason(
        prediction: RiskPrediction,
        previous: RiskPrediction | None,
    ) -> BaselineChangeReason | None:
        """비교 기준이 바뀐 이유(#389). 단정할 수 없으면 None — 틀린 설명보다 침묵이 낫다.

        모델 변형 전환이 먼저다. 허리둘레 유무가 번들을 가르므로(app/ml/predictor.py) 그 전환이
        곧 사용자가 체감하는 '허리둘레를 넣었다/뺐다'이고, comparison_status=model_changed 의
        실제 원인이기도 하다. 변형이 같을 때만 코호트표 갱신을 본다.
        """
        if previous is None:
            return None
        if previous.model_variant != prediction.model_variant:
            # 허리 유무로 설명할 수 있는 것은 **MINIMAL <-> WITH_WAIST 쌍뿐**이다. 스캐폴드가 한쪽에
            #   끼면 "허리둘레를 넣었다/뺐다"가 아니라 모델 자체가 교체된 것이라 사유를 단정하면 거짓말이 된다.
            if (previous.model_variant, prediction.model_variant) == (
                ModelVariant.MINIMAL,
                ModelVariant.WITH_WAIST,
            ):
                return BaselineChangeReason.WAIST_ADDED
            if (previous.model_variant, prediction.model_variant) == (
                ModelVariant.WITH_WAIST,
                ModelVariant.MINIMAL,
            ):
                return BaselineChangeReason.WAIST_REMOVED
            # 스캐폴드 등 그 밖의 전환은 사용자에게 설명할 사유가 없다.
            return None
        # 한쪽이라도 버전을 모르면(컬럼 도입 이전 행) 갱신됐다고 말하지 않는다.
        previous_cohort = getattr(previous, "score_cohort_version", None)
        current_cohort = getattr(prediction, "score_cohort_version", None)
        if previous_cohort is not None and current_cohort is not None and previous_cohort != current_cohort:
            return BaselineChangeReason.COHORT_UPDATED
        return None

    @staticmethod
    def _profile_changed(
        prediction: RiskPrediction,
        previous: RiskPrediction | None,
        profiles: dict[int, HealthProfile],
    ) -> bool:
        """직전 예측과 **사용자가 입력한 신체 정보**가 달라졌는가(#389 C).

        ⚠️ `profile_id` 비교로는 안 된다(리뷰). #416 은 앞으로의 복제만 멈췄고 그 이전 재평가가 만든
        프로필 42행은 그대로 남아 있다. 그 행들은 신체값을 **전부 그대로 복사**하고 활동 일수만 바꾼
        사본이라, id 만 보면 사용자가 아무것도 고치지 않은 구간이 전부 '신체 정보 변경'으로 나온다 —
        이 필드가 막으려던 바로 그 오인이다. 그래서 값 자체를 비교한다.

        활동 일수(walk_days·musc_days)는 제외한다. 사용자가 '내 정보'에서 고치는 항목이 아니고,
        재평가가 덮어쓰는 값이라 넣으면 레거시 행에서 다시 오탐한다.

        모를 때는 False 다 — '신체 정보가 바뀌어서'라고 잘못 말하느니 기존 중립 문구를 쓰는 편이 낫다.
        """
        if previous is None:
            return False
        previous_profile = profiles.get(getattr(previous, "profile_id", -1))
        current_profile = profiles.get(getattr(prediction, "profile_id", -1))
        if previous_profile is None or current_profile is None:
            return False
        return RiskPredictionService._body_info_key(previous_profile) != RiskPredictionService._body_info_key(
            current_profile
        )

    @staticmethod
    def _body_info_key(profile: HealthProfile) -> tuple[object, ...]:
        """사용자가 입력하는 신체 정보만 모은 비교 키(#389 C)."""
        return (
            profile.birth_date,
            profile.sex,
            profile.height_cm,
            profile.weight_kg,
            profile.waist_cm,
            profile.kidney_status,
            profile.protein_restriction_status,
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
    """코호트 조회 키가 **실제로 존재하는 키**인지: 단일 나이 65..79 또는 상단 top-code `'80+'`.

    입력 원본을 보관하지 않게 되면서(#408) 나이 숫자 대신 키로 판정한다 — 예측 시점에 이미 걸러지지만,
    저장된 값이 어긋난 경우 표 조회 실패(404)로 뭉뚱그리지 않고 '대상 아님'을 그대로 알리기 위한 방어다.

    상한을 두는 이유(#410 리뷰): `>= AGE_MIN` 만 보면 `'80'`·`'999'` 처럼 `_cohort_age_key` 가 절대
    만들지 않는 키까지 통과한다. 80 이상은 항상 `'80+'` 로 접히므로 단일 나이는 79 가 최대다.
    """
    if age_key == f"{AGE_TOPCODE}+":
        return True
    try:
        return AGE_MIN <= int(age_key) < AGE_TOPCODE
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
