package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.StsAssessmentItem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// 5STS 추이 표시(#353)의 순수 로직 — JVM 단위테스트 대상.
//   방향 주의: 5STS 는 '낮을수록 좋음'이라 개선=감소. 점수 추이(높을수록 좋음)와 반대다.
//   비의료(#57): '빨라졌/느려졌'(사실)까지만 말하고 저하·위험 같은 판정 표현은 쓰지 않는다.

/**
 * 권장 재측정 주기 안내(#353). **3개월** 근거 — 자세한 정리는 `docs/sts_remeasure_interval.md`:
 *  - 낙상 예방 운동은 sit-to-stand 를 포함해 **최소 12주** 시행 권고(World Falls Guidelines WG4, 등급 1A)
 *    → 운동 효과를 확인하는 시점으로 3개월이 가이드라인 권고 기간과 맞는다.
 *  - 5STS 최소감지변화(MDC)가 약 3초(지역사회 노인 3.50초)라, 한 달 재측정은 대부분 측정 오차 범위 안이라
 *    '비슷해요'만 반복 표시된다 — 성실히 운동한 사용자의 동기를 오히려 꺾는다.
 *  ⚠️ 공식 '재측정 권고 주기'가 규정된 가이드라인은 없다(AWGS 는 진단 컷오프만 정함). 판정이 아니라
 *    '변화를 확인하는 시점' 안내로만 쓴다(비의료 #57).
 */
internal const val STS_REMEASURE_INTERVAL_NOTICE = "3개월에 한 번 정도 다시 재보시면 변화를 확인할 수 있어요."

/** 초 표시: 소수 1자리("10.8초"). */
internal fun stsSecondsLabel(seconds: Double): String =
    String.format(Locale.KOREA, "%.1f초", seconds)

/**
 * 직전 대비 변화 문구. 측정이 2회 미만이면 null(미표시).
 * 개선=감소이므로 문구 방향을 반전한다(#353 요청 3). 0.1초 미만 차이는 '비슷해요'.
 */
internal fun stsChangeLine(previousSec: Double?, latestSec: Double?): String? {
    if (previousSec == null || latestSec == null) return null
    val diff = previousSec - latestSec // 양수 = 빨라짐(개선)
    val rounded = (abs(diff) * 10.0).roundToInt() / 10.0
    return when {
        rounded < 0.1 -> "지난번과 비슷해요"
        diff > 0 -> "지난번보다 ${stsSecondsLabel(rounded).removeSuffix("초")}초 빨라졌어요"
        else -> "지난번보다 ${stsSecondsLabel(rounded).removeSuffix("초")}초 느려졌어요"
    }
}

/** 최근 측정 행(최신순 최대 [max]): "07.31 · 10.8초". 날짜는 점수 추이와 같은 MM.DD 포맷. */
internal fun stsRecentLines(items: List<StsAssessmentItem>, max: Int = 5): List<String> =
    items.take(max).map { "${trendLabel(it.createdAt)} · ${stsSecondsLabel(it.chairStand5TimeSec)}" }
