package com.aihealthcare.ah0404.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens
import kotlin.math.max

// =====================================================================================
// 근육 건강 정보 화면(#기록탭 §3·§4) — 긍정 점수(높을수록 좋음) + 챌린지 시뮬레이션.
//   ⚠️ 점수는 서버/모델 계산이고 앱은 표시만 한다(지시서 §3.1). 아래 목데이터는 서버 응답을 흉내낸 샘플이며,
//      실데이터는 #272/#273 API(/risk-predictions/me/latest 의 score·score_band, /dashboard/score-simulation)로
//      교체한다. 확률(%)·관리 필요도 표기는 이 화면에 없다(§3.2).
// =====================================================================================

internal data class ScorePoint(
    val label: String,
    val score: Int,
    // 비교 불가 경계(리뷰 #275-②): 이 점부터 다른 기준(모델 변경 or 코호트표 버전 변경)의 점수다.
    //   경계를 넘어 선을 잇거나 증감을 계산하지 않는다.
    val newBaseline: Boolean = false,
)
internal data class ScoreSimPoint(val days: Int, val score: Int)

/**
 * 점수 추이 재구성(§3.2, 리뷰 #275-②). `comparison_status=model_changed` 또는 항목별
 * `cohort_version` 변경 지점에 newBaseline 을 찍는다 — 서로 다른 기준의 점수가 한 추세·증감으로
 * 이어지지 않게(기존 확률 추이의 buildRiskTrendSegments 가 하던 역할의 점수판 복원).
 * 점수 없는 이력(#273 마이그레이션 이전 행·65세 미만)은 추이에서 제외한다.
 */
internal fun buildScoreTrend(items: List<RiskHistoryItem>): List<ScorePoint> {
    val out = mutableListOf<ScorePoint>()
    var prevCohort: String? = null
    for (item in items) {
        val score = item.muscleScore ?: continue
        val boundary = item.comparisonStatus == "model_changed" ||
            (out.isNotEmpty() && item.cohortVersion != prevCohort)
        out += ScorePoint(trendLabel(item.createdAt), score, newBaseline = boundary && out.isNotEmpty())
        prevCohort = item.cohortVersion
    }
    return out
}

/** ISO(YYYY-MM-DD…) → "MM.DD". */
internal fun trendLabel(iso: String): String = if (iso.length >= 10) iso.substring(5, 10).replace('-', '.') else iso

/** newBaseline 지점에서 잘라 '같은 기준' 구간 목록으로. */
internal fun splitByBaseline(trend: List<ScorePoint>): List<List<ScorePoint>> {
    if (trend.isEmpty()) return emptyList()
    val segments = mutableListOf(mutableListOf<ScorePoint>())
    trend.forEach { p ->
        if (p.newBaseline && segments.last().isNotEmpty()) segments += mutableListOf<ScorePoint>()
        segments.last() += p
    }
    return segments
}

internal data class MuscleScoreUi(
    val age: Int?,          // §3.3 분기용(prediction-inputs 파생)
    val score: Int?,        // 0~100. null = 65+ 이지만 백엔드 점수 미도착 → "준비 중"
    val band: String?,      // good | maintain | caution
    // 체감 피드백(#357) 노출 키. null = 최신 예측 없음/구버전 서버 → 피드백 카드 미표시.
    val predictionId: Int? = null,
    val trend: List<ScorePoint>,
    val walkSim: List<ScoreSimPoint>,
    val muscSim: List<ScoreSimPoint>,
    val stsSeconds: Double?, // §3.4 5STS(초). null=미측정/스킵 → 안전망 카드 미표시
    val bmi: Double?,        // §3.4
    val cohort: CohortDistributionResponse? = null, // #193 또래 분포. null=미탑재/65세미만/실패 → 카드 미표시
    // 허리둘레(cm). null = 미입력 → 점수·또래 비교 모두 '허리 제외' 모델로 계산된 것이라 그 사실을 안내한다.
    //   (app/ml/predictor.py 가 허리 유무로 다른 번들·다른 코호트표를 쓴다)
    val waistCm: Int? = null,
)

private const val DISPLAY_FLOOR = 5 // 표시 하한 5점(§3.1) — 계산·저장은 0~100, 화면 표시만 최저 5.

internal fun shown(score: Int): Int = max(score, DISPLAY_FLOOR)

private fun bandLabel(band: String?): String = when (band) {
    "good" -> "좋음"
    "caution" -> "주의"
    else -> "유지"
}

private fun bandColor(band: String?): Color = when (band) {
    "good" -> Color(0xFF2E7D32)
    "caution" -> Color(0xFFC62828)
    else -> Color(0xFFEF6C00)
}

// 기록 탭 재구성(#334): '나의 기록'=대시보드(지금 내 상태·변화), '근육 건강 정보'=개선 잠재력(전망).
//   두 세그먼트가 점수 카드들을 나눠 갖도록 아래 두 함수로 쪼갠다. 각 함수는 호출부 Column(spacedBy) 안에
//   카드를 '직접' 배치한다(자체 Column·padding 없음) — 호출부 레이아웃/간격을 그대로 따른다.

/**
 * '나의 기록' 상단 점수 섹션(#334 질문 ①②): 지금 내 점수 → 변화 추이 → 또래 중 내 위치.
 * 점수가 없으면 연령별 안내 카드(점수 자리의 빈 상태이므로 이 섹션이 데리고 있는다).
 */
@Composable
internal fun MuscleDashboardCards(
    ui: MuscleScoreUi,
    onGoToMissions: () -> Unit,
) {
    val age = ui.age
    val score = ui.score
    when {
        // 점수가 있으면 연령 판별과 무관하게 점수를 보여준다(리뷰 #275-③) — 나이 출처인 prediction-inputs
        //   조회만 실패해도 유효한 점수가 "준비 중"에 가려지지 않게 score 우선(#273 게이트).
        score != null -> {
            ScoreHeadlineCard(score, ui.band, waistMissing = ui.waistCm == null) // ① 지금 내 점수
            ScoreTrendCard(ui.trend)                // ② 변화 추이(위험도 순화 표현)
            CohortDistributionCard(ui.cohort, waistMissing = ui.waistCm == null) // 또래 중 내 위치(#193)
        }
        // §3.3 점수가 없을 때만 연령 분기: 65세 미만 카드. 나이 미상은 준비 중.
        age == null -> ScorePendingCard()
        age < 50 -> UnderAgeInfoCard(onGoToMissions)
        age < 65 -> PreparingCard(onGoToMissions)
        else -> ScorePendingCard()
    }
    // 5STS 재측정·추이(#353): 점수 추이 아래 보조 지표. 직접 수행 지표라 점수(예측) 유무와 무관하게
    //   항상 표시한다 — 스킵·65세 미만 사용자도 여기서 측정을 시작할 수 있다.
    StsTrendCard()
}

/**
 * '근육 건강 정보' 섹션(#334 질문 ③, 전망): 운동하면 얼마나 좋아지는지 — what-if 시뮬레이션 + 근력 기능 안전망.
 * 점수가 없으면 준비 중 안내(시뮬레이션은 점수 기반이라 표시 불가).
 *
 * ⚠️ 의료 고지는 여기서 그리지 않는다 — 화면(RecordScreen)이 탭 맨 아래에 한 번만 배치한다(#385).
 *   이 섹션과 대시보드 섹션이 같은 탭에 모이면서, 여기서 그리면 고지가 두 번 나온다.
 */
@Composable
internal fun MuscleImprovementCards(ui: MuscleScoreUi, onGoToMissions: () -> Unit) {
    val score = ui.score
    if (score != null) {
        StsSafetyCard(ui, onGoToMissions) // §3.4 (조건 충족 시에만)
        ScoreSimulationCard(ui.muscSim, ui.walkSim, score)
        Spacer(Modifier.height(Dimens.Space8))
    } else {
        ImprovementPendingCard(onGoToMissions)
    }
}

// ── §3.2 헤드라인 + 구간 배지 ─────────────────────────────────────────────────
@Composable
private fun ScoreHeadlineCard(score: Int, band: String?, waistMissing: Boolean) {
    AigoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "근육 건강 점수 ${shown(score)}점",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(0.dp))
        }
        Spacer(Modifier.height(Dimens.Space8))
        BandBadge(band)
        Spacer(Modifier.height(Dimens.Space12))
        Text(
            "또래와 비교해 계산한 참고 점수예요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 허리둘레 미입력 안내는 '변화' 카드가 아니라 여기에 둔다 — 부정확성은 점수 자체의 성질이고,
        //   변화 카드는 점수가 2건 이상이어야 그려져 정작 처음 점수를 보는 사용자에게는 안 보인다.
        if (waistMissing) {
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                WAIST_MISSING_SCORE_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 허리둘레 미입력 안내(점수 카드). 허리둘레가 없으면 '허리 제외' 모델로 계산된다
 * (app/ml/predictor.py:355). 같은 BMI 라도 허리둘레로 근육/지방이 갈리는 주요 입력이라
 * 있고 없고가 결과에 영향을 준다. 다만 검증 신뢰구간이 겹치므로 "훨씬 정확해진다"고 말하지 않는다.
 */
private const val WAIST_MISSING_SCORE_NOTE =
    "허리둘레를 입력하면 더 정확하게 계산할 수 있어요. 설정 → 내 정보에서 추가할 수 있어요."

/** 허리둘레 미입력 안내(또래 카드). 코호트표도 허리 유무로 갈린다(app/ml/predictor.py:372). */
private const val WAIST_MISSING_COHORT_NOTE = "허리둘레를 입력하면 또래 비교도 더 정확해져요."

@Composable
private fun BandBadge(band: String?) {
    val color = bandColor(band)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = Dimens.Space12, vertical = Dimens.Space4),
    ) {
        Text(bandLabel(band), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

// ── §3.2 추이(y=점수, 높을수록 좋음, 변화 문구 방향 반전) ──────────────────────
@Composable
private fun ScoreTrendCard(trend: List<ScorePoint>) {
    AigoCard {
        Text("근육 건강 변화", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        if (trend.size < 2) {
            // 예측 1건이면 변화(두 점 이상)는 아직 없지만 침묵하지 않는다(#334 문제3): 왜 비었는지·언제 채워지는지·
            //   무엇을 하면 되는지 안내한다(현재 점수 자체는 위 헤드라인 카드에 크게 표시됨).
            Text(trendEmptyCopy(trend.size), style = MaterialTheme.typography.bodyLarge)
            return@AigoCard
        }
        Text(scoreChangeCopy(trend), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Dimens.Space12))
        val segments = splitByBaseline(trend)
        ScoreTrendChart(segments)
        Spacer(Modifier.height(Dimens.Space4))
        // 경계가 있으면 왜 끊겼는지 한 줄로 설명한다(#389 문제 1) — 없으면 그리지 않는다.
        trendBaselineCaption(segments)?.let { caption ->
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.Space4))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(trend.first().label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(trend.last().label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 추이가 두 점 미만일 때의 안내 문구(#334 문제3, 리뷰 #339). 이 함수는 **점수가 있을 때만**(ScoreTrendCard 는
 * score!=null 분기에서만 렌더) 도달한다. 따라서:
 *  - size=1: 점수 1건 → 두 번째 점은 **'내 정보' 저장(재평가, POST /reassess)** 에서 생긴다. 챌린지 완료만으로는
 *    새 예측이 안 생기므로(리뷰 #339-①) 실제 트리거인 '내 정보' 저장을 안내한다.
 *  - size=0: 점수는 있는데 추이만 비었다 = **추이(history) 조회 실패**(리뷰 #339-②). '첫 평가를 마치면'은 점수가
 *    이미 있는 것과 모순이므로, 조회 실패로 안내한다(첫 평가 미완료로 단정하지 않음).
 *
 * ⚠️ size=1 문구는 '무엇이 점수를 바꾸는가'와 '언제 다시 계산되는가'를 분리해서 말한다.
 *   재평가는 최신 프로필뿐 아니라 **최근 7일 실제 걷기·근력 기록**을 모델 입력으로 쓰는데
 *   (app/services/activity_metrics.py 의 derive_activity_day_counts), '내 정보 업데이트'만 안내하면
 *   "키·몸무게를 고치는 것이 점수를 올리는 방법"으로 읽힌다. 같은 카드의 증감 문구가 걷기·근력을
 *   가리키고 있어 서로 어긋나기도 했다.
 *   재평가 트리거가 '내 정보' 저장 하나뿐인 구조 자체는 별건이다(#388).
 */
internal fun trendEmptyCopy(size: Int): String =
    if (size == 1) {
        "아직 점수가 하나라 변화를 보여드릴 수 없어요. 걷기·근력 기록이 다음 점수에 반영돼요. " +
            "'내 정보'를 저장하면 그때까지의 활동으로 점수를 다시 계산해요."
    } else {
        "변화 추이를 불러오지 못했어요. 잠시 후 다시 확인해 주세요."
    }

/**
 * 변화 문구(§3.2, 방향 반전: 오르면 긍정). 증감은 마지막 '같은 기준' 구간 안에서만 계산한다(리뷰 #275-②) —
 * 기준(모델/코호트표)이 바뀐 직후에는 증감 대신 기준 변경 안내를 보여준다.
 */
internal fun scoreChangeCopy(trend: List<ScorePoint>): String {
    val segments = splitByBaseline(trend)
    val lastSegment = segments.lastOrNull() ?: return ""
    if (lastSegment.size < 2) {
        return if (segments.size > 1) {
            "새로운 기준으로 다시 살펴보기 시작했어요."
        } else {
            "첫 기록이에요. 앞으로의 변화를 함께 살펴봐요."
        }
    }
    val delta = shown(lastSegment.last().score) - shown(lastSegment[lastSegment.size - 2].score)
    return when {
        delta > 0 -> "지난 기록보다 ${delta}점 올랐어요. 지금처럼 이어가 봐요."
        // 하락 원인을 단정하지 않는다(#389 문제 2): 점수는 활동뿐 아니라 신체 정보 갱신(허리둘레 등)으로도
        //   내려간다. 실측값을 정확히 고쳤을 뿐인데 "운동을 안 해서 떨어졌다"로 읽히면 원인도 틀리고
        //   고령 사용자에게 불필요한 불안이 된다. API 가 원인을 내려주기 전까지는 사실만 말한다(#389 B·C 후속).
        delta < 0 -> "지난 기록보다 ${-delta}점 낮아졌어요."
        else -> "지난 기록과 비슷하게 유지되고 있어요."
    }
}

/**
 * 기준 경계가 있는 추이의 하단 캡션(#389 문제 1). 서로 다른 기준(모델 번들·코호트표)으로 계산된 점수를
 * 한 선으로 잇지 않느라 선이 끊기는데, 화면에 아무 표시가 없어 사용자가 **데이터 누락·앱 오류로 읽는다.**
 *
 * 원인(허리둘레 추가/제거 vs 코호트표 갱신)은 API 가 알려주지 않으므로(#389 B 후속) 중립적으로만 안내한다.
 * 경계가 없으면 null — 캡션을 그리지 않는다.
 */
internal fun trendBaselineCaption(segments: List<List<ScorePoint>>): String? =
    if (segments.size > 1) "점선 구분 이후는 계산 기준이 달라진 구간이에요. 그 앞뒤 점수는 직접 비교하지 않아요." else null

/** 접근성용 추이 설명 — 기준 경계도 음성으로 안내한다(리뷰 #275-②, 기존 확률 추이의 접근성 복원). */
internal fun trendDescription(segments: List<List<ScorePoint>>): String =
    segments.mapIndexed { i, seg ->
        val pts = seg.joinToString(", ") { "${it.label} ${shown(it.score)}점" }
        if (i == 0) pts else "새로운 기준으로 다시 시작. $pts"
    }.joinToString(". ")

@Composable
private fun ScoreTrendChart(segments: List<List<ScorePoint>>) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val desc = "근육 건강 점수 변화 그래프. ${trendDescription(segments)}"
    val total = segments.sumOf { it.size }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = desc },
    ) {
        val left = 8.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx(); val bottom = size.height - 8.dp.toPx()
        val w = right - left; val h = bottom - top
        listOf(0f, 0.5f, 1f).forEach { r ->
            val y = bottom - h * r
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        if (total == 0) return@Canvas
        fun pt(globalIndex: Int, score: Int): Offset {
            val x = left + w * if (total == 1) 0.5f else (globalIndex.toFloat() / (total - 1))
            val y = bottom - h * (score / 100f) // y=점수(0~100), 위로 갈수록 높은 점수
            return Offset(x, y)
        }
        // 기준 경계에서 선을 끊는다(리뷰 #275-②): 구간 안에서만 잇고, 구간 사이는 빈 간격으로 남긴다.
        //   끊긴 이유를 육안으로 알 수 있게(#389 문제 1) ① 경계에 세로 점선 ② 이전 기준 구간은 옅은 톤으로
        //   그린다. 최신 기준 구간만 진한 색이라 '지금 기준은 이쪽'이 한눈에 보인다.
        var index = 0
        segments.forEachIndexed { segIndex, seg ->
            val isCurrent = segIndex == segments.lastIndex
            val lineColor = if (isCurrent) primary else primary.copy(alpha = 0.35f)
            val startIndex = index
            val pts = seg.map { p -> pt(index++, shown(p.score)) }
            // 경계 세로 점선 — 첫 구간 앞에는 그리지 않는다(경계가 아니라 차트 시작이므로).
            if (segIndex > 0 && pts.isNotEmpty()) {
                val boundaryX = (pt(startIndex - 1, 0).x + pts.first().x) / 2f
                drawLine(
                    grid,
                    Offset(boundaryX, top),
                    Offset(boundaryX, bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                )
            }
            if (pts.size > 1) {
                val path = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, lineColor, style = Stroke(3.dp.toPx()))
            }
            pts.forEachIndexed { i, c ->
                val isLast = index == total && i == pts.lastIndex
                drawCircle(lineColor, radius = if (isLast) 6.dp.toPx() else 5.dp.toPx(), center = c)
            }
        }
    }
}

// ── §193 또래 분포 — '또래 중 내 위치'(확률% 미노출, 백분위·곡선·구간 띠) ──────────
@Composable
private fun CohortDistributionCard(cohort: CohortDistributionResponse?, waistMissing: Boolean) {
    if (cohort == null) return // 미탑재·65세 미만·조회 실패 → 카드 자체를 그리지 않는다(다른 섹션 무영향).
    AigoCard {
        Text("또래 중 내 위치", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        RiskDistributionChart(data = cohort)
        // 점수 카드의 안내와 겹치지 않게 한 줄로 짧게 — 여기서 달라지는 건 '비교 대상(코호트표)'이다.
        if (waistMissing) {
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                WAIST_MISSING_COHORT_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── §4 시뮬레이션(점수 곡선) ──────────────────────────────────────────────────
/**
 * 걷기 요약 1줄(리뷰 #275-①). 근력과 달리 걷기는 계수가 완만해(예측 화면 '해석 주의' 명시) 일수별 전체
 * 나열은 "주 7일 걸어도 그대로" 같은 김빠지는 목록이 된다 → 최대 일수 지점 1줄로 요약하고,
 * **점수 이득이 0이면 줄 자체를 생략**한다(걷기가 소용없다는 오해 방지 — 걷기의 가치는 챌린지가 담당).
 */
internal fun walkSummaryLine(walkSim: List<ScoreSimPoint>, currentScore: Int): String? {
    val top = walkSim.maxByOrNull { it.days } ?: return null
    val gain = shown(top.score) - shown(currentScore)
    if (gain <= 0) return null
    return "걷기를 주 ${top.days}일로 늘리면 → ${shown(top.score)}점 (+${gain}점)"
}

@Composable
private fun ScoreSimulationCard(muscSim: List<ScoreSimPoint>, walkSim: List<ScoreSimPoint>, currentScore: Int) {
    AigoCard {
        Text("이렇게 하면 이만큼", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        // 미래 지향 카피(§4): "지금보다 근력운동을 주 {n}일 하면 → {score}점"
        muscSim.filter { it.days >= 1 }.forEach { p ->
            Text(
                "지금보다 근력운동을 주 ${p.days}일 하면 → ${shown(p.score)}점",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Dimens.Space4))
        }
        // 걷기 시뮬레이션(리뷰 #275-①): 요약 1줄만. 이득 0이면 생략.
        walkSummaryLine(walkSim, currentScore)?.let { line ->
            Spacer(Modifier.height(Dimens.Space4))
            Text(line, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ── §3.4 근력 기능 안전망 카드(5STS 규칙 오버레이) ────────────────────────────
@Composable
private fun StsSafetyCard(ui: MuscleScoreUi, onGoToMissions: () -> Unit) {
    val sts = ui.stsSeconds
    // 발화 조건(§3.4): 5STS ≥ 12초 AND 점수 구간 ≠ 주의. 미측정/결측/스킵(null)이면 미표시.
    if (sts == null || sts < 12.0 || ui.band == "caution") return
    val strong = ui.bmi != null && ui.bmi >= 25.0 // 강한 티어(아시아 비만 기준)
    val tier = if (strong) "strong" else "basic"

    // 발화율 관측(§3.4, 필수): 카드 노출 시 서버 수집(POST /events/sts-overlay-shown, #366).
    //   키를 Unit 으로 고정 — 컴포지션 진입(화면 진입) 1회만 전송해, 리컴포지션·값 갱신으로
    //   같은 노출이 여러 건 집계되는 것을 막는다. 실패해도 카드 표시를 막지 않는다(fire-and-forget).
    LaunchedEffect(Unit) {
        StsOverlayReporter.report(tier = tier, stsSec = sts, bmi = ui.bmi, scoreBand = ui.band)
    }

    AigoCard {
        Text("근력 기능도 함께 살펴봐요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            if (strong) {
                "점수는 근육량 위주로 계산되어, 체중에 비해 근육이 적은 경우에는 실제보다 좋게 나올 수 있어요. " +
                    "앉았다 일어서기 결과도 느린 편이니, 가까운 병원에서 근력 상태를 한번 확인해 보시길 권해요."
            } else {
                "점수는 괜찮게 나왔지만, 앉았다 일어서기 검사 결과 근력 기능이 조금 느린 편이에요. " +
                    "점수는 근육량 위주라, 근력은 따로 챙기시는 게 좋아요. 걱정되시면 가까운 병원에서 상담받아 보세요."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoPrimaryButton(text = "근력 챌린지 하러 가기", onClick = onGoToMissions)
    }
}

// ── §3.3 연령 카드 ────────────────────────────────────────────────────────────
@Composable
private fun PreparingCard(onGoToMissions: () -> Unit) {
    AigoCard {
        Text("내 연령대 점수는 준비 중이에요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            "지금은 65세 이상 기준으로 근육 건강 점수를 제공해요. 50~64세 맞춤 점수는 " +
                "새 예측 모델과 함께 준비하고 있어요. 그동안 걷기·근력 챌린지로 근육을 먼저 챙겨 보세요.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoPrimaryButton(text = "챌린지 보러 가기", onClick = onGoToMissions)
    }
}

@Composable
private fun UnderAgeInfoCard(onGoToMissions: () -> Unit) {
    AigoCard {
        Text("근육 건강 점수 안내", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            "근육 건강 점수는 65세 이상 기준으로 제공하고 있어요. 걷기·근력 챌린지는 " +
                "연령과 관계없이 이용하실 수 있어요.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoPrimaryButton(text = "챌린지 보러 가기", onClick = onGoToMissions)
    }
}

@Composable
private fun ScorePendingCard() {
    AigoCard {
        Text("점수를 준비하고 있어요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            "조금 뒤에 다시 확인해 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 근육 건강 정보(개선 잠재력) 빈 상태(#334) — 점수 없으면 시뮬레이션 대신 안내 ────────────
@Composable
private fun ImprovementPendingCard(onGoToMissions: () -> Unit) {
    AigoCard {
        Text("개선 시뮬레이션은 점수가 준비되면 보여드려요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            "근육 건강 점수가 준비되면 '이렇게 하면 이만큼 좋아져요'를 이 화면에서 확인할 수 있어요. " +
                "그동안 걷기·근력 챌린지로 근육을 먼저 챙겨 보세요.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoPrimaryButton(text = "챌린지 보러 가기", onClick = onGoToMissions)
    }
}
