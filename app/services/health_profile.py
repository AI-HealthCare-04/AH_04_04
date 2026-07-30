from datetime import date
from decimal import ROUND_HALF_UP, Decimal

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import today_kst
from app.dtos.health_profile import (
    HealthProfileCreateRequest,
    HealthProfileCreateResponse,
    HealthProfilePatchRequest,
    HealthProfileResponse,
)
from app.models.enums import KidneyStatus, ProteinRestrictionStatus
from app.models.health import HealthProfile
from app.models.users import User
from app.repositories.health_profile_repository import HealthProfileRepository


class HealthProfileService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = HealthProfileRepository(session)

    async def create_profile(self, user: User, data: HealthProfileCreateRequest) -> HealthProfileCreateResponse:
        health_check_session = None
        if data.session_id is not None:
            health_check_session = await self.repo.get_session(data.session_id, user.user_id)
            if health_check_session is None:
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
            walk_days=data.walk_days,
            musc_days=data.musc_days,
            activity_input_source=data.activity_input_source,
            activity_window_days=None,
            kidney_status=data.kidney_status,
            protein_restriction_status=data.protein_restriction_status,
            protein_challenge_allowed=self.is_protein_challenge_allowed(
                data.kidney_status,
                data.protein_restriction_status,
            ),
            input_method=data.input_method,
            has_estimated_value=data.has_estimated_value,
        )
        await self.repo.create_profile(profile)
        # 프로필 저장은 체력검사 전 단계다. 세션 종료는 명시적인 검사 완료
        # (PhysicalAssessmentService) 또는 건너뛰기(HealthCheckService)가 담당한다.
        await self.session.commit()
        await self.session.refresh(profile)
        return HealthProfileCreateResponse(
            profile_id=profile.profile_id,
            bmi=profile.bmi,
            protein_challenge_allowed=profile.protein_challenge_allowed,
        )

    async def update_profile(self, user: User, data: HealthProfilePatchRequest) -> HealthProfileResponse:
        """설정 '내 정보' 편집(#기록탭 §2) — 최신 스냅샷에 편집분을 덮어 **새 health_profile 행을 생성**한다.

        health_profiles 는 append-only(updated_at 없음)이고 risk_predictions 가 특정 스냅샷을 참조하므로,
        in-place 수정 대신 새 행을 쌓는다. 새 컬럼은 만들지 않는다(기존 컬럼 재사용).
        다음 추론이 get_latest_profile 로 이 행을 쓰므로 "다음 근육 건강 정보부터 반영"(스펙 §2)과 일치.
        """
        latest = await self.repo.get_latest_profile(user.user_id)
        if latest is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Health profile not found.")

        # 키·몸무게·신장상태는 필수 값이라 null/미전송이면 최신값을 유지한다(지울 수 없음).
        #   허리둘레만 명시적 null 로 '측정 안 함'을 지울 수 있어 model_fields_set 로 미전송과 구분한다.
        height = data.height_cm if data.height_cm is not None else latest.height_cm
        weight = data.weight_kg if data.weight_kg is not None else latest.weight_kg
        waist = data.waist_cm if "waist_cm" in data.model_fields_set else latest.waist_cm
        kidney = data.kidney_status if data.kidney_status is not None else latest.kidney_status
        # 키·몸무게를 직접 입력했으면 추정치가 아니다. 둘 다 미편집이면 이전 추정 플래그를 유지한다.
        estimated = latest.has_estimated_value and data.height_cm is None and data.weight_kg is None

        new_profile = HealthProfile(
            user_id=user.user_id,
            session_id=None,  # 편집은 건강검진 세션과 무관
            birth_date=latest.birth_date,
            sex=latest.sex,
            height_cm=height,
            weight_kg=weight,
            bmi=self.calculate_bmi(height, weight),
            waist_cm=waist,
            walk_days=latest.walk_days,
            musc_days=latest.musc_days,
            activity_input_source=latest.activity_input_source,
            activity_window_days=latest.activity_window_days,
            kidney_status=kidney,
            protein_restriction_status=latest.protein_restriction_status,
            protein_challenge_allowed=self.is_protein_challenge_allowed(
                kidney, latest.protein_restriction_status
            ),
            input_method=latest.input_method,
            has_estimated_value=estimated,
        )
        await self.repo.create_profile(new_profile)
        await self.session.commit()
        await self.session.refresh(new_profile)
        return self.to_response(new_profile)

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
            walk_days=profile.walk_days,
            musc_days=profile.musc_days,
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
