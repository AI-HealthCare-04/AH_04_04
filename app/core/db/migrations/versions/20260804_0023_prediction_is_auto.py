"""Separate the auto (nightly) prediction from the user's manual reassessment.

자정 자동 예측을 켜면 하루 1회 정책(#388)과 정면으로 부딪힌다. 자동 예측이 `is_reassessment`
하나만 보고 저장되면 **그날의 1회를 자동이 소진**해, 사용자가 내 정보를 고치고 재평가를 눌러도
`get_today_reassessment` 가 자동 예측을 그대로 돌려준다 — 수정이 다음 날까지 반영되지 않는다.

그래서 카운터를 나눈다. `is_auto` 로 트리거 주체를 구분해 **자동 1회 + 수동 1회**를 각각
보장한다. `is_reassessment` 는 '재평가 경로로 만들어진 예측인가'(온보딩 최초 예측이 아닌가)를
뜻하고, '누가 시켰나'만 새 컬럼이 답한다 — 자동도 재평가 경로라 둘 다 참인 행이 정상이다.

⚠️ `is_reassessment` 는 **활동 일수를 실기록에서 셌는지를 뜻하지 않는다.** 8일차 게이트
(`ACTIVITY_REFLECTION_START_DAY`) 때문에 온보딩 완료 7일 이내의 재평가는 실기록 대신 자가응답
값으로 계산되지만, 하루 1회 정책의 멱등 판정에 필요하므로 그 행도 `is_reassessment=1` 이다.
실제 활동 입력 출처는 이 컬럼이 아니라 예측 시점의 게이트 통과 여부로 판정한다.

| is_reassessment | is_auto | 뜻                                   |
|---|---|---|
| 0 | 0 | 온보딩 최초 예측(자가응답 활동 일수)        |
| 1 | 0 | 사용자가 누른 재평가                      |
| 1 | 1 | 자정 배치가 만든 자동 예측                 |

⚠️ 순서(#410 에서 이어지는 규칙): MySQL 은 DDL 이 암시적 커밋이라, 컬럼을 추가한 뒤
실패하면 revision 은 이전에 머문 채 컬럼만 남아 재실행이 duplicate column 으로 막힌다.
그래서 컬럼 추가를 **조건부**로 둔다. 백필은 없다 — 이 마이그레이션 이전의 예측은 전부
사람이 만든 것이라 server_default 0 이 곧 정답이다.

Revision ID: 0023_prediction_is_auto
Revises: 0022_prediction_is_reassessment
Create Date: 2026-08-04 18:30:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0023_prediction_is_auto"
down_revision: str = "0022_prediction_is_reassessment"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_COLUMN_EXISTS = (
    "SELECT COUNT(*) FROM information_schema.columns "
    "WHERE table_schema = DATABASE() AND table_name = 'risk_predictions' "
    "AND column_name = 'is_auto'"
)

_INDEX_EXISTS = (
    "SELECT COUNT(*) FROM information_schema.statistics "
    "WHERE table_schema = DATABASE() AND table_name = 'risk_predictions' "
    "AND index_name = 'ix_risk_predictions_user_auto_created'"
)


def _has_column(bind: sa.engine.Connection) -> bool:
    return bool(bind.execute(sa.text(_COLUMN_EXISTS)).scalar())


def _has_index(bind: sa.engine.Connection) -> bool:
    return bool(bind.execute(sa.text(_INDEX_EXISTS)).scalar())


def upgrade() -> None:
    bind = op.get_bind()
    if not _has_column(bind):
        # server_default 로 추가해야 기존 행이 NOT NULL 을 만족한다. 애플리케이션은 default=False 를 쓴다.
        op.add_column(
            "risk_predictions",
            sa.Column("is_auto", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        )
    if not _has_index(bind):
        # 자정 배치가 사용자마다 "오늘 자동 예측이 있나"를 묻는다. 대상 전원을 도는 조회라
        #   (user_id, is_auto, created_at) 복합 인덱스가 없으면 사용자 수만큼 풀스캔이 된다.
        op.create_index(
            "ix_risk_predictions_user_auto_created",
            "risk_predictions",
            ["user_id", "is_auto", "created_at"],
        )


def downgrade() -> None:
    bind = op.get_bind()
    if _has_index(bind):
        op.drop_index("ix_risk_predictions_user_auto_created", table_name="risk_predictions")
    if _has_column(bind):
        op.drop_column("risk_predictions", "is_auto")
