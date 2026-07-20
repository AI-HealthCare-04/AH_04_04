"""remove voice parser residue

음성 입력(파서/엔드포인트)은 팀 결정으로 전면 철회(#107). 관련 잔재를 스키마에서도 제거한다.
  - health_check_sessions.raw_transcript 컬럼(음성 전용) DROP
  - input_method enum 3종(health/profile/mission_logs)에서 'voice' 값 제거

downgrade는 복구 가능: raw_transcript 컬럼과 'voice' enum 값을 되돌린다(과거 데이터 자체는 없음).
철회 전 'voice'로 저장된 행이 있어도 ALTER가 잘리지 않도록 먼저 'form'으로 정규화한다.

Revision ID: 0006_remove_voice_parser
Revises: 0005_backfill_kidney_check
Create Date: 2026-07-20 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006_remove_voice_parser"
down_revision: str | None = "0005_backfill_kidney_check"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_WITH_VOICE = "ENUM('form','voice','service_log','sensor','manual')"
_WITHOUT_VOICE = "ENUM('form','service_log','sensor','manual')"


def upgrade() -> None:
    # 잔여 'voice' 행을 form으로 정규화(없을 것으로 예상되나 ALTER 잘림 방지).
    op.execute("UPDATE health_check_sessions SET input_method = 'form' WHERE input_method = 'voice'")
    op.execute("UPDATE health_profiles SET input_method = 'form' WHERE input_method = 'voice'")
    op.execute("UPDATE mission_logs SET input_method = 'form' WHERE input_method = 'voice'")

    op.execute(f"ALTER TABLE health_check_sessions MODIFY COLUMN input_method {_WITHOUT_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE health_profiles MODIFY COLUMN input_method {_WITHOUT_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE mission_logs MODIFY COLUMN input_method {_WITHOUT_VOICE} NULL")

    op.drop_column("health_check_sessions", "raw_transcript")


def downgrade() -> None:
    op.add_column("health_check_sessions", sa.Column("raw_transcript", sa.Text(), nullable=True))

    op.execute(f"ALTER TABLE health_check_sessions MODIFY COLUMN input_method {_WITH_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE health_profiles MODIFY COLUMN input_method {_WITH_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE mission_logs MODIFY COLUMN input_method {_WITH_VOICE} NULL")
