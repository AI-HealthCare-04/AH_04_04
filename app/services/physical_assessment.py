from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.physical_assessment import PhysicalAssessmentCreateRequest, PhysicalAssessmentResponse
from app.models.health import PhysicalAssessment
from app.models.users import User
from app.repositories.physical_assessment_repository import PhysicalAssessmentRepository

DEFAULT_WALK_DISTANCE_M = Decimal("6.00")


class PhysicalAssessmentService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = PhysicalAssessmentRepository(session)

    async def create_assessment(
        self,
        user: User,
        data: PhysicalAssessmentCreateRequest,
    ) -> PhysicalAssessmentResponse:
        walk_distance = data.walk_6m_distance_m
        if data.walk_6m_time_sec is not None and walk_distance is None:
            walk_distance = DEFAULT_WALK_DISTANCE_M

        assessment = PhysicalAssessment(
            user_id=user.user_id,
            session_id=data.session_id,
            assessment_type=data.assessment_type,
            chair_stand_5_time_sec=data.chair_stand_5_time_sec,
            chair_stand_skipped=data.chair_stand_skipped,
            walk_6m_time_sec=data.walk_6m_time_sec,
            walk_6m_distance_m=walk_distance,
            walk_6m_speed_mps=self._calculate_walk_speed(walk_distance, data.walk_6m_time_sec),
            walk_6m_skipped=data.walk_6m_skipped,
            pain_reported=data.pain_reported,
            dizziness_reported=data.dizziness_reported,
            used_for_level_setting=data.used_for_level_setting,
        )
        await self.repo.create_physical_assessment(assessment)
        await self.session.commit()
        await self.session.refresh(assessment)
        return PhysicalAssessmentResponse.model_validate(assessment)

    @staticmethod
    def _calculate_walk_speed(distance_m: Decimal | None, time_sec: Decimal | None) -> Decimal | None:
        if distance_m is None or time_sec is None:
            return None
        return (distance_m / time_sec).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
