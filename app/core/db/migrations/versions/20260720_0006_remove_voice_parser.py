"""remove voice parser residue

음성 입력(파서/엔드포인트)은 팀 결정으로 전면 철회(#107). 관련 잔재를 스키마에서도 제거한다.
  - health_check_sessions.raw_transcript 컬럼(음성 전용) DROP
  - input_method enum 3종(health/profile/mission_logs)에서 'voice' 값 제거

⚠️ 파괴적 변경(복구 불가). 안전장치(리뷰 #111):
  upgrade는 voice 행·non-null raw_transcript가 '0건'임을 먼저 검증한다. 한 건이라도 있으면
  DDL 이전에 실패(RuntimeError)시켜, 조용한 정규화(voice→form)·삭제로 입력출처 감사이력과
  transcript를 숨기지 않는다. 즉 '파괴 대상 데이터가 없음'을 보장한 뒤에만 제거가 진행된다.
  실 데이터가 있는 환경이면 upgrade가 애초에 중단되므로, 백업/폐기를 수동 확정한 뒤 재실행한다.
  downgrade는 컬럼·enum 값(스키마)만 되돌린다 — 제거 시점에 데이터가 0건이었으므로 복원할
  데이터 자체가 없다(스키마 복구로 충분). 이 데이터-손실 경로 회귀는
  scripts/check_migrations.py 의 가드 검증 단계에서 fixture로 못박는다.

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
    # [안전가드] 음성은 운영에 출시된 적이 없어 voice 행·raw_transcript가 0건이어야 한다.
    #   0건을 '가정'하고 조용히 정규화(voice→form)·DROP하면 입력출처 감사이력과 transcript가
    #   복구 불가하게 사라진다(리뷰 #111). 그래서 가정하지 않고 '검증'한다 — 한 건이라도 있으면
    #   DDL 이전에 실패시켜, 대상 환경에서 백업·폐기를 수동 확정(0건)하게 만든다.
    residue = (
        op.get_bind()
        .execute(
            sa.text(
                "SELECT "
                "(SELECT COUNT(*) FROM health_check_sessions WHERE input_method='voice')"
                "+(SELECT COUNT(*) FROM health_profiles WHERE input_method='voice')"
                "+(SELECT COUNT(*) FROM mission_logs WHERE input_method='voice')"
                "+(SELECT COUNT(*) FROM health_check_sessions WHERE raw_transcript IS NOT NULL)"
            )
        )
        .scalar_one()
    )
    if residue:
        raise RuntimeError(
            f"0006_remove_voice_parser 중단: 음성 관련 데이터가 {residue}건 존재합니다. "
            "이 마이그레이션은 voice 행·raw_transcript를 복구 불가하게 제거하므로, 대상 환경에서 "
            "백업/폐기를 수동 확정(0건 확인)한 뒤 재실행하세요. "
            "(자동 정규화·삭제로 감사이력을 숨기지 않기 위한 의도적 실패입니다.)"
        )

    # 아래 DDL은 음성 잔재가 0건일 때만 도달 → 조용한 정규화/삭제가 발생하지 않는다.
    op.execute(f"ALTER TABLE health_check_sessions MODIFY COLUMN input_method {_WITHOUT_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE health_profiles MODIFY COLUMN input_method {_WITHOUT_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE mission_logs MODIFY COLUMN input_method {_WITHOUT_VOICE} NULL")

    op.drop_column("health_check_sessions", "raw_transcript")


def downgrade() -> None:
    op.add_column("health_check_sessions", sa.Column("raw_transcript", sa.Text(), nullable=True))

    op.execute(f"ALTER TABLE health_check_sessions MODIFY COLUMN input_method {_WITH_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE health_profiles MODIFY COLUMN input_method {_WITH_VOICE} NOT NULL")
    op.execute(f"ALTER TABLE mission_logs MODIFY COLUMN input_method {_WITH_VOICE} NULL")
