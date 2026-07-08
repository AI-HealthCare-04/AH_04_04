# =====================================================================================
# Sensor 도메인 DTO — API 명세서 v7.1 기준
#   - POST /sensor-sessions : 만보기/가속도계 등 센서 측정 결과 저장
#
# ⚠️ recognition_status 값은 v7.1 확정값(success/low_confidence/failed/manual_override)을
#    씁니다. dev 모델은 아직 recognized/low_confidence/failed 라서, 실제 DB 저장은
#    지영님 0002 마이그레이션(recognized→success + manual_override 추가) 머지 후에 됩니다.
#    (그래서 여기서는 모델 enum이 아니라 v7.1 값을 Literal로 직접 검증합니다.)
# =====================================================================================
from typing import Any, Literal

from pydantic import BaseModel

# v7.1 확정값
SensorTypeLiteral = Literal["accelerometer", "gyroscope", "step_counter"]
RecognitionStatusLiteral = Literal["success", "low_confidence", "failed", "manual_override"]


class SensorSessionCreateRequest(BaseModel):
    mission_log_id: int
    sensor_type: SensorTypeLiteral
    detected_count: int | None = None
    duration_sec: int | None = None
    motion_score: float | None = None
    recognition_status: RecognitionStatusLiteral
    raw_summary: dict[str, Any] | None = None


class SensorSessionCreateResponse(BaseModel):
    sensor_session_id: int
    recognition_status: str
