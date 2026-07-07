from sqlalchemy import BigInteger, Boolean, Enum, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin
from app.models.enums import FontSize, SoundSize, enum_values


class PersonalizedSetting(Base, TimestampMixin):
    __tablename__ = "personalized_settings"

    setting_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, unique=True)
    notification_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    font_size: Mapped[FontSize] = mapped_column(
        Enum(FontSize, values_callable=enum_values, name="font_size_enum"),
        nullable=False,
        default=FontSize.DEFAULT,
    )
    sound_size: Mapped[SoundSize] = mapped_column(
        Enum(SoundSize, values_callable=enum_values, name="sound_size_enum"),
        nullable=False,
        default=SoundSize.DEFAULT,
    )
    pet_type: Mapped[str] = mapped_column(String(50), nullable=False, default="default")
    music_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
