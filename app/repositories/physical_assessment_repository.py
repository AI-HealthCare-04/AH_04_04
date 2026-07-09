from sqlalchemy.ext.asyncio import AsyncSession

from app.models.health import PhysicalAssessment


class PhysicalAssessmentRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_physical_assessment(self, assessment: PhysicalAssessment) -> PhysicalAssessment:
        self.session.add(assessment)
        await self.session.flush()
        return assessment
