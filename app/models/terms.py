from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, Enum, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.enums import TermsType, enum_values


class TermsAgreement(Base):
    __tablename__ = "terms_agreements"

    agreement_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    terms_type: Mapped[TermsType] = mapped_column(
        Enum(TermsType, values_callable=enum_values, name="terms_type_enum"),
        nullable=False,
    )
    is_required: Mapped[bool] = mapped_column(Boolean, nullable=False)
    agreed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    version: Mapped[str] = mapped_column(String(30), nullable=False)
    agreed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
