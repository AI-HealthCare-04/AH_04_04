package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.MissionLogItem
import com.aihealthcare.ah0404.network.WalkingDayPoint
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * 기록 화면 표시 모델(#387 리디자인, 핸드오프 §5).
 *
 *  **표현 계층 전용이다.** 점수 산식·또래 순위·5STS 계산은 서버/기존 순수 함수의 몫이고,
 *  여기서는 이미 받은 값을 화면이 바로 그릴 수 있는 형태로 정리만 한다(§0-2).
 *  서버 API·DTO 는 손대지 않고, 부족한 형태는 전부 이 파일의 파생 함수로 만든다.
 *
 *  아래 함수들은 Composable 이 아니라 순수 함수라 JVM 단위테스트로 고정할 수 있다.
 */

/** 달력 한 칸. */
internal data class DayMark(
    val day: Int,
    val result: DayResult,
    val isToday: Boolean,
)

internal enum class DayResult { NONE, SUCCESS, GREAT_SUCCESS }

/** 최근 7일 걷기 한 칸. [value] 가 null 이면 그날 기록이 없다(0 과 구분). */
internal data class WalkPoint(val dayLabel: String, val value: Int?)

internal enum class WalkUnit { MINUTES, STEPS }

/** 미션별 완료 현황 한 타일. [done] 은 **일수**이고 [total] 은 항상 7이다(§7 M4). */
internal data class MissionProgress(val type: MissionKind, val done: Int, val total: Int = RECENT_WINDOW_DAYS)

/** 미션 유형 4종. 표시 순서가 곧 선언 순서다(걷기 → 운동 → 식사 → 게임, §7 M4). */
internal enum class MissionKind(val serverType: String, val label: String) {
    WALK("walking", "걷기"),
    EXERCISE("exercise", "운동"),
    MEAL("meal", "식사"),
    GAME("game", "게임"),
}

/** 미션별 완료 현황·최근 7일 걷기가 공유하는 집계 구간(오늘 포함 롤링). */
internal const val RECENT_WINDOW_DAYS = 7

private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

private fun kstCalendar(millis: Long): GregorianCalendar =
    GregorianCalendar(KST).apply { timeInMillis = millis }

private fun dateKeyOf(c: Calendar): String = String.format(
    Locale.US,
    "%04d-%02d-%02d",
    c.get(Calendar.YEAR),
    c.get(Calendar.MONTH) + 1,
    c.get(Calendar.DAY_OF_MONTH),
)

/** "YYYY-MM-DD" → 한 글자 요일("월".."일"). 파싱 실패 시 빈 문자열. */
internal fun weekdayLabel(dateKey: String): String {
    val parts = dateKey.split("-")
    if (parts.size < 3) return ""
    val y = parts[0].toIntOrNull() ?: return ""
    val m = parts[1].toIntOrNull() ?: return ""
    val d = parts[2].toIntOrNull() ?: return ""
    val c = GregorianCalendar(KST).apply { clear(); set(y, m - 1, d) }
    return when (c.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "월"
        Calendar.TUESDAY -> "화"
        Calendar.WEDNESDAY -> "수"
        Calendar.THURSDAY -> "목"
        Calendar.FRIDAY -> "금"
        Calendar.SATURDAY -> "토"
        else -> "일"
    }
}

/** 오늘 포함 최근 [days] 일의 날짜 키(과거 → 오늘 순). */
internal fun recentWindowKeys(todayMillis: Long, days: Int = RECENT_WINDOW_DAYS): List<String> =
    (days - 1 downTo 0).map { back ->
        dateKeyOf(kstCalendar(todayMillis - back.toLong() * 86_400_000L))
    }

/**
 * 이번 달 요약(§7 M1). 스탬프의 daily_result 로만 센다 — 참여 일수 = 성공 + 대성공.
 * 서버가 활동 있는 날만 내려주므로 none 은 애초에 세지 않는다.
 */
internal data class MonthSummary(val joinedDays: Int, val successCount: Int, val greatSuccessCount: Int)

internal fun monthSummaryOf(stampsByDate: Map<String, String>): MonthSummary {
    var success = 0
    var great = 0
    stampsByDate.values.forEach { result ->
        when (result) {
            "great_success" -> great++
            "success" -> success++
        }
    }
    return MonthSummary(joinedDays = success + great, successCount = success, greatSuccessCount = great)
}

/** 달력 칸 목록(§7 M2). 해당 월의 1일~말일을 모두 만들고, 스탬프가 있는 날만 마크를 붙인다. */
internal fun dayMarksOf(
    year: Int,
    month1: Int,
    stampsByDate: Map<String, String>,
    todayMillis: Long,
): List<DayMark> {
    val first = GregorianCalendar(KST).apply { clear(); set(year, month1 - 1, 1) }
    val lastDay = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val todayKey = dateKeyOf(kstCalendar(todayMillis))
    return (1..lastDay).map { day ->
        val key = String.format(Locale.US, "%04d-%02d-%02d", year, month1, day)
        DayMark(
            day = day,
            result = when (stampsByDate[key]) {
                "great_success" -> DayResult.GREAT_SUCCESS
                "success" -> DayResult.SUCCESS
                else -> DayResult.NONE
            },
            isToday = key == todayKey,
        )
    }
}

/** 그 달 1일의 요일 오프셋(일요일 시작 격자에서 앞에 비울 칸 수). */
internal fun leadingBlankCount(year: Int, month1: Int): Int {
    val first = GregorianCalendar(KST).apply { clear(); set(year, month1 - 1, 1) }
    return first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
}

/**
 * 최근 7일 걷기(§7 M3). **항상 7칸**을 만들고 기록이 없는 날은 null 로 둔다 —
 * 서버가 활동 있는 날만 내려주기 때문에, 빈 날을 0 으로 채우면 "0분 걸었다"는 오해가 된다.
 */
internal fun walk7dOf(days: List<WalkingDayPoint>, unit: WalkUnit, todayMillis: Long): List<WalkPoint> {
    val byDate = days.associateBy { it.date }
    return recentWindowKeys(todayMillis).map { key ->
        val point = byDate[key]
        WalkPoint(
            dayLabel = weekdayLabel(key),
            value = point?.let {
                when (unit) {
                    WalkUnit.MINUTES -> it.minutes.toInt()
                    WalkUnit.STEPS -> it.steps
                }
            },
        )
    }
}

/** 막대그래프를 그릴 값이 하나도 없는가(§6 M3 빈 상태 조건). */
internal fun isWalkEmpty(points: List<WalkPoint>): Boolean =
    points.all { it.value == null || it.value == 0 }

/**
 * 미션별 완료 현황(§7 M4) — **최근 7일 롤링(오늘 포함)에서 유형별로 완료한 '일수'**.
 *
 *  하루에 같은 유형을 두 번 해도 1일로 센다. 서버의 챌린지 집계 API 는 전체 누적이라 쓸 수 없어
 *  이미 받아 둔 미션 로그에서 파생한다(§7 M4 확정, API 무수정).
 *
 *  세는 기준은 `countedForDaily` 다 — 홈 화면의 완료 개수·포인트와 같은 기준이라야 사용자가
 *  두 화면에서 다른 숫자를 보지 않는다(운동·걷기는 목표 초과분도 success 지만 미적립이다).
 */
internal fun missionProgressOf(logs: List<MissionLogItem>, todayMillis: Long): List<MissionProgress> {
    val window = recentWindowKeys(todayMillis).toSet()
    return MissionKind.entries.map { kind ->
        val days = logs.asSequence()
            .filter { it.missionType == kind.serverType && it.countedForDaily }
            .mapNotNull { it.completedAt?.let(::dateKey) }
            .filter { it in window }
            .toSet()
        MissionProgress(type = kind, done = days.size)
    }
}
