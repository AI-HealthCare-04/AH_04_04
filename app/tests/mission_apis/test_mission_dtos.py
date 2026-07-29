# =====================================================================================
# Mission/Sensor DTO 검증 테스트 (DB 불필요).
# Pydantic이 enum/필수값을 제대로 막는지 확인합니다.
# =====================================================================================
import pytest
from pydantic import ValidationError

from app.dtos.mission import (
    ExerciseDetail,
    GameDetail,
    MissionLogCreateRequest,
    MissionLogUpdateRequest,
)
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


def test_mission_log_create_rejects_bad_game_type() -> None:
    # game_type을 DTO에서 enum으로 검증하므로, 잘못된 값은 내부 예외가 아니라 422(ValidationError)여야 한다.
    with pytest.raises(ValidationError):
        MissionLogCreateRequest.model_validate(
            {
                "mission_template_id": 1,
                "mission_type": "game",
                "status": "completed",
                "game_detail": {"game_type": "tetris", "completed": True},
            }
        )


def test_sensor_session_accepts_recognition_status() -> None:
    # 확정값 (sensor_type은 v7.8에서 제거 — 가속도계 단일)
    for value in ("success", "low_confidence", "failed", "manual_override"):
        req = SensorSessionCreateRequest.model_validate({"mission_log_id": 1, "recognition_status": value})
        assert req.recognition_status == value


@pytest.mark.parametrize("field", ["score", "duration_sec", "success_count", "mistake_count"])
def test_game_detail_rejects_negative_numeric_fields(field: str) -> None:
    with pytest.raises(ValidationError):
        GameDetail.model_validate({"game_type": "card_match", field: -1})


@pytest.mark.parametrize("field", ["reps", "sets", "met_value"])
def test_exercise_detail_rejects_negative_numeric_fields(field: str) -> None:
    with pytest.raises(ValidationError):
        ExerciseDetail.model_validate({field: -1})


@pytest.mark.parametrize("field", ["actual_value", "target_value"])
def test_mission_log_create_rejects_negative_numeric_fields(field: str) -> None:
    with pytest.raises(ValidationError):
        MissionLogCreateRequest.model_validate(
            {
                "mission_template_id": 1,
                "mission_type": "exercise",
                "status": "completed",
                field: -1,
            }
        )


@pytest.mark.parametrize("field", ["actual_value", "target_value"])
def test_mission_log_update_rejects_negative_numeric_fields(field: str) -> None:
    with pytest.raises(ValidationError):
        MissionLogUpdateRequest.model_validate({field: -1})


@pytest.mark.parametrize("field", ["detected_count", "duration_sec", "motion_score"])
def test_sensor_session_rejects_negative_numeric_fields(field: str) -> None:
    with pytest.raises(ValidationError):
        SensorSessionCreateRequest.model_validate({"mission_log_id": 1, "recognition_status": "success", field: -1})
