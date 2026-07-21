"""연속 위험도 공개 범위와 내부 모델 정보 비노출 계약 회귀 방지.

연속 risk_score와 변화량은 공개한다. 내부 등급·모델 식별자는 노출하지 않고
서버가 비교 가능 여부를 comparison_status로 추상화한다.
"""

from app.apis.v1.risk_prediction_routers import get_risk_prediction_history
from app.dtos.risk_prediction import RiskPredictionHistoryItem


def test_history_item_exposes_score_without_internal_model_fields() -> None:
    fields = set(RiskPredictionHistoryItem.model_fields.keys())
    assert "risk_level" not in fields, "내부 위험도 등급이 이력 응답에 노출되면 안 됩니다(#57 비노출)"
    assert "risk_score" in fields, "연속 위험도 추이를 위해 risk_score가 공개돼야 합니다"
    assert "model_version" not in fields, "모델 버전 비교는 서버가 comparison_status로 추상화해야 합니다"
    assert "model_variant" not in fields, "내부 모델 변형은 사용자 이력 응답에 불필요합니다"


def test_history_item_shape_is_display_safe() -> None:
    # 표시용으로 허용된 필드만 존재해야 한다(예상 밖 내부값이 추가로 새는 것도 차단).
    assert set(RiskPredictionHistoryItem.model_fields.keys()) == {
        "prediction_id",
        "created_at",
        "risk_score",
        "change_percentage_points",
        "comparison_status",
        "care_stage",
    }


def test_history_endpoint_is_wired() -> None:
    # `_13`이 붙을 표시용 이력 엔드포인트가 실제로 배선돼 있는지 최소 확인.
    # (response_model=RiskPredictionHistoryResponse → 위 안전 DTO로 직렬화되므로 내부값 유출 없음)
    assert callable(get_risk_prediction_history)
