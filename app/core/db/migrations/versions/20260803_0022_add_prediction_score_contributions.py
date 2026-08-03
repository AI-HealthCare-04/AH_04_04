"""Add derived SHAP score contributions to predictions (#406).

근육 점수의 SHAP 기여도(#406)를 응답에 노출한다. 이전 설계는 저장된 원본 입력
(`input_snapshot`)으로 응답 시점에 재계산했으나, 0021(#408)이 원본 보관을 없애면서
그 경로가 사라졌다. 그래서 노출 특징 3개(근력·걷기·허리)의 **파생 기여도만** 예측 시점에
계산해 이 컬럼에 고정 저장한다(원본은 여전히 저장하지 않는다 — 최소화 유지).

순수 추가 컬럼(nullable JSON)이라 파괴적 경로·백필 가드가 없다. 0021 이전 구행은 원본이
이미 폐기돼 백필 소스가 없으므로 NULL 로 남고, 응답에서 빈 목록으로 나간다(신규 예측만
기여도를 제공하는 계약 — 서비스 테스트가 못박는다).

Revision ID: 0022_add_score_contributions
Revises: 0021_drop_input_snapshot
Create Date: 2026-08-03 20:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0022_add_score_contributions"
down_revision: str = "0021_drop_input_snapshot"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("risk_predictions", sa.Column("score_contributions", sa.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("risk_predictions", "score_contributions")
