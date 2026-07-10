from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils.clock import now_kst
from app.dtos.physical_assessment import (
    PhysicalAssessmentActivityProfile,
    PhysicalAssessmentCreateRequest,
    PhysicalAssessmentResponse,
)
from app.models.activity import UserActivityProfile
from app.models.enums import ActivityLevel, LevelReason
from app.models.health import PhysicalAssessment
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.physical_assessment_repository import PhysicalAssessmentRepository

DEFAULT_WALK_DISTANCE_M = Decimal("6.00")
EASY_WALK_SPEED_THRESHOLD_MPS = Decimal("0.80")
HARD_WALK_SPEED_THRESHOLD_MPS = Decimal("1.00")


class PhysicalAssessmentService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = PhysicalAssessmentRepository(session)
        self.activity_repo = ActivityProfileRepository(session)

    async def create_assessment(
        self,
        user: User,
        data: PhysicalAssessmentCreateRequest,
    ) -> PhysicalAssessmentResponse:
        walk_distance = data.walk_6m_distance_m
        if data.walk_6m_time_sec is not None and walk_distance is None:
            walk_distance = DEFAULT_WALK_DISTANCE_M

        walk_speed = self._calculate_walk_speed(walk_distance, data.walk_6m_time_sec)
        used_for_level_setting = walk_speed is not None
        activity_level = self._determine_activity_level(
            walk_speed=walk_speed,
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
        )

        assessment = PhysicalAssessment(
            user_id=user.user_id,
            session_id=data.session_id,
            assessment_type=data.assessment_type,
            chair_stand_5_time_sec=data.chair_stand_5_time_sec,
            chair_stand_skipped=data.chair_stand_skipped,
            walk_6m_time_sec=data.walk_6m_time_sec,
            walk_6m_distance_m=walk_distance,
            walk_6m_speed_mps=walk_speed,
            walk_6m_skipped=data.walk_6m_skipped,
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
            used_for_level_setting=used_for_level_setting,
        )
        await self.repo.create_physical_assessment(assessment)
        activity_profile = await self._upsert_activity_profile(
            user_id=user.user_id,
            current_level=activity_level,
            physical_assessment_id=assessment.physical_assessment_id,
        )
        await self.session.commit()
        await self.session.refresh(assessment)
        await self.session.refresh(activity_profile)
        return PhysicalAssessmentResponse(
            physical_assessment_id=assessment.physical_assessment_id,
            walk_6m_speed_mps=assessment.walk_6m_speed_mps,
            used_for_level_setting=assessment.used_for_level_setting,
            activity_profile=PhysicalAssessmentActivityProfile(
                current_level=activity_profile.current_level,
                level_reason=activity_profile.level_reason,
            ),
        )

    @staticmethod
    def _calculate_walk_speed(distance_m: Decimal | None, time_sec: Decimal | None) -> Decimal | None:
        if distance_m is None or time_sec is None:
            return None
        return (distance_m / time_sec).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    @staticmethod
    def _determine_activity_level(
        *,
        walk_speed: Decimal | None,
        pain_reported: bool,
        dizziness_reported: bool,
    ) -> ActivityLevel:
        if pain_reported or dizziness_reported or walk_speed is None:
            return ActivityLevel.EASY
        if walk_speed < EASY_WALK_SPEED_THRESHOLD_MPS:
            return ActivityLevel.EASY
        if walk_speed >= HARD_WALK_SPEED_THRESHOLD_MPS:
            return ActivityLevel.HARD
        return ActivityLevel.NORMAL

    async def _upsert_activity_profile(
        self,
        *,
        user_id: int,
        current_level: ActivityLevel,
        physical_assessment_id: int,
    ) -> UserActivityProfile:
        profile = await self.activity_repo.get_by_user_id(user_id)
        if profile is None:
            profile = UserActivityProfile(
                user_id=user_id,
                current_level=current_level,
                level_reason=LevelReason.INITIAL_TEST,
                physical_assessment_id=physical_assessment_id,
                started_at=now_kst(),
            )
            await self.activity_repo.create_profile(profile)
            return profile

        profile.current_level = current_level
        profile.level_reason = LevelReason.INITIAL_TEST
        profile.physical_assessment_id = physical_assessment_id
        profile.started_at = now_kst()
        await self.activity_repo.update_profile(profile)
        return profile
