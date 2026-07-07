# =====================================================================================
# 약관(Terms) 도메인의 DB 테이블 정의 파일입니다.
# SQLAlchemy ORM을 사용하며, 여기서 만든 클래스 하나가 DB 테이블 하나에 대응됩니다.
# =====================================================================================

# datetime: '동의한 시각'처럼 날짜/시간을 담는 파이썬 표준 타입입니다.
from datetime import datetime

# StrEnum: "정해진 문자열 값만 허용"하고 싶을 때 쓰는 열거형(Enum)입니다.
# 예) 약관 종류는 아무 문자열이나 되면 안 되고 'service', 'privacy' 등으로 제한합니다.
from enum import StrEnum

# SQLAlchemy가 제공하는 '컬럼 자료형/제약조건' 도구들을 가져옵니다.
#   BigInteger      : 아주 큰 정수 (PK, FK 용도)
#   Boolean         : 참/거짓 (예: 필수 약관 여부)
#   DateTime        : 날짜+시간
#   Enum            : 위에서 만든 StrEnum을 DB 컬럼 제약으로 연결
#   ForeignKey      : 다른 테이블의 컬럼을 참조(외래키)
#   String          : 길이 제한이 있는 짧은 문자열
#   UniqueConstraint: "이 컬럼 조합은 중복 불가"라는 제약
#   func            : DB의 내장 함수 호출(여기서는 현재시각 now())
from sqlalchemy import BigInteger, Boolean, DateTime, Enum, ForeignKey, String, UniqueConstraint, func

# Mapped / mapped_column: 최신 SQLAlchemy(2.0) 방식으로 컬럼을 선언하는 도구입니다.
#   Mapped[타입]        : 이 속성이 어떤 파이썬 타입인지 알려줌 (타입 힌트)
#   mapped_column(...)  : 실제 DB 컬럼의 세부 설정(자료형, null 허용 여부 등)
from sqlalchemy.orm import Mapped, mapped_column

# 우리 프로젝트 공통 베이스입니다.
#   Base           : 모든 모델이 상속해야 하는 최상위 클래스 (SQLAlchemy가 요구)
#   TimestampMixin : created_at / updated_at 컬럼을 자동으로 추가해주는 공통 조각
from app.models.base import Base, TimestampMixin


# -------------------------------------------------------------------------------------
# 약관의 '종류'를 정의하는 열거형입니다.
# 이렇게 해두면 terms_type 컬럼에는 아래 값들만 들어갈 수 있습니다.
# (명세서 예시에 service / sensitive_health 가 등장 → 확정본 나오면 팀과 함께 보정)
# -------------------------------------------------------------------------------------
class TermsType(StrEnum):
    SERVICE = "service"  # 서비스 이용약관
    PRIVACY = "privacy"  # 개인정보 처리방침
    SENSITIVE_HEALTH = "sensitive_health"  # 민감정보(건강정보) 수집·이용 동의
    MARKETING = "marketing"  # 마케팅 정보 수신 동의(선택)


# -------------------------------------------------------------------------------------
# 'terms' 테이블: 앱에 존재하는 약관 문서 목록을 저장합니다.
# 예) 서비스이용약관 v1.0, 개인정보방침 v1.0 ...
# -------------------------------------------------------------------------------------
class Term(Base, TimestampMixin):
    # __tablename__ : 실제 DB에 생성될 테이블 이름입니다.
    __tablename__ = "terms"

    # __table_args__ : 테이블 수준의 제약조건을 넣는 자리입니다.
    # 아래는 "(terms_type, version) 조합은 유일해야 한다"는 뜻입니다.
    # → 같은 종류의 같은 버전 약관이 두 번 등록되는 것을 DB가 막아줍니다.
    __table_args__ = (UniqueConstraint("terms_type", "version", name="uq_terms_type_version"),)

    # 약관의 고유 번호(Primary Key). autoincrement=True 라서 새 행이 생길 때마다 1씩 자동 증가합니다.
    term_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)

    # 약관 종류. 위에서 만든 TermsType 값만 허용합니다. nullable=False → 비워둘 수 없음.
    terms_type: Mapped[TermsType] = mapped_column(Enum(TermsType), nullable=False)

    # 약관 버전 문자열. 예) "1.0". 최대 20자.
    version: Mapped[str] = mapped_column(String(20), nullable=False)

    # 화면에 보여줄 약관 제목. 예) "서비스 이용약관". 최대 200자.
    title: Mapped[str] = mapped_column(String(200), nullable=False)

    # 약관 전문(全文)이 있는 웹 주소(URL). 앱은 이 링크를 열어 내용을 보여줍니다. 최대 500자.
    url: Mapped[str] = mapped_column(String(500), nullable=False)

    # 필수 약관인지 여부. True면 반드시 동의해야 가입이 진행됩니다. 기본값은 False(선택).
    is_required: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # 현재 사용 중인(노출되는) 약관인지 여부. 구버전을 숨길 때 False로 바꿉니다. 기본값 True.
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)


# -------------------------------------------------------------------------------------
# 'terms_agreements' 테이블: "어떤 사용자가 어떤 약관에 동의했는지"를 기록합니다.
# 사용자(users)와 약관(terms)을 연결하는 다리 역할을 합니다.
# -------------------------------------------------------------------------------------
class TermsAgreement(Base, TimestampMixin):
    __tablename__ = "terms_agreements"

    # "한 사용자는 하나의 약관에 대해 한 개의 동의 기록만 갖는다"는 제약입니다.
    # → 같은 (user_id, term_id)로 중복 저장되는 것을 막아, 재동의 시 갱신(update)만 되게 합니다.
    __table_args__ = (UniqueConstraint("user_id", "term_id", name="uq_terms_agreements_user_term"),)

    # 동의 기록의 고유 번호(PK).
    agreement_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)

    # 어떤 사용자의 동의인지 → users 테이블의 user_id를 가리키는 외래키(FK).
    #   ondelete="CASCADE" : 사용자가 삭제되면 그 사용자의 동의 기록도 함께 삭제됩니다.
    #   index=True         : user_id로 자주 조회하므로 검색 속도를 위해 인덱스를 답니다.
    user_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("users.user_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    # 어떤 약관에 대한 동의인지 → terms 테이블의 term_id를 가리키는 외래키(FK).
    #   ondelete="RESTRICT" : 동의 기록이 남아있는 약관은 삭제하지 못하게 막습니다(이력 보호).
    term_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("terms.term_id", ondelete="RESTRICT"),
        nullable=False,
    )

    # 동의했는지 여부(True=동의, False=미동의). 선택 약관은 False로도 저장될 수 있습니다.
    is_agreed: Mapped[bool] = mapped_column(Boolean, nullable=False)

    # 동의한 시각. server_default=func.now() → 값을 안 넣으면 DB가 '지금 시각'을 자동으로 채웁니다.
    agreed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
