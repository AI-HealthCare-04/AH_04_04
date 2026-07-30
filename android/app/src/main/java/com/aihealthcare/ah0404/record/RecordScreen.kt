package com.aihealthcare.ah0404.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.dashboard.PredictionDashboardScreen
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.components.SegmentOption
import com.aihealthcare.ah0404.ui.theme.Dimens
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `_13 나의 기록` 화면.
 *
 * 예측 이력은 연속 점수와 변화량으로 표시한다. 점수는 진단값이 아니며, 모델 버전이 바뀐
 * `model_changed` 지점에서는 서로 다른 모델의 점수를 하나의 추세처럼 연결하지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    vm: RecordViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    // 상단 세그먼트: 나의 기록(챌린지 통계) ↔ 근육 건강 정보(점수/시뮬레이션). (§1 명칭 변경)
    var tab by remember { mutableStateOf(RecordTab.RECORDS) }
    // §5.2 달력 일자 탭 → 바텀시트로 그날 완료 미션 목록.
    var selectedDay by remember { mutableStateOf<String?>(null) }
    // §5.3 걷기 막대 축 전환(시간/걸음).
    var walkMetric by remember { mutableStateOf(WalkingMetric.MINUTES) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        TopBar(title = "나의 기록", onBack = onBack)

        AigoSegmentedSelector(
            options = listOf(
                SegmentOption(RecordTab.RECORDS, "나의 기록"),
                SegmentOption(RecordTab.DASHBOARD, "근육 건강 정보"),
            ),
            selected = tab,
            onSelect = { tab = it },
            horizontal = true,
            modifier = Modifier.padding(
                horizontal = Dimens.ScreenPadding,
                vertical = Dimens.Space8,
            ),
        )

        when (tab) {
            RecordTab.RECORDS -> Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
            ) {
                // §5.1 일별 미션 완료 선그래프(최근 14일)
                AigoCard {
                    SectionTitle("최근 2주 미션 완료")
                    val keys = remember { recentDateKeys(14, System.currentTimeMillis()) }
                    val counts = remember(vm.lineLogs) { dailyCompletionCounts(vm.lineLogs, keys) }
                    Spacer(Modifier.height(Dimens.Space8))
                    CompletionLineChart(keys, counts)
                }

                // §5.2 미션 달력(월 뷰) — 일자 탭 시 바텀시트
                AigoCard {
                    SectionTitle("미션 달력")
                    Spacer(Modifier.height(Dimens.Space8))
                    MissionCalendar(
                        year = vm.calYear,
                        month1 = vm.calMonth,
                        resultByDate = vm.stampsByDate,
                        onPrevMonth = vm::showPreviousMonth,
                        onNextMonth = vm::showNextMonth,
                        onDaySelected = { selectedDay = it },
                    )
                }

                // §5.3 걷기 막대(시간/걸음 탭 전환)
                AigoCard {
                    SectionTitle("최근 7일 걷기")
                    Spacer(Modifier.height(Dimens.Space8))
                    AigoSegmentedSelector(
                        options = listOf(
                            SegmentOption(WalkingMetric.MINUTES, "시간(분)"),
                            SegmentOption(WalkingMetric.STEPS, "걸음 수"),
                        ),
                        selected = walkMetric,
                        onSelect = { walkMetric = it },
                        horizontal = true,
                    )
                    Spacer(Modifier.height(Dimens.Space8))
                    WalkingBarChart(vm.walkingDays, walkMetric)
                }

                // §5.4 챌린지 비율 도넛
                AigoCard {
                    SectionTitle("챌린지 비율")
                    Spacer(Modifier.height(Dimens.Space8))
                    val totals = vm.challengeTotals
                    if (totals == null) {
                        EmptyText("아직 기록이 없어요. 챌린지를 완료하면 이곳에 쌓여요.")
                    } else {
                        ChallengeDonut(totals)
                    }
                }

                MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT)
                Spacer(Modifier.height(Dimens.Space8))
            }

            RecordTab.DASHBOARD -> PredictionDashboardScreen(
                prefill = vm.predictionPrefill,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }

    // §5.2 일자 팝업: 그날 성공한 미션(미션명 + 완료 시각), 없으면 안내.
    selectedDay?.let { day ->
        ModalBottomSheet(onDismissRequest = { selectedDay = null }) {
            Column(Modifier.padding(Dimens.ScreenPadding)) {
                Text(
                    day.replace('-', '.'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Dimens.Space12))
                val missions = successMissionsOn(vm.monthLogs, day)
                if (missions.isEmpty()) {
                    Text(
                        "이날은 완료한 미션이 없어요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    missions.forEach { m ->
                        Text(
                            "${m.title} · ${koreanTime(m.completedAt)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(Dimens.Space8))
                    }
                }
                Spacer(Modifier.height(Dimens.Space16))
            }
        }
    }
}

/** 나의 기록 각 카드 제목(#기록탭 §5). */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

/** 나의 기록 화면 상단 세그먼트 탭(#193). */
internal enum class RecordTab { RECORDS, DASHBOARD }

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(Dimens.Space16), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorRow(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
        Text(
            "불러오지 못했어요. 네트워크를 확인해 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("다시 시도") }
    }
}

@Composable
private fun RiskTrendContent(items: List<RiskHistoryItem>) {
    val segments = remember(items) { buildRiskTrendSegments(items) }
    val points = segments.flatten()

    if (points.isEmpty()) {
        EmptyText("연속 점수 기록을 준비하고 있어요. 잠시 후 다시 확인해 주세요.")
        return
    }

    val latest = points.last()
    Text(
        "현재 관리 필요도 ${scorePercent(latest.score)}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(Dimens.Space4))
    Text(
        changeDescription(latest),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Dimens.Space16))
    RiskTrendChart(segments)
    Spacer(Modifier.height(Dimens.Space4))

    if (points.size == 1) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                formatDate(points.single().createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDate(points.first().createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDate(points.last().createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(Dimens.Space8))
    Text(
        "점수가 높을수록 생활습관을 조금 더 살펴보자는 뜻이에요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RiskTrendChart(segments: List<List<RiskTrendPoint>>) {
    val points = segments.flatten()
    val xFractions = remember(points) { buildRiskTrendXFractions(points) }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = riskTrendContentDescription(segments)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = "근육 건강 관리 필요도 그래프. $description" },
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val chartWidth = right - left
        val chartHeight = bottom - top
        listOf(0f, 0.5f, 1f).forEach { ratio ->
            val y = bottom - chartHeight * ratio
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }

        fun offset(point: RiskTrendPoint): Offset {
            val x = left + chartWidth * xFractions[point.index]
            val y = bottom - chartHeight * point.score.toFloat()
            return Offset(x, y)
        }

        segments.forEach { segment ->
            if (segment.size > 1) {
                val path = Path().apply {
                    val first = offset(segment.first())
                    moveTo(first.x, first.y)
                    segment.drop(1).forEach { point ->
                        val next = offset(point)
                        lineTo(next.x, next.y)
                    }
                }
                drawPath(
                    path = path,
                    color = primary,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                )
            }
            segment.forEach { point ->
                drawCircle(primary, radius = 5.dp.toPx(), center = offset(point))
            }
        }
    }
}

internal data class RiskTrendPoint(
    val index: Int,
    val createdAt: String,
    val score: Double,
    val changePercentagePoints: Double?,
    val comparisonStatus: String,
)

/** 모델 변경 지점에서 선을 끊기 위한 화면용 구간을 만든다. */
internal fun buildRiskTrendSegments(items: List<RiskHistoryItem>): List<List<RiskTrendPoint>> {
    val validPoints = items.mapNotNull { item ->
        val score = item.riskScore?.takeIf { it in 0.0..1.0 } ?: return@mapNotNull null
        item to score
    }
    if (validPoints.isEmpty()) return emptyList()

    val segments = mutableListOf<MutableList<RiskTrendPoint>>()
    validPoints.forEachIndexed { index, (item, score) ->
        val point = RiskTrendPoint(
            index = index,
            createdAt = item.createdAt,
            score = score,
            changePercentagePoints = item.changePercentagePoints,
            comparisonStatus = item.comparisonStatus,
        )
        if (segments.isEmpty() || (item.comparisonStatus == "model_changed" && segments.last().isNotEmpty())) {
            segments += mutableListOf(point)
        } else {
            segments.last() += point
        }
    }
    return segments
}

internal fun changeDescription(point: RiskTrendPoint): String {
    if (point.comparisonStatus == "model_changed") {
        return "새로운 기준으로 다시 살펴보기 시작했어요."
    }
    val change = point.changePercentagePoints ?: return "첫 기록이에요. 앞으로의 변화를 함께 살펴봐요."
    if (abs(change) < 1.0) return "지난 기록과 비슷하게 유지되고 있어요."

    val amount = String.format(Locale.KOREA, "%.1f", abs(change))
    return if (change < 0) {
        "지난 기록보다 ${amount}%p 낮아졌어요."
    } else {
        "지난 기록보다 ${amount}%p 높아졌어요. 생활습관을 조금 더 살펴봐요."
    }
}

internal fun riskTrendContentDescription(segments: List<List<RiskTrendPoint>>): String =
    segments.mapIndexed { index, segment ->
        val pointsDescription = segment.joinToString(", ") {
            "${formatDate(it.createdAt)} 관리 필요도 ${scorePercent(it.score)}"
        }
        if (index == 0) pointsDescription else "새로운 기준으로 다시 시작. $pointsDescription"
    }.joinToString(". ")

/** 실제 날짜 간격을 X축 비율로 바꾼다. 날짜가 하나라도 잘못되면 안전하게 순서 기반 배치로 폴백한다. */
internal fun buildRiskTrendXFractions(points: List<RiskTrendPoint>): List<Float> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(0.5f)

    val epochDays = points.map { parseIsoDateEpochDay(it.createdAt) }
    val validDays = epochDays.filterNotNull()
    val datesAreUsable = validDays.size == points.size &&
        validDays.zipWithNext().all { (previous, current) -> previous <= current }
    if (!datesAreUsable) {
        return points.indices.map { it / points.lastIndex.toFloat() }
    }

    val first = validDays.first()
    val span = validDays.last() - first
    if (span == 0L) return points.indices.map { it / points.lastIndex.toFloat() }
    return validDays.map { ((it - first).toDouble() / span).toFloat() }
}

private fun parseIsoDateEpochDay(value: String): Long? {
    val match = ISO_DATE_PREFIX.matchEntire(value.take(10)) ?: return null
    val (year, month, day) = match.destructured
    return runCatching {
        GregorianCalendar(UTC).apply {
            isLenient = false
            clear()
            set(year.toInt(), month.toInt() - 1, day.toInt())
        }.timeInMillis / MILLIS_PER_DAY
    }.getOrNull()
}

private fun scorePercent(score: Double): String = "${(score * 100).roundToInt()}%"

internal fun formatDate(iso: String): String =
    if (iso.length >= 10) iso.substring(0, 10).replace('-', '.') else iso

private val ISO_DATE_PREFIX = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
