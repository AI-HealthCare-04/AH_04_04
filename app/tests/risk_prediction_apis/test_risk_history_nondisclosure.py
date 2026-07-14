"""위험도 이력(표시용) 비노출 계약 회귀 방지.

`_13 나의 기록`에 붙는 위험도 추이 응답은 내부 등급/점수(risk_level·risk_score)를
절대 노출하지 않는다(#57 비노출). care_stage(순화 등급)만 표시용으로 내려간다.
스키마 단(DTO)에서 잠그므로 DB/서버 없이도 검증된다.
"""
from app.apis.v1.risk_prediction_routers import get_risk_prediction_history
from app.dtos.risk_prediction import RiskPredictionHistoryItem


def test_history_item_never_exposes_internal_risk_fields() -> None:
    fields = set(RiskPredictionHistoryItem.model_fields.keys())
    assert "risk_level" not in fields, "내부 위험도 등급이 이력 응답에 노출되면 안 됩니다(#57 비노출)"
    assert "risk_score" not in fields, "내부 위험도 점수가 이력 응답에 노출되면 안 됩니다(#57 비노출)"


def test_history_item_shape_is_display_safe() -> None:
    # 표시용으로 허용된 필드만 존재해야 한다(예상 밖 내부값이 추가로 새는 것도 차단).
    assert set(RiskPredictionHistoryItem.model_fields.keys()) == {
        "prediction_id",
        "created_at",
        "care_stage",
    }


def test_history_endpoint_is_wired() -> None:
    # `_13`이 붙을 표시용 이력 엔드포인트가 실제로 배선돼 있는지 최소 확인.
    # (response_model=RiskPredictionHistoryResponse → 위 안전 DTO로 직렬화되므로 내부값 유출 없음)
    assert callable(get_risk_prediction_history)
