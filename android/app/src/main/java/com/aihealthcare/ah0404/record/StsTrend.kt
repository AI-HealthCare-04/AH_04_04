package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.StsAssessmentItem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// 5STS 추이 표시(#353)의 순수 로직 — JVM 단위테스트 대상.
//   방향 주의: 5STS 는 '낮을수록 좋음'이라 개선=감소. 점수 추이(높을수록 좋음)와 반대다.
//   비의료(#57): '빨라졌/느려졌'(사실)까지만 말하고 저하·위험 같은 판정 표현은 쓰지 않는다.

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
