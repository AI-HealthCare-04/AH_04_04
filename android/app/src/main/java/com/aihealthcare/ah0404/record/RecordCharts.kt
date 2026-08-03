package com.aihealthcare.ah0404.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.network.ChallengeTotalsResponse
import com.aihealthcare.ah0404.network.MissionLogItem
import com.aihealthcare.ah0404.network.WalkingDayPoint
import com.aihealthcare.ah0404.ui.theme.ChartBarGreen
import com.aihealthcare.ah0404.ui.theme.Dimens
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

// =====================================================================================
// 나의 기록 차트(#기록탭 §5) — 순수 집계 헬퍼 + Canvas 차트.
//   확률(%)·관리 필요도 표기는 이 화면에 없다(§5.5). 모든 축은 챌린지 수행 통계다.
// =====================================================================================

private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/** ISO 문자열 앞 10자("YYYY-MM-DD")를 날짜 키로 쓴다. */
internal fun dateKey(iso: String): String = if (iso.length >= 10) iso.substring(0, 10) else iso

/** 오늘(KST) 기준 최근 [days]일의 날짜 키를 오래된→오늘 순으로 반환. */
internal fun recentDateKeys(days: Int, todayMillis: Long): List<String> {
    val cal = GregorianCalendar(KST).apply { timeInMillis = todayMillis }
    val todayStart = GregorianCalendar(KST).apply {
        clear(); set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
    return (days - 1 downTo 0).map { offset ->
        val d = GregorianCalendar(KST).apply { timeInMillis = todayStart - offset * MILLIS_PER_DAY }
        String.format(Locale.US, "%04d-%02d-%02d", d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH))
    }
}

/**
 * 일별 '완료' 미션 개수(#기록탭 §5.1). 최근 [days]일 각 날짜의 **counted_for_daily** 로그 수.
 *  기준은 앱 전체의 '미션 완료' 정의(홈 완료 개수·포인트 적립·#274)와 통일한다 — success 로 세면
 *  목표를 넘긴 뒤의 추가 세션(예: 20분 목표에 21분째 걷기, success·미적립)까지 중복돼 홈과 어긋난다.
 *  데이터 없는 날은 0.
 */
internal fun dailyCompletionCounts(logs: List<MissionLogItem>, dateKeys: List<String>): List<Int> {
    val byDay = logs.filter { it.countedForDaily && it.completedAt != null }
        .groupingBy { dateKey(it.completedAt!!) }
        .eachCount()
    return dateKeys.map { byDay[it] ?: 0 }
}

/** 해당 날짜에 완료(집계·적립)된 미션 목록(달력 팝업용). counted_for_daily 기준, 완료 시각 오름차순. */
internal fun completedMissionsOn(logs: List<MissionLogItem>, dayKey: String): List<MissionLogItem> =
    logs.filter { it.countedForDaily && it.completedAt != null && dateKey(it.completedAt!!) == dayKey }
        .sortedBy { it.completedAt }

/**
 * '기록함(목표 미달성)' 흔적이 있는 날짜 집합(#343 문제 2). 단백질 '안 먹었어요'(0종) 저장은
 * counted_for_daily=false 라 완료 통계 어디에도 안 나타나 성실한 기록이 화면상 무(無)로 보였다.
 * 완료 통계(포인트·성공 판정)의 의미는 그대로 두고, 달력에 옅은 흔적만 따로 표시하기 위한 값이다.
 * (meal 은 목표 1종이라 counted=false 완료 로그 == '안 먹었어요' 기록)
 */
internal fun mealRecordedOnlyDates(logs: List<MissionLogItem>): Set<String> =
    logs.filter { it.missionType == "meal" && !it.countedForDaily && it.completedAt != null }
        .map { dateKey(it.completedAt!!) }
        .toSet()

/** 그날 '안 먹었어요'로 기록한 단백질 항목(달력 팝업용, #343 문제 2). 기록 시각 오름차순. */
internal fun mealRecordedOnlyOn(logs: List<MissionLogItem>, dayKey: String): List<MissionLogItem> =
    logs.filter {
        it.missionType == "meal" && !it.countedForDaily && it.completedAt != null && dateKey(it.completedAt!!) == dayKey
    }.sortedBy { it.completedAt }

/** ISO(KST) 시각 → "오전/오후 h:mm". 파싱 실패 시 빈 문자열. */
internal fun koreanTime(iso: String?): String {
    if (iso == null || iso.length < 16) return ""
    val hour = iso.substring(11, 13).toIntOrNull() ?: return ""
    val minute = iso.substring(14, 16).toIntOrNull() ?: return ""
    val ampm = if (hour < 12) "오전" else "오후"
    val h12 = when (val h = hour % 12) { 0 -> 12; else -> h }
    return String.format(Locale.KOREA, "%s %d:%02d", ampm, h12, minute)
}

// ── §5.1 일별 완료 선그래프 ───────────────────────────────────────────────────
@Composable
internal fun CompletionLineChart(dateKeys: List<String>, counts: List<Int>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    val maxCount = max(counts.maxOrNull() ?: 0, 3) // y=0~3+ 정수 눈금
    val todayIndex = counts.lastIndex
    val desc = "최근 ${counts.size}일 일별 완료 미션 수. 오늘 ${counts.lastOrNull() ?: 0}개."
    Canvas(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = desc },
    ) {
        val left = 8.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx(); val bottom = size.height - 8.dp.toPx()
        val w = right - left; val h = bottom - top
        // 정수 가로 눈금(0..maxCount)
        for (v in 0..maxCount) {
            val y = bottom - h * (v.toFloat() / maxCount)
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        // 목표선(1개) 얇은 점선
        val goalY = bottom - h * (1f / maxCount)
        drawLine(
            goalColor, Offset(left, goalY), Offset(right, goalY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        if (counts.size < 2) return@Canvas
        fun pt(i: Int): Offset {
            val x = left + w * (i.toFloat() / (counts.size - 1))
            val y = bottom - h * (counts[i].toFloat() / maxCount)
            return Offset(x, y)
        }
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until counts.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(path, primary, style = Stroke(2.dp.toPx()))
        counts.indices.forEach { i ->
            val isToday = i == todayIndex
            drawCircle(primary, radius = if (isToday) 6.dp.toPx() else 4.dp.toPx(), center = pt(i))
            if (isToday) drawCircle(goalColor, radius = 3.dp.toPx(), center = pt(i))
        }
    }
}

// ── §5.3 걷기 막대그래프(시간/걸음 탭 전환) ────────────────────────────────────
enum class WalkingMetric { MINUTES, STEPS }

/** 막대 플롯 높이(§7 M3) — 값·요일 라벨 자리를 포함한 카드 안 높이. */
private val WalkChartHeight = 130.dp

/**
 * 최근 7일 걷기 막대(§7 M3). 시안 대비 바뀐 점: 축선·격자 제거(하단 구분선 하나만), **모든 막대에 값 라벨**,
 * 막대 아래 요일 라벨, 막대 상단 라운드.
 *
 *  값 라벨을 최대값·오늘에만 붙이던 기존 방식은 시니어에게 "왜 어떤 날만 숫자가 있지?"로 읽혔다.
 *  일곱 개뿐이라 전부 적어도 빽빽하지 않다.
 *
 *  **기록이 없는 날(value = null)과 0 인 날을 구분**한다 — 없는 날은 막대를 그리지 않고 라벨도 비운다.
 *  값이 전부 없거나 0 이면 이 차트를 그리지 않고 호출부가 안내 카드로 교체한다(§6 M3).
 */
@Composable
internal fun WalkingBarChart(
    points: List<WalkPoint>,
    unitLabel: String,
    modifier: Modifier = Modifier,
) {
    val bar = ChartBarGreen
    val divider = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    val maxV = max(points.mapNotNull { it.value }.maxOrNull()?.toDouble() ?: 0.0, 1.0)
    val description = walkChartDescription(points, unitLabel)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(WalkChartHeight)
            .semantics { contentDescription = description },
    ) {
        val topPad = 16.dp.toPx()      // 값 라벨 자리
        val bottomPad = 18.dp.toPx()   // 요일 라벨 자리
        val baseline = size.height - bottomPad
        val plotTop = topPad
        val h = baseline - plotTop
        drawLine(divider, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
        if (points.isEmpty()) return@Canvas
        val slot = size.width / points.size
        val barW = 14.dp.toPx()
        val radius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
        points.forEachIndexed { i, p ->
            val centerX = slot * i + slot / 2f
            drawChartText(p.dayLabel, centerX, size.height - 4.dp.toPx(), labelColor, 12.sp.toPx())
            val v = p.value ?: return@forEachIndexed
            if (v <= 0) return@forEachIndexed
            // 최소 4dp — 0 이 아닌 값이 안 보이는 일이 없게(§7 M3).
            val barH = max((h * (v / maxV)).toFloat().toDouble(), 4.dp.toPx().toDouble()).toFloat()
            drawRoundRect(
                bar,
                topLeft = Offset(centerX - barW / 2f, baseline - barH),
                size = Size(barW, barH),
                cornerRadius = radius,
            )
            drawChartText("$v", centerX, baseline - barH - 4.dp.toPx(), valueColor, 12.sp.toPx(), bold = true)
        }
    }
}

/** 접근성 문구(§10) — 그래프를 못 보는 사용자도 요일별 값을 그대로 듣게 한다. */
internal fun walkChartDescription(points: List<WalkPoint>, unitLabel: String): String {
    val body = points.joinToString(", ") { p ->
        if (p.value == null) "${p.dayLabel}요일 기록 없음" else "${p.dayLabel}요일 ${p.value}$unitLabel"
    }
    return "최근 ${points.size}일 걷기, $body"
}

// ── §5.4 챌린지 비율 도넛 ─────────────────────────────────────────────────────
/** 유형별 고정 색(§5.4 — 필터·기간 변경에도 불변). */
internal data class DonutSlice(val label: String, val count: Int, val color: Color)

internal val CHALLENGE_TYPE_LABELS = mapOf(
    "walking" to "걷기",
    "exercise" to "영상 운동",
    "meal" to "단백질 식사",
    "game" to "게임",
)

@Composable
internal fun challengeSlices(totals: ChallengeTotalsResponse): List<DonutSlice> {
    // 고정 색 배정(유형→색). 0회 유형은 회색으로 남긴다.
    val colors = mapOf(
        "walking" to Color(0xFF2E7D32),
        "exercise" to Color(0xFF1565C0),
        "meal" to Color(0xFFEF6C00),
        "game" to Color(0xFF6A1B9A),
    )
    val gray = MaterialTheme.colorScheme.outlineVariant
    val order = listOf("walking", "exercise", "meal", "game")
    val byType = totals.byType.associate { it.missionType to it.count }
    return order.map { t ->
        val c = byType[t] ?: 0
        DonutSlice(
            label = CHALLENGE_TYPE_LABELS[t] ?: t,
            count = c,
            color = if (c > 0) colors[t] ?: gray else gray,
        )
    }
}

@Composable
internal fun ChallengeDonut(totals: ChallengeTotalsResponse, modifier: Modifier = Modifier) {
    val slices = challengeSlices(totals)
    val total = totals.total
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(160.dp).semantics { contentDescription = "챌린지 유형별 누적 완료 비율. 총 $total 회." }) {
                val stroke = 26.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                if (total <= 0) {
                    drawArc(trackColor, 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                } else {
                    var startAngle = -90f
                    slices.forEach { s ->
                        if (s.count > 0) {
                            val sweep = 360f * (s.count.toFloat() / total)
                            drawArc(s.color, startAngle, sweep, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                            startAngle += sweep
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("누적", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${total}회", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(Dimens.Space8))
        // 범례: 도넛 밖 목록형 "유형명 N회 (NN%)". 조각 위 텍스트 금지.
        slices.forEach { s ->
            val pct = if (total > 0) (s.count * 100f / total).roundToInt() else 0
            Row(
                Modifier.fillMaxWidth().padding(vertical = Dimens.Space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(Dimens.Space8))
                val isZero = s.count == 0
                Text(
                    text = if (isZero) "${s.label}  아직 0회" else "${s.label}  ${s.count}회 (${pct}%)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isZero) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
