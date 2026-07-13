# =====================================================================================
# Sensor 도메인 Service — 센서 세션 저장.
#
# ⚠️ recognition_status는 v7.1 값(success/low_confidence/failed/manual_override)을 그대로
#    DB에 넣습니다. dev 모델 enum이 아직 recognized/... 라서, 0002 마이그레이션 머지 전에는
#    실제 저장이 enum 위반으로 실패할 수 있습니다. (코드는 v7.1 기준, 저장 테스트는 0002 이후)
# =====================================================================================
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.sensor import SensorSessionCreateRequest, SensorSessionCreateResponse
from app.models.enums import RecognitionStatus, SensorType
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
            # 가속도계 단일 센서라 요청에서 sensor_type을 받지 않고 서버가 상수로 저장한다(v7.8).
            sensor_type=SensorType.ACCELEROMETER,
            detected_count=data.detected_count,
            duration_sec=data.duration_sec,
            motion_score=data.motion_score,
            # ⚠️ 0002 머지 전에는 "success"/"manual_override"가 모델 enum에 없어 여기서 ValueError가 날 수 있음.
            recognition_status=RecognitionStatus(data.recognition_status),
            raw_summary=data.raw_summary,
        )
        await self.repo.create_sensor_session(sensor_session)
        await self.session.commit()
        return SensorSessionCreateResponse(
            sensor_session_id=sensor_session.sensor_session_id,
            recognition_status=data.recognition_status,
        )
