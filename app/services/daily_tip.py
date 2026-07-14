# =====================================================================================
# Service(서비스) 파일입니다.
# 실제 '비즈니스 규칙'을 담당합니다. 데일리 팁은:
#   - "오늘의 팁"을 KST 날짜 기준으로 결정(순환 선택)합니다.
#
# 데일리 팁 '콘텐츠'는 정적 카탈로그(app/core/daily_tips_catalog.py)에서 오고,
# 사용자별 상태가 없으므로 DB(Repository) 접근이 없습니다. → 세션을 받지 않는 순수 로직입니다.
# =====================================================================================

# 정적 데일리 팁 카탈로그: 순환 노출할 팁 목록.
from app.core.daily_tips_catalog import DAILY_TIPS_CATALOG, DailyTip

# today_kst(): 오늘의 팁이 어느 날짜(KST) 기준인지 응답에 함께 담기 위해 사용합니다.
from app.core.utils.clock import today_kst
from app.dtos.daily_tip import DailyTipResponse


class DailyTipService:
    # [GET /daily-tips] "오늘의 팁"을 돌려줍니다.
    #   - 선택 규칙: KST 오늘 날짜의 서수(ordinal, 연속 정수)를 팁 개수로 나눈 나머지 인덱스.
    #     → 같은 날엔 항상 같은 팁(결정적), 매일 다음 팁으로 순환, 끝에 닿으면 처음으로 되돌아옵니다.
    #     → datetime.now()/random 을 쓰지 않아 재현 가능하고, 서버 재시작에도 값이 흔들리지 않습니다.
    def get_today_tip(self) -> DailyTipResponse:
        today = today_kst()
        tip = self._tip_for_date_ordinal(today.toordinal())
        return DailyTipResponse(
            tip_date=today,
            tip_id=tip.tip_id,
            category=tip.category,
            title=tip.title,
            body=tip.body,
        )

    # 날짜 서수 → 카탈로그 항목. 카탈로그가 비어 있으면 배선 오류이므로 명확히 실패시킵니다.
    @staticmethod
    def _tip_for_date_ordinal(day_ordinal: int) -> DailyTip:
        if not DAILY_TIPS_CATALOG:
            raise RuntimeError("데일리 팁 카탈로그가 비어 있습니다(app/core/daily_tips_catalog.py).")
        index = day_ordinal % len(DAILY_TIPS_CATALOG)
        return DAILY_TIPS_CATALOG[index]
