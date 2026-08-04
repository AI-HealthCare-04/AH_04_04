package com.aihealthcare.ah0404.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.ContributionItemDto
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.theme.AigoOutlineVariant
import com.aihealthcare.ah0404.ui.theme.AigoPrimary
import com.aihealthcare.ah0404.ui.theme.AigoTertiaryDark
import com.aihealthcare.ah0404.ui.theme.ChartLineGreen
import com.aihealthcare.ah0404.ui.theme.Dimens
import kotlin.math.abs
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
    // 점수 기준일(ISO). latest 응답에 날짜가 없어 추이 최신 항목에서 가져온다(#387, 핸드오프 §13 B-2).
    //   null = 추이 조회 실패 → H1 의 기준일 줄만 숨긴다.
    val measuredAtIso: String? = null,
    // 점수 기여도(#406): 바꿀 수 있는 근력·걷기·허리만. 빈 목록이면 기여도 카드 미표시.
    val contributions: List<ContributionItemDto> = emptyList(),
)

/** ISO(YYYY-MM-DD…) → "2026.08.02 기준". 형식이 짧으면 null(줄 자체를 숨긴다). */
internal fun measuredAtLabel(iso: String?): String? =
    iso?.takeIf { it.length >= 10 }?.let { "${it.substring(0, 10).replace('-', '.')} 기준" }

/** 점수 변화 그래프 기준 높이(글꼴 확대 시 함께 늘어난다). */
private val TrendChartBaseHeight = 120.dp

private const val DISPLAY_FLOOR = 5 // 표시 하한 5점(§3.1) — 계산·저장은 0~100, 화면 표시만 최저 5.

/**
 * 근육 건강 점수를 **언젠가라도** 제공할 수 있는 하한 나이. 이 미만은 학습 데이터(고령층 건강 조사)가 없어
 * 제공 계획 자체가 없다 — '준비 중' 안내를 쓰면 안 되는 경계다(1차 검토 피드백).
 * 50-64세는 이 위이므로 새 모델과 함께 준비 중으로 안내한다.
 */
internal const val MIN_SCORE_AGE = 50

/** 점수 하한 연령 — 이 위(65세)부터 실제 점수를 제공한다. */
private const val SCORE_AGE = 65

/**
 * 점수가 없을 때 어떤 안내를 보여줄지. **두 섹션(대시보드·근육 건강 정보)이 같은 판단을 쓰게** 한곳에 둔다 —
 * 각자 분기하던 탓에 50세 미만 사용자가 한 화면에서 "제공하지 않는다"와 "준비되면 보여준다"를 동시에 봤다
 * (1차 검토 피드백).
 */
internal enum class ScoreEmptyState { PENDING, UNDER_AGE, PREPARING }

internal fun scoreEmptyState(age: Int?): ScoreEmptyState = when {
    age == null -> ScoreEmptyState.PENDING          // 나이 미상(조회 실패) — 판단 불가라 잠시 후 재확인
    age < MIN_SCORE_AGE -> ScoreEmptyState.UNDER_AGE // 학습 데이터 없음 → 제공 계획 없음
    age < SCORE_AGE -> ScoreEmptyState.PREPARING     // 50-64세 — 새 모델과 함께 준비 중
    else -> ScoreEmptyState.PENDING                  // 65세 이상인데 점수 미도착 — 준비 중
}

// 문구를 상수로 빼 테스트가 '준비 중' 표현의 재유입을 막는다(1차 검토 피드백).
internal const val UNDER_AGE_SCORE_TITLE = "근육 건강 점수는 65세 이상만 제공해요"
internal const val UNDER_AGE_SCORE_BODY =
    "이 점수는 65세 이상 어르신의 건강 조사 자료로 만들어졌어요. " +
        "${MIN_SCORE_AGE}세 미만은 기준이 되는 자료가 없어 점수를 제공하지 않아요. " +
        "걷기·근력 챌린지와 운동 영상은 연령과 관계없이 그대로 이용하실 수 있어요."

/**
 * 점수 미도착(PENDING) 안내 문구. 기존 '점수를 준비하고 있어요' 카드의 본문을 그대로 옮긴 것으로,
 * #387 리디자인에서 그 카드의 자리를 H1 빈 상태 카드([ScoreEmptyCard])가 대신하면서 상수로 뺐다.
 */
internal const val SCORE_PENDING_BODY = "조금 뒤에 다시 확인해 주세요."

/**
 * 또래 위치 빈 상태 카드를 그릴지(PR #413 리뷰 P1). **점수를 받을 수 있는 PENDING 에서만** true.
 *
 *  이 카드의 문구는 "체력 검사를 완료하면 위치를 확인할 수 있어요" — 검사만 하면 또래 위치가 생긴다는
 *  약속이다. 65세 미만은 코호트표 자체가 없어 검사해도 받을 수 없으므로(#403 계약) 그 약속을 하면 안 된다.
 */
internal fun showsCohortEmptyCard(state: ScoreEmptyState): Boolean = state == ScoreEmptyState.PENDING

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
    // 점수 재계산(#388). 이 섹션이 5STS 까지 그리므로 **호출부에서 뒤에 붙이면 점수 카드가 아니라
    //   5STS 아래로 밀린다**(실기기 QA). 사용자가 "왜 안 바뀌지"를 느끼는 자리는 점수 옆이라
    //   여기서 직접 배치한다. null 이면 그리지 않는다(점수 제공 대상이 아닌 화면).
    scoreRefresh: ScoreRefreshState? = null,
    canRefreshScore: Boolean = true,
    onRefreshScore: (() -> Unit)? = null,
) {
    val age = ui.age
    val score = ui.score
    when {
        // 점수가 있으면 연령 판별과 무관하게 점수를 보여준다(리뷰 #275-③) — 나이 출처인 prediction-inputs
        //   조회만 실패해도 유효한 점수가 "준비 중"에 가려지지 않게 score 우선(#273 게이트).
        score != null -> {
            ScoreHeadlineCard(ui, score)            // H1 지금 내 점수
            // H1 바로 아래 — 점수를 보고 "안 바뀌네" 하는 그 자리에 둔다.
            onRefreshScore?.let { ScoreRefreshCard(scoreRefresh, canRefreshScore, it) }
            ScoreTrendCard(ui.trend)                // H2 점수 변화
            CohortDistributionCard(ui.cohort, waistMissing = ui.waistCm == null) // H3 또래 중 내 위치(#193)
        }
        // §3.3 점수가 없을 때만 연령 분기: 65세 미만 카드. 나이 미상은 준비 중.
        //   ⚠️ 50세 미만과 50-64세는 **성격이 다르다**(1차 검토 피드백): 50-64세는 새 모델과 함께 준비 중이지만,
        //      50세 미만은 학습 데이터가 없어 **제공 계획 자체가 없다**. 두 경우에 같은 '준비 중' 문구를 쓰면
        //      50세 미만 사용자가 기다리면 되는 것으로 오해한다.
        //   PENDING(나이 미상·65세 이상 미도착)만 리디자인의 H1 빈 상태 카드로 그린다 — 점수를 받을 수 있는
        //      사용자라 '점수 자리'를 보여주는 게 맞고, 연령 대상 밖에는 그 자리 자체가 오해가 된다(#403 유지).
        else -> when (scoreEmptyState(age)) {
            ScoreEmptyState.UNDER_AGE -> UnderAgeInfoCard(onGoToMissions)
            ScoreEmptyState.PREPARING -> PreparingCard(onGoToMissions)
            ScoreEmptyState.PENDING -> {
                ScoreEmptyCard()
                // H3 빈 상태(§8 H3) — 그릴지 여부는 [showsCohortEmptyCard] 가 판단한다(리뷰 P1).
                if (showsCohortEmptyCard(ScoreEmptyState.PENDING)) EmptyCohortCard()
            }
        }
    }
    // 5STS 재측정·추이(#353): 점수 추이 아래 보조 지표. 직접 수행 지표라 점수(예측) 유무와 무관하게
    //   항상 표시한다 — 스킵·65세 미만 사용자도 여기서 측정을 시작할 수 있다.
    StsTrendCard()
    // H5 생활습관 안내 — 점수가 없을 때만(§8 H5). 빈 화면이 안내로 끝나게 하는 마지막 카드다.
    //   H3 와 달리 연령 전 구간에 그린다: 걷기·근력운동 권유는 점수 제공 여부와 무관하게 참이라
    //   '검사하면 받을 수 있다'는 약속을 담지 않는다(PR #413 리뷰 P1 구분).
    if (score == null) LifestyleTipCard()
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
        ContributionCard(ui.contributions) // #406 무엇이 점수에 영향을 줬나(바꿀 수 있는 것만)
        ScoreSimulationCard(ui.muscSim, ui.walkSim, score)
        Spacer(Modifier.height(Dimens.Space8))
    } else {
        // 도달하지 않는다 — 호출부(RecordScreen)가 `ui.score != null` 일 때만 이 섹션을 그린다(#385).
        //   1차 제출본(09ec9bc)에는 그 게이트가 없어 50세 미만 사용자가 "점수가 준비되면 보여드려요"를
        //   실제로 봤고, 그게 1차 검토에서 지적된 문구다. 지금은 위 섹션의 연령 안내 하나만 나간다.
        ImprovementPendingCard(onGoToMissions)
    }
}

// ── H1 근육 건강 점수(§8 H1) ──────────────────────────────────────────────────
/**
 * 제목과 숫자를 분리한다(#387). 이전에는 `근육 건강 점수 73점`이 한 줄 제목이라 숫자가 제목에 묻혔다.
 *  좌: 점수 + 구간 배지 + 변화 한 줄 + 기준일 / 우: 상태 표정 원.
 *  변화 문구는 기존 확정 문구([scoreChangeCopy])를 그대로 쓴다(핸드오프 §0-1 다).
 */
@Composable
internal fun ScoreHeadlineCard(ui: MuscleScoreUi, score: Int) {
    AigoCard(title = "근육 건강 점수", contentSpacing = Dimens.Space12) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${shown(score)}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("점", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(Dimens.Space8))
                    BandBadge(ui.band)
                }
                // ⚠️ 변화 문구는 여기 두지 않는다(실기기 확인). 바로 아래 '점수 변화' 카드가 같은
                //   [scoreChangeCopy] 를 요약 1줄로 쓰고 있어, 두 카드에 똑같은 문장이 연달아 나왔다.
                //   핸드오프 §8 H1 은 H2 와 다른 문구를 전제했지만, 문구는 기존 확정본을 쓰기로 했으므로(§0-1 다)
                //   중복을 피해 '변화'는 H2 가 갖고 H1 은 점수·구간·기준일만 맡는다.
                measuredAtLabel(ui.measuredAtIso)?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Dimens.Space12))
            ScoreFaceIcon(band = ui.band)
        }
        // 허리둘레 미입력 안내는 '변화' 카드가 아니라 여기에 둔다 — 부정확성은 점수 자체의 성질이고,
        //   변화 카드는 점수가 2건 이상이어야 그려져 정작 처음 점수를 보는 사용자에게는 안 보인다.
        if (ui.waistCm == null) {
            Text(
                WAIST_MISSING_SCORE_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** H1 빈 상태(§8 H1) — 점수를 받을 수 있는 사용자에게만 그린다(65세 미만은 연령 안내 카드가 대신한다). */
@Composable
internal fun ScoreEmptyCard() {
    AigoCard(title = "근육 건강 점수", contentSpacing = Dimens.Space12) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("–", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(Dimens.Space8))
                    // 배지도 기존 BandBadge 와 같은 모양, 색만 비활성 토큰(§3 확정 — 새 색을 만들지 않는다).
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = Dimens.Space12, vertical = Dimens.Space4),
                    ) {
                        Text(
                            "기록 없음",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 문구는 기존 확정본을 그대로 쓴다(핸드오프 §0-1 다). 핸드오프의 "첫 기록을 만들어볼까요?"는
                //   측정을 유도하는 말이라, '점수가 아직 도착하지 않았다'는 이 상태(#403 PENDING)와 뜻이 어긋난다.
                Text(
                    SCORE_PENDING_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Dimens.Space12))
            ScoreFaceIcon(band = null, empty = true)
        }
    }
}

/**
 * 상태 표정 아이콘(§4-4·§8 H1) — **이미지가 아니라 Canvas 로 그린다.**
 *  원 + 링 + 눈 2개 + 입 호가 전부라 벡터가 선명하고, 구간이 늘어도 색과 입 곡률만 바꾸면 된다.
 *  색은 기존 [bandColor] 를 그대로 쓴다(새 색 추가 없음). 빈 상태는 비활성 토큰 + 무표정.
 */
@Composable
private fun ScoreFaceIcon(band: String?, empty: Boolean = false) {
    val ringColor = if (empty) MaterialTheme.colorScheme.surfaceVariant else bandColor(band)
    val faceColor = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else bandColor(band)
    // 입 곡률: 좋음=많이 웃음, 유지=살짝 웃음, 주의=평평, 빈 상태=평평(무표정).
    val smile = when {
        empty -> 0f
        band == "good" -> 1f
        band == "caution" -> 0f
        else -> 0.6f
    }
    val description = if (empty) "기록 없음" else "상태 ${bandLabel(band)}"
    Canvas(
        Modifier
            .size(72.dp)
            .semantics { contentDescription = description },
    ) {
        val r = size.minDimension / 2f
        val center = Offset(r, r)
        drawCircle(ringColor.copy(alpha = 0.25f), radius = r, center = center)
        drawCircle(ringColor, radius = r, center = center, style = Stroke(3.dp.toPx()))
        // 눈 2개
        val eyeDx = r * 0.32f
        val eyeDy = r * 0.22f
        val eyeR = r * 0.09f
        drawCircle(faceColor, radius = eyeR, center = Offset(center.x - eyeDx, center.y - eyeDy))
        drawCircle(faceColor, radius = eyeR, center = Offset(center.x + eyeDx, center.y - eyeDy))
        // 입: smile 이 0 이면 직선, 커질수록 아래로 볼록한 호.
        val mouthHalf = r * 0.36f
        val mouthY = center.y + r * 0.24f
        val mouth = Path().apply {
            moveTo(center.x - mouthHalf, mouthY)
            quadraticTo(center.x, mouthY + mouthHalf * smile, center.x + mouthHalf, mouthY)
        }
        drawPath(mouth, faceColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
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
        // 구간색은 기존 그대로, 크기만 H1 에 맞춰 줄인다(§8 H1 — 점수 숫자가 주인공이라 배지가 커선 안 된다).
        Text(bandLabel(band), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

// ── H2 점수 변화(§8 H2, y=점수·높을수록 좋음) ────────────────────────────────
/**
 * 요약 한 줄이 그래프보다 **위**에 온다(#387) — 시니어는 이 줄만 읽어도 결론이 전달돼야 한다.
 *  문구는 기존 확정본([scoreChangeCopy]/[trendEmptyCopy])을 그대로 쓴다(핸드오프 §0-1 다).
 *
 *  ⚠️ 핸드오프 §6 은 추이 2건 미만이면 "카드 자체를 숨긴다"고 적었지만, 그 근거는 '빈 그래프 금지'다.
 *   여기서는 **그래프만 그리지 않고 카드는 남긴다** — 이 안내 문구는 왜 비었는지·무엇을 하면 채워지는지
 *   알리려고 #334/#339 에서 두 번 다듬은 확정 문구라, 카드를 통째로 지우면 그 안내가 사라진다.
 *   빈 그래프가 렌더되지 않는다는 요구는 그대로 지킨다.
 */
@Composable
internal fun ScoreTrendCard(trend: List<ScorePoint>) {
    AigoCard(title = "점수 변화", contentSpacing = Dimens.Space12) {
        if (trend.size < 2) {
            // 예측 1건이면 변화(두 점 이상)는 아직 없지만 침묵하지 않는다(#334 문제3): 왜 비었는지·언제 채워지는지·
            //   무엇을 하면 되는지 안내한다(현재 점수 자체는 위 헤드라인 카드에 크게 표시됨).
            Text(trendEmptyCopy(trend.size), style = MaterialTheme.typography.bodyLarge)
            return@AigoCard
        }
        Text(
            scoreChangeCopy(trend),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        val segments = splitByBaseline(trend)
        ScoreTrendChart(segments)
        // 경계가 있으면 왜 끊겼는지 한 줄로 설명한다(#389 문제 1) — 없으면 그리지 않는다.
        //   x축 날짜는 차트가 직접 그리므로 아래 첫·마지막 라벨 Row 는 더 이상 두지 않는다.
        trendBaselineCaption(segments)?.let { caption ->
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * 점수 변화 꺾은선(§8 H2). 시안 대비 바뀐 점: 격자선 제거, y 눈금 3개(50/70/90)만, 각 점 위에 점수 값,
 * x축에 날짜 라벨. 값 표시는 시니어가 그래프 모양이 아니라 **숫자**로 읽게 하려는 것이다.
 *
 *  y 범위는 눈금과 어긋나지 않게 50~90 으로 고정하되, 범위를 벗어난 점수(예: 45·95)도 잘리지 않도록
 *  실제 값이 밖으로 나가면 그만큼 넓힌다. 기준 경계(#275-②)에서 선을 끊는 규칙은 그대로다.
 */
@Composable
private fun ScoreTrendChart(segments: List<List<ScorePoint>>) {
    val lineColor = ChartLineGreen
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    // 기준 경계 점선은 '의미를 전달하는 선'이라 대비를 따로 확보한다(#394 리뷰 P2):
    //   카드 배경 기준 outlineVariant 는 1.62:1 로 저시력·고령 사용자가 구분하기 어렵다.
    //   outline 은 4.28:1 로 그래프 선 권장선(3:1)을 넉넉히 넘는다.
    val baselineDivider = MaterialTheme.colorScheme.outline
    val desc = "근육 건강 점수 변화 그래프. ${trendDescription(segments)}"
    val flat = segments.flatten()
    val total = flat.size
    if (total == 0) return
    val scores = flat.map { shown(it.score) }
    val minY = minOf(50, scores.min())
    val maxY = maxOf(90, scores.max())
    val ticks = listOf(50, 70, 90).filter { it in minY..maxY }
    // 글꼴을 키우면 라벨도 같이 커지므로 플롯 높이를 함께 늘린다 — 안 그러면 값 라벨이 선에 붙는다(실기기 확인).
    val fontScale = LocalDensity.current.fontScale
    val chartHeight = (TrendChartBaseHeight.value * (1f + (fontScale - 1f) * 0.6f)).dp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .semantics { contentDescription = desc },
    ) {
        val labelSize = 12.sp.toPx()
        val labelPaint = chartTextPaint(labelColor, labelSize)
        // y 라벨 폭을 실측해 축 위치를 정한다 — 고정 폭이면 글꼴 확대 시 축과 겹친다(실기기 지적).
        val yLabelW = ticks.maxOfOrNull { labelPaint.measureText(it.toString()) } ?: 0f
        val left = yLabelW + 12.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = labelSize + 8.dp.toPx()                    // 점 위 값 라벨 자리
        val bottom = size.height - (labelSize + 8.dp.toPx()) // x 날짜 라벨 자리
        val h = bottom - top
        fun yOf(score: Int): Float = bottom - h * ((score - minY).toFloat() / (maxY - minY).toFloat())

        // 축선(실기기 지적): 격자선은 여전히 없지만, 축 경계가 없으면 그래프로 읽히지 않는다.
        drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 1.dp.toPx())
        drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())

        ticks.forEach { tick ->
            drawChartText(
                tick.toString(),
                left - 4.dp.toPx() - yLabelW / 2f,
                yOf(tick) + labelSize / 3f,
                labelColor,
                labelSize,
            )
        }

        // 점을 '슬롯 가운데'에 놓아 첫·마지막 점이 축에 붙지 않게 한다(실기기 지적: 좌우 여백 없음).
        val slot = (right - left) / total
        fun xOf(index: Int): Float = left + slot * index + slot / 2f

        // 기준 경계에서 선을 끊는다(리뷰 #275-②): 구간 안에서만 잇고, 구간 사이는 빈 간격으로 남긴다.
        //   끊긴 이유를 육안으로 알 수 있게(#389 문제 1) ① 경계에 세로 점선 ② 이전 기준 구간은 옅은 톤으로
        //   그린다. 최신 기준 구간만 진한 색이라 '지금 기준은 이쪽'이 한눈에 보인다.
        var index = 0
        segments.forEachIndexed { segIndex, seg ->
            val isCurrent = segIndex == segments.lastIndex
            // 이전 기준 구간은 옅게 그리되 식별은 가능해야 한다(#394 리뷰 P2): alpha 0.6 이면 3:1 을
            //   넘기면서도 현재 기준과 농도 차이는 유지된다.
            val segColor = if (isCurrent) lineColor else lineColor.copy(alpha = 0.6f)
            val startIndex = index
            val pts = seg.map { p -> Offset(xOf(index++), yOf(shown(p.score))) }
            // 경계 세로 점선 — 첫 구간 앞에는 그리지 않는다(경계가 아니라 차트 시작이므로).
            if (segIndex > 0 && pts.isNotEmpty()) {
                val boundaryX = (xOf(startIndex - 1) + pts.first().x) / 2f
                drawLine(
                    baselineDivider,
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
                drawPath(path, segColor, style = Stroke(2.dp.toPx()))
            }
            pts.forEach { drawCircle(segColor, radius = 3.dp.toPx(), center = it) }
        }

        // 날짜 라벨이 슬롯보다 넓으면 서로 겹치므로 처음·마지막만 남긴다(글꼴 200% 대응).
        val widestDate = flat.maxOfOrNull { labelPaint.measureText(it.label) } ?: 0f
        val showAllDates = widestDate <= slot * 0.95f
        flat.forEachIndexed { i, p ->
            val x = xOf(i)
            drawChartText(shown(p.score).toString(), x, yOf(shown(p.score)) - 6.dp.toPx(), valueColor, labelSize, bold = true)
            if (showAllDates || i == 0 || i == flat.lastIndex) {
                // 양 끝 라벨이 캔버스 밖으로 나가지 않도록 안쪽으로 당긴다.
                val halfW = labelPaint.measureText(p.label) / 2f
                val clampedX = x.coerceIn(halfW, size.width - halfW)
                drawChartText(p.label, clampedX, size.height - 2.dp.toPx(), labelColor, labelSize)
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

// ── #406 SHAP 기여도: 바꿀 수 있는 것(근력·걷기·허리)이 점수에 준 영향 ─────────────
/** 백엔드 feature 키 → 화면 라벨. 화이트리스트 밖(나이·성별·체중 등)은 null 로 걸러 절대 노출하지 않는다. */
internal fun contributionLabel(feature: String): String? = when (feature) {
    "musc_days" -> "근력 운동"
    "walk_days" -> "걷기"
    "waist_cm" -> "허리둘레"
    else -> null
}

internal data class ContributionRow(val label: String, val effect: Double, val raising: Boolean)

// 영향이 사실상 0인 항목의 컷오프. 백엔드가 log-odds 를 소수 넷째 자리로 반올림하므로(#411),
//   |effect| < 0.00005 는 표시상 0(영향 없음)으로 본다 — 0 을 '개선 여지'로 오표시하지 않기 위함(#406 P2).
internal const val CONTRIBUTION_EFFECT_EPSILON = 5e-5

/**
 * 표시용 행: 화이트리스트로 거르고, **영향이 0인 항목은 제외**한 뒤 |영향| 큰 순으로 정렬한다.
 * 0(영향 없음)은 '개선 여지'라는 근거가 없어 행에서 빼며(#406 리뷰), 그 결과 전부 0이면 빈 목록이 되어
 * 카드가 통째로 숨는다 — 표시 여부 판단이 이 순수 함수 하나로 고정돼 테스트할 수 있다(#406 리뷰).
 */
internal fun contributionRows(contributions: List<ContributionItemDto>): List<ContributionRow> =
    contributions
        .mapNotNull { c ->
            val effect = c.effectOnScoreLogOdds
            if (abs(effect) < CONTRIBUTION_EFFECT_EPSILON) return@mapNotNull null // 0 은 표시하지 않는다.
            contributionLabel(c.feature)?.let { ContributionRow(it, effect, effect > 0) }
        }
        .sortedByDescending { abs(it.effect) }

@Composable
private fun ContributionCard(contributions: List<ContributionItemDto>) {
    // 표시할 행이 없으면(구버전 서버·기여도 없음·모두 0) 카드 자체를 그리지 않는다.
    val rows = contributionRows(contributions)
    if (rows.isEmpty()) return
    val maxMag = abs(rows.first().effect) // 정렬 결과 첫 행이 최대. epsilon 필터로 항상 > 0.

    AigoCard {
        Text("무엇이 내 점수에 영향을 줬을까요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space4))
        Text(
            // 체중·BMI 는 '바꾸기 어려워서'가 아니라 모델 계수 방향이 건강 조언과 어긋나 오해를 막으려 뺀 것이다
            //   (#406 설계·리뷰). 사용자가 "체중은 못 바꾼다"로 읽지 않도록 중립적으로 안내한다.
            "지금부터 꾸준히 바꿀 수 있는 근력·걷기·허리 중심으로 보여드려요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space12))
        rows.forEach { row ->
            ContributionBar(row, maxMag)
            Spacer(Modifier.height(Dimens.Space12))
        }
    }
}

@Composable
private fun ContributionBar(row: ContributionRow, maxMag: Double) {
    // 진녹색 = 이 습관이 점수를 올리는 중, 골드 = 여기서 더 올릴 수 있음(개선 여지). 오류가 아니라 빨강은 안 쓴다.
    //   두 색·트랙 모두 테마 토큰이며, 카드 배경(AigoSurface) 대비 4.5:1 이상(텍스트)과
    //   트랙(AigoOutlineVariant) 대비 3:1 이상(막대)을 **둘 다** 만족한다(#406 리뷰 — 저시력 접근성):
    //     진녹 AigoPrimary      텍스트 10.45:1 · 막대 6.46:1
    //     골드 AigoTertiaryDark 텍스트  5.96:1 · 막대 3.68:1
    val barColor = if (row.raising) AigoPrimary else AigoTertiaryDark
    val fraction = (abs(row.effect) / maxMag).toFloat().coerceIn(0.06f, 1f)
    val directionText = if (row.raising) "점수를 올리고 있어요" else "여기서 더 올릴 수 있어요"
    Column(
        // 막대 길이=영향 크기는 순수 시각 정보라, TalkBack 사용자를 위해 라벨·방향·상대 크기를 음성으로 안내한다
        //   (#406 리뷰 — 같은 화면 ScoreTrendChart 와 기준을 맞춘다). 수치(log-odds)는 노출하지 않는다.
        Modifier.semantics {
            contentDescription = "$directionText 항목: ${row.label}, 영향 크기 ${barPercentLabel(fraction)}"
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                directionText,
                style = MaterialTheme.typography.bodyMedium,
                color = barColor,
            )
        }
        Spacer(Modifier.height(Dimens.Space4))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(AigoOutlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(barColor),
            )
        }
    }
}

/** 막대 채움 비율(0~1)을 음성 안내용 백분율 문구로. 순수 함수라 테스트로 고정한다. */
internal fun barPercentLabel(fraction: Float): String = "${(fraction * 100).toInt()}%"

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

/**
 * 50세 미만 안내(1차 검토 피드백). **'준비 중'이라고 하지 않는다** — 근육 건강 점수는 고령층 조사 자료로
 * 만들어졌고 50세 미만은 학습 데이터가 없어 제공 계획이 없다. "지금은", "아직" 같은 말도 쓰지 않는다:
 * 기다리면 열리는 것으로 읽히면 안 된다. 대신 **왜 안 되는지**를 말하고, 쓸 수 있는 것으로 안내한다.
 */
@Composable
private fun UnderAgeInfoCard(onGoToMissions: () -> Unit) {
    AigoCard {
        Text(UNDER_AGE_SCORE_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(UNDER_AGE_SCORE_BODY, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Dimens.Space12))
        AigoPrimaryButton(text = "챌린지 보러 가기", onClick = onGoToMissions)
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

// ── H3 빈 상태 · H5 생활습관(§8) ──────────────────────────────────────────────

/**
 * 또래 중 내 위치 — 빈 상태(§8 H3). 점수가 없으면 내 위치도 없다.
 *  **수치·마커·구간 띠를 전부 숨기고 흐린 실루엣만** 남긴다 — 값이 0 인 차트를 그리지 않는다는
 *  이번 리디자인의 원칙(§0-2)을 지키면서도, 이 카드가 무엇을 보여줄 자리인지는 알리기 위한 것이다.
 */
@Composable
internal fun EmptyCohortCard() {
    AigoCard(title = "또래 중 내 위치", contentSpacing = Dimens.Space8) {
        Text("아직 기록이 없어요", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Text(
            "체력 검사를 완료하면 위치를 확인할 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DistributionSilhouette()
    }
}

/** 분포 실루엣(장식) — 종 모양 곡선만 옅게. 값이 없으므로 눈금·마커·라벨을 그리지 않는다. */
@Composable
private fun DistributionSilhouette() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .semantics { contentDescription = "또래 분포 예시 그림" },
    ) {
        val w = size.width
        val h = size.height
        val baseline = h - 4.dp.toPx()
        // 왼쪽으로 치우친 종 모양 — 실제 코호트 분포와 같은 인상만 준다(수치 아님).
        val path = Path().apply {
            moveTo(0f, baseline)
            cubicTo(w * 0.18f, baseline, w * 0.20f, 6.dp.toPx(), w * 0.34f, 6.dp.toPx())
            cubicTo(w * 0.48f, 6.dp.toPx(), w * 0.52f, baseline * 0.72f, w * 0.72f, baseline * 0.88f)
            cubicTo(w * 0.86f, baseline * 0.96f, w * 0.92f, baseline, w, baseline)
            close()
        }
        drawPath(path, tint)
    }
}

/**
 * 생활습관으로 바꿔보세요(§8 H5) — **점수가 없을 때만** 표시한다.
 *  빈 화면이 "아무것도 없다"로 끝나지 않게, 지금 할 수 있는 일을 마지막에 한 번 더 짚어 준다.
 */
@Composable
internal fun LifestyleTipCard() {
    AigoCard(title = "생활습관으로 바꿔보세요", contentSpacing = Dimens.Space12) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "꾸준한 걷기와 근력운동이\n건강한 근육 유지에 도움을 줘요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Image(
                painter = painterResource(R.drawable.img_record_habit_shoes),
                contentDescription = null, // 장식용(§10)
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * '점수 다시 계산하기'(#388). 점수 카드 바로 아래에 둔다 — 사용자가 "왜 안 바뀌지"를 느끼는 자리다.
 *
 * 하루 1회 제한(#396)에 걸리면 숫자가 그대로라 고장으로 읽히므로, 왜 안 바뀌었는지와 언제 다시
 * 되는지를 함께 말한다. 재시도 버튼은 네트워크·서버 실패에서만 의미가 있다.
 */
@Composable
private fun ScoreRefreshCard(state: ScoreRefreshState?, canRefresh: Boolean, onRefresh: () -> Unit) {
    AigoCard {
        AigoPrimaryButton(
            text = if (state == ScoreRefreshState.IN_PROGRESS) "다시 계산 중…" else "점수 다시 계산하기",
            onClick = onRefresh,
            // 정책 판정은 호출부가 순수 함수로 계산해 넘긴다 — 시각 경과를 화면 재개마다 반영해야 한다.
            enabled = canRefresh,
        )
        scoreRefreshStatusText(state)?.let { status ->
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == ScoreRefreshState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
