# =====================================================================================
# 데일리 팁(Daily Tips) API 테스트 파일입니다.
# - 인증 요구(401)는 client 픽스처로 실제 요청을 보내 확인합니다.
# - 카탈로그 형태/톤, "오늘의 팁" 선택 규칙(결정적·순환)은 DB 없이 순수 로직으로 검증합니다.
# =====================================================================================

from datetime import date

from httpx import AsyncClient
from starlette import status

from app.core.daily_tips_catalog import DAILY_TIPS_CATALOG
from app.services.daily_tip import DailyTipService


# GET /daily-tips 는 로그인이 필요합니다. 토큰 없이 부르면 401이 나야 합니다.
async def test_get_daily_tip_requires_auth(client: AsyncClient) -> None:
    response = await client.get("/api/v1/daily-tips")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    # 공통 예외 핸들러가 명세서 규격 {"error_detail": ...} 로 응답을 통일했는지 확인합니다.
    assert "error_detail" in response.json()


# 정적 카탈로그가 화면에 뿌릴 필드를 모두 갖추고, tip_id가 유일한지 검증합니다. (DB 불필요)
def test_daily_tips_catalog_shape() -> None:
    assert len(DAILY_TIPS_CATALOG) > 0
    for tip in DAILY_TIPS_CATALOG:
        assert tip.title and tip.body and tip.category
        assert isinstance(tip.tip_id, int)
    tip_ids = [tip.tip_id for tip in DAILY_TIPS_CATALOG]
    assert len(tip_ids) == len(set(tip_ids)), "tip_id는 카탈로그 내에서 유일해야 합니다."


# 비노출·비의료 톤: 팁 문구에 위험 등급/점수/진단성 표현이 새지 않아야 합니다(#57 톤 원칙).
def test_daily_tips_copy_is_non_medical() -> None:
    banned = ("위험", "점수", "진단", "risk", "score")
    for tip in DAILY_TIPS_CATALOG:
        text = f"{tip.title} {tip.body}".lower()
        for word in banned:
            assert word.lower() not in text, f"팁 {tip.tip_id}에 비의료 톤을 벗어난 표현이 있습니다: {word}"


# "오늘의 팁" 선택은 결정적이어야 합니다 — 같은 날짜(서수)는 항상 같은 팁을 돌려줍니다.
def test_today_tip_selection_is_deterministic() -> None:
    ordinal = date(2026, 7, 14).toordinal()
    first = DailyTipService._tip_for_date_ordinal(ordinal)
    second = DailyTipService._tip_for_date_ordinal(ordinal)
    assert first is second


# 매일 다음 팁으로 순환하고, 목록 끝에 닿으면 처음으로 되돌아와야 합니다.
def test_tip_rotates_daily_and_wraps_around() -> None:
    base = date(2026, 7, 14).toordinal()
    picked = [DailyTipService._tip_for_date_ordinal(base + offset) for offset in range(len(DAILY_TIPS_CATALOG))]
    # 한 주기(카탈로그 길이)를 돌면 모든 팁이 정확히 한 번씩 노출됩니다(중복·누락 없음).
    assert {tip.tip_id for tip in picked} == {tip.tip_id for tip in DAILY_TIPS_CATALOG}
    # 한 주기 뒤(오프셋 = 길이)에는 시작 팁으로 되돌아옵니다(순환).
    assert DailyTipService._tip_for_date_ordinal(base + len(DAILY_TIPS_CATALOG)) is picked[0]


# get_today_tip()은 오늘(KST) 날짜와 함께 화면에 뿌릴 필드를 모두 채워 반환합니다.
def test_get_today_tip_returns_display_ready_response() -> None:
    response = DailyTipService().get_today_tip()
    assert isinstance(response.tip_date, date)
    assert response.tip_id in {tip.tip_id for tip in DAILY_TIPS_CATALOG}
    assert response.title and response.body and response.category
