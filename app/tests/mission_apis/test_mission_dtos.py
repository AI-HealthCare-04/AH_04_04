# =====================================================================================
# Mission/Sensor DTO 검증 테스트 (DB 불필요).
# Pydantic이 enum/필수값을 제대로 막는지 확인합니다.
# =====================================================================================
import pytest
from pydantic import ValidationError

from app.dtos.mission import MissionLogCreateRequest
from app.dtos.sensor import SensorSessionCreateRequest


def test_mission_log_create_accepts_valid_meal() -> None:
    req = MissionLogCreateRequest.model_validate(
        {
            "mission_template_id": 300,
            "mission_type": "meal",
            "status": "completed",
            "success": True,
            "meal_detail": {"protein_foods": ["egg", "tofu"], "protein_meal_count": 1},
        }
    )
    assert req.mission_type.value == "meal"
    assert req.meal_detail is not None
    assert req.meal_detail.protein_meal_count == 1


def test_mission_log_create_rejects_bad_mission_type() -> None:
    with pytest.raises(ValidationError):
        MissionLogCreateRequest.model_validate(
            {"mission_template_id": 1, "mission_type": "swimming", "status": "completed"}
        )


def test_sensor_session_accepts_v71_recognition_status() -> None:
    # v7.1 확정값
    for value in ("success", "low_confidence", "failed", "manual_override"):
        req = SensorSessionCreateRequest.model_validate(
            {"mission_log_id": 1, "sensor_type": "step_counter", "recognition_status": value}
        )
        assert req.recognition_status == value


def test_sensor_session_rejects_unknown_sensor_type() -> None:
    with pytest.raises(ValidationError):
        SensorSessionCreateRequest.model_validate(
            {"mission_log_id": 1, "sensor_type": "pedometer", "recognition_status": "success"}
        )
