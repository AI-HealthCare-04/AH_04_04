"""backfill requires_kidney_check for existing high-protein mission

이미 시드된 `단백질 식사 기록` 템플릿은 과거 기본값(requires_kidney_check=False)으로
남아 있고, seed 스크립트는 같은 title을 건너뛰므로 새 True 값으로 갱신되지 않는다.
이 데이터 마이그레이션이 없으면 기존 DB에서 GET 필터/POST 차단 모두 발동하지 않아
신장/단백질 안전 필터가 무동작한다.

Revision ID: 0005_backfill_kidney_check
Revises: 0004_add_activity_change_logs
Create Date: 2026-07-13 00:00:00
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0005_backfill_kidney_check"
down_revision: str | None = "0004_add_activity_change_logs"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# 데이터 전용 마이그레이션(스키마 변경 없음). 한글 title은 파라미터 바인딩으로 안전 처리한다.
_HIGH_PROTEIN_TITLE = "단백질 식사 기록"
_mission_templates = sa.table(
    "mission_templates",
    sa.column("title", sa.String),
    sa.column("requires_kidney_check", sa.Boolean),
)


def upgrade() -> None:
    op.execute(
        _mission_templates.update()
        .where(_mission_templates.c.title == _HIGH_PROTEIN_TITLE)
        .values(requires_kidney_check=True)
    )


def downgrade() -> None:
    op.execute(
        _mission_templates.update()
        .where(_mission_templates.c.title == _HIGH_PROTEIN_TITLE)
        .values(requires_kidney_check=False)
    )
