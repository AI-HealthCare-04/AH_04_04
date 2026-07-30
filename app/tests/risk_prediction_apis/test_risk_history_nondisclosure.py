"""Risk history response exposes display-safe fields only.

Continuous risk score and peer-relative muscle score are public display fields.
Internal risk level, model version, and model variant remain hidden; the server
abstracts model comparability through comparison_status.
"""

from app.apis.v1.risk_prediction_routers import get_risk_prediction_history
from app.dtos.risk_prediction import RiskPredictionHistoryItem


def test_history_item_exposes_display_scores_without_internal_model_fields() -> None:
    fields = set(RiskPredictionHistoryItem.model_fields.keys())

    assert "risk_score" in fields
    assert "muscle_score" in fields
    assert "score_band" in fields
    assert "risk_level" not in fields
    assert "model_version" not in fields
    assert "model_variant" not in fields


def test_history_item_shape_is_display_safe() -> None:
    assert set(RiskPredictionHistoryItem.model_fields.keys()) == {
        "prediction_id",
        "created_at",
        "risk_score",
        "muscle_score",
        "score_band",
        "change_percentage_points",
        "comparison_status",
        "care_stage",
    }


def test_history_endpoint_is_wired() -> None:
    assert callable(get_risk_prediction_history)
