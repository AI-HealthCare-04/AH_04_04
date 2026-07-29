# =====================================================================================
# Sensor 도메인 Service — 센서 세션 저장.
#
# recognition_status는 DTO와 DB enum이 공유하는 확정값을 사용합니다.
# =====================================================================================
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.sensor import SensorSessionCreateRequest, SensorSessionCreateResponse
from app.models.enums import RecognitionStatus
from app.models.missions import SensorSession
from app.models.users import User
from app.repositories.sensor_repository import SensorRepository


class SensorService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = SensorRepository(session)

    async def create_sensor_session(self, user: User, data: SensorSessionCreateRequest) -> SensorSessionCreateResponse:
        # 센서 세션은 반드시 본인 소유의 mission_log에 종속되어야 함
        if not await self.repo.mission_log_exists(data.mission_log_id, user.user_id):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="센서 데이터가 올바르지 않습니다.",
            )

        sensor_session = SensorSession(
            mission_log_id=data.mission_log_id,
            detected_count=data.detected_count,
            duration_sec=data.duration_sec,
            motion_score=data.motion_score,
            recognition_status=RecognitionStatus(data.recognition_status),
            raw_summary=data.raw_summary,
        )
        await self.repo.create_sensor_session(sensor_session)
        await self.session.commit()
        return SensorSessionCreateResponse(
            sensor_session_id=sensor_session.sensor_session_id,
            recognition_status=data.recognition_status,
        )
