# =====================================================================================
# Sensor 도메인 DTO — API 명세서 v7.8 기준
#   - POST /sensor-sessions : 가속도계 단일 센서 측정 결과 저장 (sensor_type 없음)
#
# ⚠️ recognition_status 값은 확정값(success/low_confidence/failed/manual_override)을 씁니다.
#    (모델 enum이 아니라 확정값을 Literal로 직접 검증합니다.)
#    sensor_type은 v7.8에서 제거됨 — 가속도계 단일이라 서버가 상수(SensorType.ACCELEROMETER)로 저장합니다.
# =====================================================================================
from typing import Any, Literal

from pydantic import BaseModel

RecognitionStatusLiteral = Literal["success", "low_confidence", "failed", "manual_override"]


class SensorSessionCreateRequest(BaseModel):
    mission_log_id: int
    detected_count: int | None = None
    duration_sec: int | None = None
    motion_score: float | None = None
    recognition_status: RecognitionStatusLiteral
    raw_summary: dict[str, Any] | None = None


class SensorSessionCreateResponse(BaseModel):
    sensor_session_id: int
    recognition_status: str
