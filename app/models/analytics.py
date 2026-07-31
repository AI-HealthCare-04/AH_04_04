from datetime import datetime
from decimal import Decimal

from sqlalchemy import BigInteger, DateTime, ForeignKey, Numeric, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class StsOverlayEvent(Base):
    """근력 기능 안전망 카드(#기록탭 §3.4) 노출 이벤트 — 발화율 관측용(지시서 §3.4 필수).

    앱이 카드를 실제로 노출할 때 1건 전송한다. 주간 발화율(카드 노출 사용자 / 점수 화면 조회 사용자)의
    분자(카드 노출)를 이 테이블로 수집해, 출시 초기 컷(12초)·조건 조정 근거로 쓴다.
    (분모인 '점수 화면 조회'는 별도 뷰 이벤트가 필요 — 후속.)
    tier/score_band 는 향후 값이 늘 수 있어 Enum 아닌 String 으로 둔다.
    """

    __tablename__ = "sts_overlay_events"

    event_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    tier: Mapped[str] = mapped_column(String(10), nullable=False)  # basic | strong
    sts_sec: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    bmi: Mapped[Decimal | None] = mapped_column(Numeric(4, 1), nullable=True)
    score_band: Mapped[str | None] = mapped_column(String(20), nullable=True)  # good | maintain | caution
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
