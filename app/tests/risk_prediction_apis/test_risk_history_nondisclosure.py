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
    # 기준 변경 사유(#389)는 내부 식별자를 그대로 흘리는 게 아니라 사용자가 이해할 수 있는
    #   값으로 추상화한 것이다 — 모델 버전·변형 비노출 계약은 위 단언으로 그대로 유지된다.
    assert "baseline_change_reason" in fields


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
        "baseline_change_reason",
        "profile_changed",
        "care_stage",
    }


def test_history_endpoint_is_wired() -> None:
    # response_model 배선이 유지되는지 최소 확인한다.
    assert callable(get_risk_prediction_history)
