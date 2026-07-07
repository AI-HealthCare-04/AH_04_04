# =====================================================================================
# DTO(Data Transfer Object) 파일입니다.
# "API가 주고받는 데이터의 모양(형식)"을 정의합니다.
#  - Request(요청) DTO  : 클라이언트(안드로이드 앱)가 서버로 보내는 JSON의 형태
#  - Response(응답) DTO : 서버가 클라이언트로 돌려주는 JSON의 형태
# Pydantic이 이 정의를 보고 자동으로 검증(validation)해줍니다.
# =====================================================================================

# BaseModel : Pydantic의 가장 기본이 되는 클래스. 요청 검증용 DTO는 이걸 상속합니다.
# Field     : 각 필드에 추가 규칙(예: 최소 개수)을 걸 때 사용합니다.
from pydantic import BaseModel, Field

# BaseSerializerModel : 우리 프로젝트 공통 응답 베이스입니다.
# 내부에 from_attributes=True 설정이 있어서, DB 모델 객체(ORM)를 곧바로 응답 DTO로 변환할 수 있습니다.
from app.dtos.base import BaseSerializerModel


# -------------------------------------------------------------------------------------
# [응답] 약관 한 건의 정보. GET /terms 응답의 "terms" 배열에 들어가는 요소입니다.
# 명세서 응답 스펙: terms_type, version, is_required, title, url
# -------------------------------------------------------------------------------------
class TermResponse(BaseSerializerModel):
    terms_type: str  # 약관 종류 (예: "service")
    version: str  # 약관 버전 (예: "1.0")
    is_required: bool  # 필수 동의 여부 (true=필수)
    title: str  # 약관 제목 (예: "서비스 이용약관")
    url: str  # 약관 전문 링크(URL)


# -------------------------------------------------------------------------------------
# [응답] GET /terms 전체 응답. 약관 여러 건을 "terms" 배열로 감쌉니다.
# 최종 JSON 예: { "terms": [ {...}, {...} ] }
# -------------------------------------------------------------------------------------
class TermsListResponse(BaseModel):
    terms: list[TermResponse]  # TermResponse 여러 개를 담는 리스트


# -------------------------------------------------------------------------------------
# [요청] 약관 동의 제출 시, 동의 항목 하나의 형태입니다.
# 명세서 요청 스펙: 약관을 terms_type + version 으로 식별하고, agreed 로 동의 여부를 보냅니다.
# -------------------------------------------------------------------------------------
class TermAgreementItem(BaseModel):
    terms_type: str  # 어떤 종류의 약관인지 (예: "service")
    version: str  # 그 약관의 버전 (예: "1.0")
    agreed: bool  # 동의했는지 여부 (true=동의)


# -------------------------------------------------------------------------------------
# [요청] POST /users/me/agreements 전체 요청 바디입니다.
# 최종 JSON 예: { "agreements": [ {terms_type, version, agreed}, ... ] }
# -------------------------------------------------------------------------------------
class TermsAgreementRequest(BaseModel):
    # Field(min_length=1) → 최소 1개 이상 보내야 함. 빈 배열이면 Pydantic이 422 에러로 막아줍니다.
    agreements: list[TermAgreementItem] = Field(min_length=1)


# -------------------------------------------------------------------------------------
# [응답] POST /users/me/agreements 응답입니다.
# 명세서 응답 스펙: onboarding_status 하나만 반환.
# 예: { "onboarding_status": "terms_agreed" }
# -------------------------------------------------------------------------------------
class TermsAgreementResponse(BaseModel):
    onboarding_status: str  # 동의 처리 후 사용자의 온보딩 진행 상태
