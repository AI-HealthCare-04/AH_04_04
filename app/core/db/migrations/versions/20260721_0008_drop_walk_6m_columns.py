"""drop walk_6m columns from physical_assessments

6m 걷기는 팀 결정으로 미구현·제외(#102/#109). 밴드는 5STS 단독(PR #103)으로 이미 전환됐고,
앱의 6m 입력도 제거(PR #115)됐으므로, 마지막으로 physical_assessments의 6m 컬럼 4개를 제거한다.

⚠️ 파괴적 변경(복구 불가). 안전장치(리뷰 #103, voice #107과 동일 패턴):
  upgrade는 6m '측정값'(time/distance/speed)이 non-null인 행이 0건인지 먼저 검증한다.
  한 건이라도 있으면 DDL 이전에 실패시켜, 컬럼 DROP으로 측정 데이터를 조용히 삭제하지 않는다.
  실 데이터가 있는 환경이면 마이그레이션이 중단되므로, 백업/내보내기를 수동 확정(0건)한 뒤 재실행한다.
  downgrade는 컬럼(스키마)만 되돌린다 — 제거 시점에 0건이 보장되므로 복원할 데이터 자체가 없다.

⚠️ walk_6m_skipped(파생 플래그)는 가드 대상이 '아니다'(의도적 폐기, 리뷰 #118-2).
  구 정규화 `walk_skipped_stored = data.walk_6m_skipped or not walk_provided` 때문에 6m를 안 낸
  '모든' 평가 행이 skipped=1이 된다. 이를 가드에 넣으면 5STS 단독 평가가 하나라도 있는 DB에서
  마이그레이션이 '항상' 중단돼 실행 불가가 된다. skipped는 측정값이 아니라 "6m 안 함" 기록이라
  보존 가치가 없으므로 컬럼과 함께 폐기한다(측정값만 가드).

Revision ID: 0008_drop_walk_6m_columns
Revises: 0007_oauth_login_nonces
Create Date: 2026-07-21 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0008_drop_walk_6m_columns"
down_revision: str | None = "0007_oauth_login_nonces"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # [안전가드] 6m 측정값이 실재하면 조용히 삭제하지 않고 마이그레이션을 '실패'시킨다(리뷰 #103).
    residue = (
        op.get_bind()
        .execute(
            sa.text(
                "SELECT COUNT(*) FROM physical_assessments "
                "WHERE walk_6m_time_sec IS NOT NULL "
                "OR walk_6m_distance_m IS NOT NULL "
                "OR walk_6m_speed_mps IS NOT NULL"
            )
        )
        .scalar_one()
    )
    if residue:
        raise RuntimeError(
            f"0008_drop_walk_6m_columns 중단: 6m 측정 데이터가 {residue}건 존재합니다. "
            "컬럼 DROP은 이 데이터를 복구 불가하게 삭제하므로, 대상 환경에서 백업/내보내기를 "
            "수동 확정(0건 확인)한 뒤 재실행하세요. (자동 삭제로 데이터를 숨기지 않기 위한 의도적 실패입니다.)"
        )

    op.drop_column("physical_assessments", "walk_6m_skipped")
    op.drop_column("physical_assessments", "walk_6m_speed_mps")
    op.drop_column("physical_assessments", "walk_6m_distance_m")
    op.drop_column("physical_assessments", "walk_6m_time_sec")


def downgrade() -> None:
    # 스키마(컬럼)만 복원. 제거 시점 0건이 보장돼 복원할 데이터 자체가 없다.
    op.add_column(
        "physical_assessments",
        sa.Column("walk_6m_time_sec", sa.Numeric(5, 2), nullable=True),
    )
    op.add_column(
        "physical_assessments",
        sa.Column("walk_6m_distance_m", sa.Numeric(4, 2), nullable=True),
    )
    op.add_column(
        "physical_assessments",
        sa.Column("walk_6m_speed_mps", sa.Numeric(5, 2), nullable=True),
    )
    op.add_column(
        "physical_assessments",
        sa.Column("walk_6m_skipped", sa.Boolean(), nullable=False, server_default=sa.text("0")),
    )
