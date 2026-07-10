from datetime import date
from decimal import ROUND_HALF_UP, Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.health_profile import HealthProfileCreateRequest, HealthProfileResponse
from app.models.enums import ActivityInputSource, InputMethod, KidneyStatus, ProteinRestrictionStatus
from app.models.health import HealthProfile
from app.models.users import User
from app.repositories.health_profile_repository import HealthProfileRepository


class HealthProfileService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = HealthProfileRepository(session)

    async def create_profile(self, user: User, data: HealthProfileCreateRequest) -> HealthProfileResponse:
        if data.session_id is not None:
            session = await self.repo.get_session(data.session_id, user.user_id)
            if session is None:
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health check session not found.")

        profile = HealthProfile(
            user_id=user.user_id,
            session_id=data.session_id,
            birth_date=data.birth_date,
            sex=data.sex,
            height_cm=data.height_cm,
            weight_kg=data.weight_kg,
            bmi=self.calculate_bmi(data.height_cm, data.weight_kg),
            waist_cm=data.waist_cm,
            walking_practice=data.walking_practice,
            strength_exercise=data.strength_exercise,
            activity_input_source=ActivityInputSource.SELF_REPORT,
            activity_window_days=None,
            kidney_status=data.kidney_status,
            protein_restriction_status=data.protein_restriction_status,
            protein_challenge_allowed=self.is_protein_challenge_allowed(
                data.kidney_status,
                data.protein_restriction_status,
            ),
            input_method=InputMethod.FORM,
            has_estimated_value=False,
        )
        await self.repo.create_profile(profile)
        await self.session.commit()
        await self.session.refresh(profile)
        return self.to_response(profile)

    async def get_latest_profile(self, user: User) -> HealthProfileResponse:
        profile = await self.repo.get_latest_profile(user.user_id)
        if profile is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")
        return self.to_response(profile)

    @classmethod
    def to_response(cls, profile: HealthProfile) -> HealthProfileResponse:
        return HealthProfileResponse(
            profile_id=profile.profile_id,
            birth_date=profile.birth_date,
            age=cls.calculate_age(profile.birth_date),
            sex=profile.sex,
            height_cm=profile.height_cm,
            weight_kg=profile.weight_kg,
            bmi=profile.bmi,
            waist_cm=profile.waist_cm,
            walking_practice=profile.walking_practice,
            strength_exercise=profile.strength_exercise,
            activity_input_source=profile.activity_input_source,
            activity_window_days=profile.activity_window_days,
            kidney_status=profile.kidney_status,
            protein_restriction_status=profile.protein_restriction_status,
            protein_challenge_allowed=profile.protein_challenge_allowed,
            input_method=profile.input_method,
            has_estimated_value=profile.has_estimated_value,
            created_at=profile.created_at,
        )

    @staticmethod
    def calculate_bmi(height_cm: Decimal, weight_kg: Decimal) -> Decimal:
        height_m = height_cm / Decimal("100")
        return (weight_kg / (height_m * height_m)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)

    @staticmethod
    def calculate_age(birth_date: date, today: date | None = None) -> int:
        today = today or today_kst()
        age = today.year - birth_date.year
        if (today.month, today.day) < (birth_date.month, birth_date.day):
            age -= 1
        return age

    @staticmethod
    def is_protein_challenge_allowed(
        kidney_status: KidneyStatus,
        protein_restriction_status: ProteinRestrictionStatus,
    ) -> bool:
        return kidney_status == KidneyStatus.NONE and protein_restriction_status == ProteinRestrictionStatus.NONE
