from typing import Literal

from pydantic import BaseModel, Field


class StsOverlayShownRequest(BaseModel):
    """근력 기능 안전망 카드(#기록탭 §3.4) 노출 이벤트. 앱이 카드를 실제로 띄울 때 1건 전송한다.

    입력 경계를 저장 스키마(sts_overlay_events)에 맞춰 좁혀, 초과 입력이 commit 단계 500 이 아니라
    422 로 거부되게 한다(지영 리뷰):
      - score_band: 점수 구간 확정값(good/maintain/caution)만 허용 → DB String(20) 초과 불가.
      - sts_sec: Numeric(5,2) → 최대 999.99.
      - bmi:     Numeric(4,1) → 최대 999.9.
    """

    tier: Literal["basic", "strong"]
    sts_sec: float | None = Field(default=None, ge=0, le=999.99)
    bmi: float | None = Field(default=None, ge=0, le=999.9)
    score_band: Literal["good", "maintain", "caution"] | None = None
