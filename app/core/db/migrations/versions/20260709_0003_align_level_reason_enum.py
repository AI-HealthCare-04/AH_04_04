"""align level_reason enum to spec (default/reassessment 제거, llm_recommendation 추가)

Revision ID: 0003_align_level_reason_enum
Revises: 0002_align_v71_enums
Create Date: 2026-07-09 00:00:00

user_activity_profiles.level_reason(level_reason_enum)를 명세 확정값
(initial_test/rule/llm_recommendation/user_selected)으로 정렬한다.
- 백필: default → rule (건너뛴 사용자 기본은 서버 규칙), reassessment → initial_test (평가 흐름 유래)
- 재평가 여부는 physical_assessments.assessment_type=reassessment로 표현하므로 level_reason에선 제거
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0003_align_level_reason_enum"
down_revision: str | None = "0002_align_v71_enums"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 1) 넓히기: 구값 + 신규값(llm_recommendation) superset로 확장
    op.execute(
        "ALTER TABLE user_activity_profiles "
        "MODIFY COLUMN level_reason "
        "ENUM('rule','initial_test','reassessment','user_selected','default','llm_recommendation') NOT NULL"
    )
    # 2) 백필: 제거 예정 값을 유지 값으로 매핑
    op.execute("UPDATE user_activity_profiles SET level_reason = 'rule' WHERE level_reason = 'default'")
    op.execute("UPDATE user_activity_profiles SET level_reason = 'initial_test' WHERE level_reason = 'reassessment'")
    # 3) 좁히기: 명세 확정값으로 고정
    op.execute(
        "ALTER TABLE user_activity_profiles "
        "MODIFY COLUMN level_reason "
        "ENUM('initial_test','rule','llm_recommendation','user_selected') NOT NULL"
    )


def downgrade() -> None:
    # 넓히기 → llm_recommendation은 되돌릴 원본이 없어 rule로 접는다.
    op.execute(
        "ALTER TABLE user_activity_profiles "
        "MODIFY COLUMN level_reason "
        "ENUM('rule','initial_test','reassessment','user_selected','default','llm_recommendation') NOT NULL"
    )
    op.execute("UPDATE user_activity_profiles SET level_reason = 'rule' WHERE level_reason = 'llm_recommendation'")
    # 0002 이후 스키마로 복원. default/reassessment 백필은 비가역이라 원복하지 않는다.
    op.execute(
        "ALTER TABLE user_activity_profiles "
        "MODIFY COLUMN level_reason "
        "ENUM('rule','initial_test','reassessment','user_selected','default') NOT NULL"
    )
