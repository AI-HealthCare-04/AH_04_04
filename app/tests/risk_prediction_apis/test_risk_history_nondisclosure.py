"""위험도 이력 응답의 공개 범위 회귀 방지.

연속 위험도와 근육 건강 점수는 기록탭 표시용 공개 필드다.
반면 내부 위험도 등급, 모델 버전, 모델 변형은 노출하지 않고,
서버가 비교 가능 여부를 comparison_status로 추상화한다.
"""

from app.apis.v1.risk_prediction_routers import get_risk_prediction_history
from app.dtos.risk_prediction import RiskPredictionHistoryItem


def test_history_item_exposes_display_scores_without_internal_model_fields() -> None:
    fields = set(RiskPredictionHistoryItem.model_fields.keys())

    assert "risk_score" in fields
    assert "muscle_score" in fields
    assert "score_band" in fields
    assert "cohort_version" in fields
    assert "risk_level" not in fields
    assert "model_version" not in fields
    assert "model_variant" not in fields


def test_history_item_shape_is_display_safe() -> None:
    # 표시용으로 허용된 필드만 존재해야 한다.
    assert set(RiskPredictionHistoryItem.model_fields.keys()) == {
        "prediction_id",
        "created_at",
        "risk_score",
        "muscle_score",
        "score_band",
        "cohort_version",
        "change_percentage_points",
        "comparison_status",
        "care_stage",
    }


def test_history_endpoint_is_wired() -> None:
    # response_model 배선이 유지되는지 최소 확인한다.
    assert callable(get_risk_prediction_history)
