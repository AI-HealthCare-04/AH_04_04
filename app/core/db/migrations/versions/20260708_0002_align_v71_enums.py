"""align v7.1 enum values

Revision ID: 0002_align_v71_enums
Revises: 0001_init_core_tables
Create Date: 2026-07-08 00:00:00
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0002_align_v71_enums"
down_revision: str | None = "0001_init_core_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute(
        "ALTER TABLE users "
        "MODIFY COLUMN provider ENUM('google','kakao','guest') NOT NULL"
    )

    op.execute(
        "ALTER TABLE sensor_sessions "
        "MODIFY COLUMN recognition_status "
        "ENUM('recognized','success','low_confidence','failed','manual_override') NOT NULL"
    )
    op.execute(
        "UPDATE sensor_sessions "
        "SET recognition_status = 'success' "
        "WHERE recognition_status = 'recognized'"
    )
    op.execute(
        "ALTER TABLE sensor_sessions "
        "MODIFY COLUMN recognition_status "
        "ENUM('success','low_confidence','failed','manual_override') NOT NULL"
    )

    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN font_size ENUM('small','default','medium','large') NOT NULL"
    )
    op.execute(
        "UPDATE personalized_settings "
        "SET font_size = 'medium' "
        "WHERE font_size = 'default'"
    )
    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN font_size ENUM('small','medium','large') NOT NULL"
    )

    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN sound_size ENUM('small','default','medium','large') NOT NULL"
    )
    op.execute(
        "UPDATE personalized_settings "
        "SET sound_size = 'medium' "
        "WHERE sound_size = 'default'"
    )
    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN sound_size ENUM('small','medium','large') NOT NULL"
    )


def downgrade() -> None:
    # This will fail if users.provider still contains kakao or guest rows.
    op.execute(
        "ALTER TABLE users "
        "MODIFY COLUMN provider ENUM('google') NOT NULL"
    )

    op.execute(
        "ALTER TABLE sensor_sessions "
        "MODIFY COLUMN recognition_status "
        "ENUM('recognized','success','low_confidence','failed','manual_override') NOT NULL"
    )
    op.execute(
        "UPDATE sensor_sessions "
        "SET recognition_status = 'recognized' "
        "WHERE recognition_status = 'success'"
    )
    # This will fail if recognition_status still contains manual_override rows.
    op.execute(
        "ALTER TABLE sensor_sessions "
        "MODIFY COLUMN recognition_status "
        "ENUM('recognized','low_confidence','failed') NOT NULL"
    )

    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN font_size ENUM('small','default','medium','large') NOT NULL"
    )
    op.execute(
        "UPDATE personalized_settings "
        "SET font_size = 'default' "
        "WHERE font_size = 'medium'"
    )
    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN font_size ENUM('small','default','large') NOT NULL"
    )

    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN sound_size ENUM('small','default','medium','large') NOT NULL"
    )
    op.execute(
        "UPDATE personalized_settings "
        "SET sound_size = 'default' "
        "WHERE sound_size = 'medium'"
    )
    op.execute(
        "ALTER TABLE personalized_settings "
        "MODIFY COLUMN sound_size ENUM('small','default','large') NOT NULL"
    )
