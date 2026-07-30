from typing import Literal

from pydantic import BaseModel, Field


class StsOverlayShownRequest(BaseModel):
    """근력 기능 안전망 카드(#기록탭 §3.4) 노출 이벤트. 앱이 카드를 실제로 띄울 때 1건 전송한다."""

    tier: Literal["basic", "strong"]
    sts_sec: float | None = Field(default=None, ge=0)
    bmi: float | None = Field(default=None, ge=0)
    score_band: str | None = None
