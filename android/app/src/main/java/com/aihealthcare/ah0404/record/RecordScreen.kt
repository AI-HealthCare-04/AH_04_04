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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
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
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: RecordViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "나의 기록", onBack = onBack)

        Column(
            Modifier.padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            AigoCard {
                Text(
                    "근육 건강 변화",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Dimens.Space4))
                Text(
                    "생활습관 관리를 위해 살펴보는 참고 점수예요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.Space8))
                when {
                    vm.historyError -> ErrorRow(onRetry = vm::load)
                    vm.loading && vm.history.isEmpty() -> LoadingRow()
                    vm.history.isEmpty() -> EmptyText(
                        "아직 기록이 없어요. 건강 확인을 마치면 이곳에서 변화를 볼 수 있어요.",
                    )
                    else -> RiskTrendContent(vm.history)
                }
            }

            AigoCard {
                Text(
                    "그동안의 활동",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Dimens.Space8))
                when {
                    vm.activityError -> ErrorRow(onRetry = vm::load)
                    vm.loading && !vm.loaded -> LoadingRow()
                    else -> Text(
                        "완료한 미션 ${vm.completedMissions}개 · 모은 포인트 %,d P".format(vm.totalPoints),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT)
            Spacer(Modifier.height(Dimens.Space8))
        }
    }
}

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
    if (span == 0L) return List(points.size) { 0.5f }
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
