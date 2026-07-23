"""운동 미션 목표 3회(count) → 10분(minutes) 데이터 이관

운동 성공 판정을 '당일 누적 시간 >= 목표(분)'으로 바꾸면서(services/mission.py) 목표 단위도
분으로 통일했다. 그런데 이관을 시드 스크립트에만 두면, 시드는 배포 시 자동 실행되지 않아
배포 직후 운영 DB 가 잠시 3/count 로 남는다. 그 창에서는 서버가 '3분'을 목표로 잘못 판정한다
(리뷰 #159).

alembic upgrade head 는 배포 파이프라인(deploy.yml)에 포함되므로, 이관을 마이그레이션으로 옮기면
배포와 이관이 원자적이 된다. 새 DB 는 시드가 곧장 10/minutes 로 넣으므로 이 마이그레이션은
아무 행도 건드리지 않는다(멱등).

target_unit == 'count' 가드로 이미 이관된 DB 에서는 재실행돼도 변화가 없다.

Revision ID: 0010_exercise_target_minutes
Revises: 0009_mission_log_device_uq
Create Date: 2026-07-23 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0010_exercise_target_minutes"
down_revision: str | None = "0009_mission_log_device_uq"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_TITLE = "영상 따라 운동하기"
_NEW_DESC = "몸풀기·앉아서·서서·마무리 영상을 따라 해요. 하루 10분을 채우면 완료예요. (여러 번 나눠 해도 합산돼요)"

# 반드시 op.execute() 를 쓴다 — op.get_bind().execute() 는 마이그레이션 트랜잭션 밖에서 돌아 커밋되지 않는다.
_mission_templates = sa.table(
    "mission_templates",
    sa.column("title", sa.String),
    sa.column("mission_type", sa.String),
    sa.column("default_target_value", sa.Integer),
    sa.column("target_unit", sa.String),
    sa.column("description", sa.Text),
)


def upgrade() -> None:
    # title 기준으로 아직 count 인 운동 템플릿만 분으로 올린다. 실 데이터가 없는 새 DB 는 no-op.
    op.execute(
        _mission_templates.update()
        .where(
            _mission_templates.c.title == _TITLE,
            _mission_templates.c.mission_type == "exercise",
            _mission_templates.c.target_unit == "count",
        )
        .values(default_target_value=10, target_unit="minutes", description=_NEW_DESC)
    )


def downgrade() -> None:
    # 값만 3/count 로 복원한다(설명은 옛 문구를 보존할 근거가 없어 두지 않는다).
    #   목표값·단위 교체일 뿐 로그·집계에 영향이 없어 파괴적이지 않다.
    op.execute(
        _mission_templates.update()
        .where(
            _mission_templates.c.title == _TITLE,
            _mission_templates.c.mission_type == "exercise",
            _mission_templates.c.target_unit == "minutes",
        )
        .values(default_target_value=3, target_unit="count")
    )
