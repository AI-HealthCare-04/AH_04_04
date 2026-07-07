# =====================================================================================
# 약관 정적 카탈로그(Static Catalog) 파일입니다.
#
# 팀 결정(2026-07-07): MVP에서는 약관 목록을 DB 테이블(terms)로 관리하지 않고,
# 이 파이썬 모듈에 '현재 유효한 약관 목록'을 상수로 두고 백엔드에서 직접 제공합니다.
#   - GET /terms 응답(title, url, version, is_required)의 출처
#   - POST 동의 제출 시 "제출된 약관이 현재 유효한지 / 최신 버전인지" 검증의 기준
# DB에는 사용자별 '동의 결과'(terms_agreements)만 저장합니다.
#
# 약관을 새로 개정하면(내용/버전 변경) 이 파일의 version/url 등을 수정 후 배포하면 됩니다.
# (추후 약관 관리자 기능이나 DB 버전관리가 필요해지면 그때 terms 테이블을 별도 도입)
# =====================================================================================

# frozen=True dataclass: 한 번 만들면 값을 못 바꾸는(불변) 데이터 묶음. 상수 카탈로그에 적합합니다.
from dataclasses import dataclass

# 약관 종류 열거형. DB enum(terms_type_enum)과 동일한 값을 사용합니다. (핵심 모델과 값 공유)
from app.models.enums import TermsType


@dataclass(frozen=True)
class TermSpec:
    """약관 한 건의 정적 정의. (사용자 동의 기록이 아니라 '약관 그 자체'의 메타데이터)"""

    terms_type: TermsType  # 약관 종류 (DB enum과 동일 값)
    version: str  # 현재 유효한 버전 (예: "1.0")
    title: str  # 화면에 보여줄 제목
    url: str  # 약관 전문 링크(URL)
    is_required: bool  # 필수 동의 여부


# 현재 유효한 약관 목록입니다. GET /terms는 이 순서대로 응답합니다.
# TODO(팀): title/url/version은 임시 placeholder입니다. 실제 약관 문서가 확정되면 교체해 주세요.
TERMS_CATALOG: tuple[TermSpec, ...] = (
    TermSpec(
        terms_type=TermsType.SERVICE,
        version="1.0",
        title="서비스 이용약관",
        url="https://example.com/terms/service-1.0",  # TODO: 실제 링크로 교체
        is_required=True,
    ),
    TermSpec(
        terms_type=TermsType.PRIVACY,
        version="1.0",
        title="개인정보 처리방침",
        url="https://example.com/terms/privacy-1.0",  # TODO: 실제 링크로 교체
        is_required=True,
    ),
    TermSpec(
        terms_type=TermsType.SENSITIVE_HEALTH,
        version="1.0",
        title="민감정보(건강정보) 수집·이용 동의",
        url="https://example.com/terms/sensitive-health-1.0",  # TODO: 실제 링크로 교체
        is_required=True,
    ),
    TermSpec(
        terms_type=TermsType.MARKETING,
        version="1.0",
        title="마케팅 정보 수신 동의",
        url="https://example.com/terms/marketing-1.0",  # TODO: 실제 링크로 교체
        is_required=False,  # 선택 약관
    ),
)

# (terms_type 문자열) → TermSpec 으로 빠르게 찾기 위한 조회용 딕셔너리입니다.
# 예) {"service": TermSpec(...), ...}  — 동의 제출 검증 시 사용합니다.
CATALOG_BY_TYPE: dict[str, TermSpec] = {str(spec.terms_type): spec for spec in TERMS_CATALOG}
