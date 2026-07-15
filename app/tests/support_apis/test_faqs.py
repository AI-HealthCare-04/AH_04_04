# =====================================================================================
# 고객센터 FAQ(GET /support/faqs) 계약 테스트.
# - 인증 요구(401)는 client 픽스처로 확인.
# - 카탈로그 형태·비노출 톤은 DB 없이 순수 로직으로 검증.
# =====================================================================================

from httpx import AsyncClient
from starlette import status

from app.core.faq_catalog import FAQ_CATALOG


# GET /support/faqs 는 로그인이 필요합니다. 토큰 없이 부르면 401이 나야 합니다.
async def test_get_faqs_requires_auth(client: AsyncClient) -> None:
    response = await client.get("/api/v1/support/faqs")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert "error_detail" in response.json()  # 공통 예외 핸들러 규격


# 카탈로그가 질문·답변을 모두 갖추고 faq_id가 유일한지 검증합니다. (DB 불필요)
def test_faq_catalog_shape() -> None:
    assert len(FAQ_CATALOG) > 0
    for spec in FAQ_CATALOG:
        assert spec.question and spec.answer
        assert isinstance(spec.faq_id, int)
    faq_ids = [spec.faq_id for spec in FAQ_CATALOG]
    assert len(faq_ids) == len(set(faq_ids)), "faq_id는 유일해야 합니다."


# 비노출(#57): FAQ 답변에 위험도 점수·등급이 새면 안 된다.
def test_faq_answers_are_non_disclosure_safe() -> None:
    banned = ("점수", "등급", "risk", "score")
    for spec in FAQ_CATALOG:
        text = f"{spec.question} {spec.answer}".lower()
        for word in banned:
            assert word.lower() not in text, f"FAQ {spec.faq_id}에 비노출 위반 표현: {word}"
