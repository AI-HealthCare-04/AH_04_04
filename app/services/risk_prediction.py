from decimal import Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.risk_prediction import (
    CareStage,
    RiskPredictionCreateRequest,
    RiskPredictionCreateResponse,
    RiskPredictionHistoryItem,
    RiskPredictionHistoryResponse,
    RiskPredictionReassessRequest,
    RiskPredictionReassessResponse,
    RiskPredictionResponse,
)
from app.ml.predictor import RiskPredictor, features_from_health_profile
from app.models.enums import ActivityInputSource, InputMethod, OnboardingStatus, RiskLevel
from app.models.health import HealthProfile
from app.models.predictions import RiskPrediction
from app.models.users import User
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.risk_prediction_repository import RiskPredictionRepository


class RiskPredictionService:
    def __init__(self, session: AsyncSession, predictor: RiskPredictor | None = None):
        self.session = session
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
        return RiskPredictionHistoryResponse(
            predictions=[self._to_history_item(prediction) for prediction in predictions],
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

    def _to_response(self, prediction: RiskPrediction) -> RiskPredictionResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionResponse(
            prediction_id=prediction.prediction_id,
            profile_id=prediction.profile_id,
            model_variant=prediction.model_variant.value,
            care_stage=care_stage,
            display_message=self._display_message(care_stage),
        )

    def _to_reassess_response(self, prediction: RiskPrediction) -> RiskPredictionReassessResponse:
        care_stage = self._care_stage_from_risk_level(prediction.internal_risk_level)
        return RiskPredictionReassessResponse(
            profile_id=prediction.profile_id,
            prediction_id=prediction.prediction_id,
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
        profile = HealthProfile(
            user_id=user.user_id,
            session_id=None,
            birth_date=source_profile.birth_date,
            sex=source_profile.sex,
            height_cm=source_profile.height_cm,
            weight_kg=source_profile.weight_kg,
            bmi=source_profile.bmi,
            waist_cm=source_profile.waist_cm,
            walking_practice=source_profile.walking_practice,
            strength_exercise=source_profile.strength_exercise,
            activity_input_source=ActivityInputSource.SERVICE_LOG,
            activity_window_days=activity_window_days,
            kidney_status=source_profile.kidney_status,
            protein_restriction_status=source_profile.protein_restriction_status,
            protein_challenge_allowed=source_profile.protein_challenge_allowed,
            input_method=InputMethod.SERVICE_LOG,
            has_estimated_value=False,
        )
        await self.profile_repo.create_profile(profile)
        return profile

    @staticmethod
    def _to_history_item(prediction: RiskPrediction) -> RiskPredictionHistoryItem:
        return RiskPredictionHistoryItem(
            prediction_id=prediction.prediction_id,
            created_at=prediction.created_at,
            care_stage=RiskPredictionService._care_stage_from_risk_level(prediction.internal_risk_level),
            risk_level=prediction.internal_risk_level,
            risk_score=prediction.internal_risk_score,
            model_variant=prediction.model_variant,
        )

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
            return "근감소 위험 신호가 있어요. 무리하지 않는 범위에서 맞춤 운동 미션을 시작해 보세요."
        if care_stage == CareStage.MAINTAIN:
            return "생활습관 관리가 필요한 상태예요. 걷기와 근력 운동을 꾸준히 이어가 보세요."
        return "현재 입력값 기준으로 위험도는 낮은 편이에요. 지금처럼 생활습관 미션을 이어가 보세요."
