"""난이도 폐기(#428)·미사용 라우트 정리(#429): 관련 테이블 3개 제거.

명세서 v1.3 기준으로 앱이 쓰지 않는 기능의 저장소를 정리한다.
  - user_activity_profiles / activity_level_change_logs : 운동 난이도 폐기(#428).
    걷기 목표가 일일 20분 단일로 확정되어 레벨 상태·변경 이력을 저장할 이유가 없다.
  - sts_score_view_events : 발화율 분모 이벤트(#373)가 앱에 배선되지 않아 라우트와 함께
    폐기(#429). 분자(sts_overlay_events)는 사용 중이라 유지한다.

⚠️ 재실행 가능성(#410 규칙): MySQL DDL 은 암시적 커밋이라 중간 실패 시 반쯤 적용된 DB 가
   남을 수 있다. 모든 drop 을 존재 확인 후에만 실행해, 같은 DB 에 그대로 재실행할 수 있게 한다.
   downgrade 는 구조만 재생성하고 데이터는 복원하지 않는다(레벨 상태는 파생값 — 5STS 원본은
   physical_assessments 에 그대로 남아 있다). 재생성 정의는 0001·0003(enum 정렬)·0019 결과와 같다. (#426의 0023 뒤로 재번호)

Revision ID: 0024_drop_level_tables
Revises: 0023_prediction_is_auto
Create Date: 2026-08-05 10:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0024_drop_level_tables"
down_revision: str = "0023_prediction_is_auto"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _has_table(table_name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(table_name)


def upgrade() -> None:
    for table_name in ("activity_level_change_logs", "user_activity_profiles", "sts_score_view_events"):
        if _has_table(table_name):
            op.drop_table(table_name)


def downgrade() -> None:
    if not _has_table("user_activity_profiles"):
        op.create_table(
            "user_activity_profiles",
            sa.Column("activity_profile_id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), nullable=False),
            sa.Column(
                "current_level", sa.Enum("easy", "normal", "hard", name="activity_level_enum"), nullable=False
            ),
            sa.Column(
                "level_reason",
                sa.Enum("initial_test", "rule", "llm_recommendation", "user_selected", name="level_reason_enum"),
                nullable=False,
            ),
            sa.Column("physical_assessment_id", sa.BigInteger(), nullable=True),
            sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
            sa.Column(
                "updated_at",
                sa.DateTime(timezone=True),
                server_default=sa.text("CURRENT_TIMESTAMP"),
                nullable=False,
            ),
            sa.ForeignKeyConstraint(["physical_assessment_id"], ["physical_assessments.physical_assessment_id"]),
            sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
            sa.PrimaryKeyConstraint("activity_profile_id"),
            sa.UniqueConstraint("user_id"),
        )
        op.create_index(
            "ix_user_activity_profiles_physical_assessment_id", "user_activity_profiles", ["physical_assessment_id"]
        )
    if not _has_table("activity_level_change_logs"):
        op.create_table(
            "activity_level_change_logs",
            sa.Column("level_change_id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), nullable=False),
            sa.Column("from_level", sa.Enum("easy", "normal", "hard", name="activity_level_enum"), nullable=False),
            sa.Column("to_level", sa.Enum("easy", "normal", "hard", name="activity_level_enum"), nullable=False),
            sa.Column(
                "reason_type",
                sa.Enum("rule", "llm_recommendation", "user_request", name="reason_type_enum"),
                nullable=False,
            ),
            sa.Column("reason_text", sa.Text(), nullable=True),
            sa.Column("accepted_by_user", sa.Boolean(), nullable=False),
            sa.Column(
                "created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False
            ),
            sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
            sa.PrimaryKeyConstraint("level_change_id"),
        )
        op.create_index("ix_activity_level_change_logs_user_id", "activity_level_change_logs", ["user_id"])
    if not _has_table("sts_score_view_events"):
        op.create_table(
            "sts_score_view_events",
            sa.Column("event_id", sa.BigInteger(), autoincrement=True, nullable=False),
            sa.Column("user_id", sa.BigInteger(), nullable=False),
            sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
            sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
            sa.PrimaryKeyConstraint("event_id"),
        )
        op.create_index("ix_sts_score_view_events_user_id", "sts_score_view_events", ["user_id"])
        op.create_index("ix_sts_score_view_events_created_user", "sts_score_view_events", ["created_at", "user_id"])
