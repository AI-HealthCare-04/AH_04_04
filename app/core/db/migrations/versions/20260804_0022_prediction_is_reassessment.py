"""Move the reassessment marker onto predictions so profiles stop being duplicated (#408 A+3).

재평가 여부를 프로필의 `input_method = service_log` 로 판별하고 있었다. 그 판별을 위해
**재평가마다 health_profiles 행을 통째로 복제**해야 했다 — 실제로 달라지는 값은 walk_days·
musc_days 뿐인데 생년월일·성별·키·몸무게·BMI·허리둘레·신장 상태·단백질 제한까지 매번 같이
복사됐다. 배포 DB 실측으로 사용자 87명에 프로필 129행, 한 사용자 최대 14행이었다.

판별 근거를 `risk_predictions.is_reassessment` 로 옮기면 재평가가 프로필 행을 만들 이유가
없어진다. 이 마이그레이션은 **컬럼 추가와 백필까지만** 한다(#396 하루 1회 정책이 끊기지 않게).
기존 복제 행 정리는 별건이다 — 예측이 그 행을 FK 로 가리키고 있어 되돌릴 수 없다.

⚠️ 순서(#410 에서 배운 것): MySQL 은 DDL 이 암시적 커밋이라, 컬럼을 추가한 뒤 실패하면
revision 은 이전에 머문 채 컬럼만 남아 재실행이 duplicate column 으로 막힌다. 그래서
컬럼 추가를 **조건부**로 두고 백필도 멱등으로 만든다.

Revision ID: 0022_prediction_is_reassessment
Revises: 0021_drop_input_snapshot
Create Date: 2026-08-04 13:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0022_prediction_is_reassessment"
down_revision: str = "0021_drop_input_snapshot"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# InputMethod.SERVICE_LOG 의 저장값. 재평가가 만든 프로필만 이 값을 갖는다(온보딩 최초는 form).
SERVICE_LOG = "service_log"

_COLUMN_EXISTS = (
    "SELECT COUNT(*) FROM information_schema.columns "
    "WHERE table_schema = DATABASE() AND table_name = 'risk_predictions' "
    "AND column_name = 'is_reassessment'"
)

# 기존 행의 판별: 예측이 가리키는 프로필이 재평가본이면 재평가 예측이다.
#   JOIN 이 비는 경우(프로필 삭제 등)는 0 으로 남겨 둔다 — 하루 1회 정책은 '재평가였다'가
#   확실할 때만 막아야 하고, 모르는 행 때문에 오늘 재평가가 차단되면 안 된다.
_BACKFILL = f"""
    UPDATE risk_predictions rp
      JOIN health_profiles hp ON hp.profile_id = rp.profile_id
       SET rp.is_reassessment = 1
     WHERE hp.input_method = '{SERVICE_LOG}'
"""


def _has_column(bind: sa.engine.Connection) -> bool:
    return bool(bind.execute(sa.text(_COLUMN_EXISTS)).scalar())


def upgrade() -> None:
    bind = op.get_bind()
    if not _has_column(bind):
        # server_default 로 추가해야 기존 행이 NOT NULL 을 만족한다. 애플리케이션은 default=False 를 쓴다.
        op.add_column(
            "risk_predictions",
            sa.Column("is_reassessment", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        )
    # 멱등: 이미 1 인 행을 다시 1 로 써도 결과가 같다.
    bind.execute(sa.text(_BACKFILL))


def downgrade() -> None:
    bind = op.get_bind()
    if _has_column(bind):
        op.drop_column("risk_predictions", "is_reassessment")
