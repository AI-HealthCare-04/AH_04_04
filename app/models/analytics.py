from datetime import datetime
from decimal import Decimal

from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, Numeric, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class StsOverlayEvent(Base):
    """근력 기능 안전망 카드(#기록탭 §3.4) 노출 이벤트 — 주간 발화율의 **분자**(#373).

    앱이 카드를 실제로 노출할 때 1건 전송한다. 분모 이벤트(점수 화면 조회)는 앱 미배선으로 폐기했다(#429).
    지표 정의·중복·보존 정책은 docs/sts_overlay_metrics.md 가 단일 원천이다. 요약:
      - 중복 허용: 앱 fire-and-forget 재시도로 같은 노출이 여러 행일 수 있다 — 집계가
        COUNT(DISTINCT user_id) 라 지표에 영향 없음(멱등키 없음이 결정사항).
      - 보존 90일: scripts.prune_sts_events 로 운영자가 월 1회 정리.
      - sts_sec/bmi/score_band 는 발화 조건(5STS ≥ 12초, BMI ≥ 25 티어, 구간 ≠ caution)의
        **노출 시점 입력값 스냅샷**이다 — 컷 재조정 분석에 필요해 유지(저장 사유 문서화).
    tier/score_band 는 향후 값이 늘 수 있어 Enum 아닌 String 으로 둔다.
    """

    __tablename__ = "sts_overlay_events"
    # 기간 집계(주간 발화율)가 인덱스만으로 돌도록 (created_at, user_id) 커버링 인덱스(#373).
    __table_args__ = (Index("ix_sts_overlay_events_created_user", "created_at", "user_id"),)

    event_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    tier: Mapped[str] = mapped_column(String(10), nullable=False)  # basic | strong
    sts_sec: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    bmi: Mapped[Decimal | None] = mapped_column(Numeric(4, 1), nullable=True)
    score_band: Mapped[str | None] = mapped_column(String(20), nullable=True)  # good | maintain | caution
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
