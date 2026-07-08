# =====================================================================================
# Sensor 도메인 Repository — sensor_sessions 저장만 담당.
# =====================================================================================
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.missions import MissionLog, SensorSession


class SensorRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def mission_log_exists(self, mission_log_id: int, user_id: int) -> bool:
        """본인 소유의 mission_log가 존재하는지 확인 (센서는 mission_log에 종속)."""
        stmt = select(MissionLog.mission_log_id).where(
            MissionLog.mission_log_id == mission_log_id,
            MissionLog.user_id == user_id,
        )
        return await self.session.scalar(stmt) is not None

    async def create_sensor_session(self, sensor_session: SensorSession) -> SensorSession:
        self.session.add(sensor_session)
        await self.session.flush()  # PK 채우기
        return sensor_session
