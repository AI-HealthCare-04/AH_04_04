"""mission_logs: 기기 생성 시각을 자연 키로 한 재전송 방지 유니크

오프라인 outbox 가 응답을 못 받고 같은 수행을 다시 보내면 지금은 행이 하나 더 생긴다.
걷기 성공 판정이 '당일 로그 합산'이라(services/mission.py), 재전송 한 번이 목표 달성으로
이어지고 포인트까지 지급된다. 운동·게임도 반복 수행을 허용하므로 서버가 재전송과 정당한
반복을 구분할 방법이 없었다(#91, #105).

키를 새로 만들지 않고 이미 있던 created_on_device_at 을 자연 키로 쓴다.
users(provider, social_id) / terms_agreements(user_id, terms_type) 와 같은 패턴이다.
  같은 수행의 재전송 → 기기 시각이 같다 → 유니크가 막는다
  컨디션 따라 또 수행   → 기기 시각이 다르다 → 정상 삽입
  created_on_device_at 이 NULL 인 행은 MySQL 유니크에서 제외되므로,
  이 값을 보내지 않는 기존 앱·기존 데이터는 영향이 없다.

⚠️ 컬럼 타입도 DATETIME → DATETIME(6) 으로 올린다.
  MySQL DATETIME 은 기본이 초 단위라, 그대로 두면 같은 초에 시작한 서로 다른 두 수행이
  중복으로 오인돼 거부된다. 정밀도를 올리는 방향이라 기존 값은 손실 없이 보존된다.

⚠️ 유니크 생성 전에 기존 중복을 검사한다.
  NULL 이 아닌 (user_id, mission_template_id, created_on_device_at) 조합이 이미 중복이면
  DDL 이 실패하는데, 그 실패 메시지로는 원인을 알기 어렵다. 여기서 먼저 세어 보고
  건수를 담은 메시지로 중단시킨다(리뷰 #103·#118 과 같은 안전장치 패턴).

Revision ID: 0009_mission_log_device_uq
Revises: 0008_drop_walk_6m_columns
Create Date: 2026-07-22 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import mysql

revision: str = "0009_mission_log_device_uq"
down_revision: str | None = "0008_drop_walk_6m_columns"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_UQ_NAME = "uq_mission_logs_user_template_device_time"

# 이미 중복이 있으면 유니크를 만들 수 없다. 원인을 알 수 있는 메시지로 먼저 멈춘다.
_DUPLICATE_CHECK = sa.text(
    """
    SELECT COUNT(*) FROM (
        SELECT 1
        FROM mission_logs
        WHERE created_on_device_at IS NOT NULL
        GROUP BY user_id, mission_template_id, created_on_device_at
        HAVING COUNT(*) > 1
    ) AS dup
    """
)


def upgrade() -> None:
    bind = op.get_bind()
    duplicated = bind.execute(_DUPLICATE_CHECK).scalar_one()
    if duplicated:
        raise RuntimeError(
            f"mission_logs 에 (user_id, mission_template_id, created_on_device_at) 중복이 "
            f"{duplicated}건 있습니다. 유니크를 만들기 전에 어느 행을 남길지 정해 정리해야 합니다. "
            f"(재전송으로 생긴 중복이라면 mission_log_id 가 큰 쪽을 지우면 됩니다)"
        )

    # 초 단위 → 마이크로초. 정밀도를 올리는 방향이라 기존 값 손실이 없다.
    op.alter_column(
        "mission_logs",
        "created_on_device_at",
        existing_type=mysql.DATETIME(),
        type_=mysql.DATETIME(fsp=6),
        existing_nullable=True,
    )
    op.create_unique_constraint(
        _UQ_NAME,
        "mission_logs",
        ["user_id", "mission_template_id", "created_on_device_at"],
    )


def downgrade() -> None:
    op.drop_constraint(_UQ_NAME, "mission_logs", type_="unique")
    # 마이크로초 → 초. 소수부가 잘리므로 되돌릴 때만 수행한다.
    op.alter_column(
        "mission_logs",
        "created_on_device_at",
        existing_type=mysql.DATETIME(fsp=6),
        type_=mysql.DATETIME(),
        existing_nullable=True,
    )
