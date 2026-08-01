from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.health import PhysicalAssessment


class PhysicalAssessmentRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_physical_assessment(self, assessment: PhysicalAssessment) -> PhysicalAssessment:
        self.session.add(assessment)
        await self.session.flush()
        return assessment

    async def get_by_session(self, session_id: int, user_id: int) -> PhysicalAssessment | None:
        """세션에 저장된 체력검사(멱등 재제출 시 기존 반환용, #180). 유니크라 1건이지만 방어적으로 최신 1건."""
        stmt = (
            select(PhysicalAssessment)
            .where(PhysicalAssessment.session_id == session_id, PhysicalAssessment.user_id == user_id)
            .order_by(PhysicalAssessment.physical_assessment_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)

    async def get_latest_by_user(self, user_id: int) -> PhysicalAssessment | None:
        """사용자의 최신 체력검사 1건(#기록탭 §3.4 안전망 카드 — chair_stand_5_time_sec 읽기용)."""
        stmt = (
            select(PhysicalAssessment)
            .where(PhysicalAssessment.user_id == user_id)
            .order_by(PhysicalAssessment.created_at.desc(), PhysicalAssessment.physical_assessment_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)

    async def get_measured_history(self, user_id: int, limit: int) -> list[PhysicalAssessment]:
        """측정 완료(5STS 시간 존재) 이력, 최신순(#353). 스킵 기록은 추이 표시에 무의미해 제외한다."""
        stmt = (
            select(PhysicalAssessment)
            .where(
                PhysicalAssessment.user_id == user_id,
                PhysicalAssessment.chair_stand_5_time_sec.is_not(None),
            )
            .order_by(PhysicalAssessment.created_at.desc(), PhysicalAssessment.physical_assessment_id.desc())
            .limit(limit)
        )
        result = await self.session.scalars(stmt)
        return list(result.all())
