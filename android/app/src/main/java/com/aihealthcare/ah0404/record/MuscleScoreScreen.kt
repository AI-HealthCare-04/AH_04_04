package com.aihealthcare.ah0404.record

import android.util.Log
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.theme.Dimens
import kotlin.math.max

// =====================================================================================
// 근육 건강 정보 화면(#기록탭 §3·§4) — 긍정 점수(높을수록 좋음) + 챌린지 시뮬레이션.
//   ⚠️ 점수는 서버/모델 계산이고 앱은 표시만 한다(지시서 §3.1). 아래 목데이터는 서버 응답을 흉내낸 샘플이며,
//      실데이터는 #272/#273 API(/risk-predictions/me/latest 의 score·score_band, /dashboard/score-simulation)로
//      교체한다. 확률(%)·관리 필요도 표기는 이 화면에 없다(§3.2).
// =====================================================================================

internal data class ScorePoint(val label: String, val score: Int)
internal data class ScoreSimPoint(val days: Int, val score: Int)

internal data class MuscleScoreUi(
    val age: Int?,          // §3.3 분기용(prediction-inputs 파생)
    val score: Int?,        // 0~100. null = 65+ 이지만 백엔드 점수 미도착 → "준비 중"
    val band: String?,      // good | maintain | caution
    val trend: List<ScorePoint>,
    val walkSim: List<ScoreSimPoint>,
    val muscSim: List<ScoreSimPoint>,
    val stsSeconds: Double?, // §3.4 5STS(초). null=미측정/스킵 → 안전망 카드 미표시
    val bmi: Double?,        // §3.4
)

private const val DISPLAY_FLOOR = 5 // 표시 하한 5점(§3.1) — 계산·저장은 0~100, 화면 표시만 최저 5.

private fun shown(score: Int): Int = max(score, DISPLAY_FLOOR)

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

@Composable
internal fun MuscleScoreScreen(
    ui: MuscleScoreUi,
    onGoToMissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
    ) {
        val age = ui.age
        when {
            // §3.3 65세 미만: 점수 자리에 카드(추이·시뮬레이션 미표시). 나이 미상도 안전하게 준비 중.
            age == null -> ScorePendingCard()
            age < 50 -> UnderAgeInfoCard(onGoToMissions)
            age < 65 -> PreparingCard(onGoToMissions)
            ui.score == null -> ScorePendingCard()
            else -> {
                ScoreHeadlineCard(ui.score, ui.band)
                StsSafetyCard(ui, onGoToMissions) // §3.4 (조건 충족 시에만)
                ScoreTrendCard(ui.trend)
                ScoreSimulationCard(ui.muscSim)
                MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT)
                Spacer(Modifier.height(Dimens.Space8))
            }
        }
    }
}

// ── §3.2 헤드라인 + 구간 배지 ─────────────────────────────────────────────────
@Composable
private fun ScoreHeadlineCard(score: Int, band: String?) {
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
    }
}

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
            Text(
                "기록이 쌓이면 변화를 보여드릴게요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AigoCard
        }
        Text(scoreChangeCopy(trend), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Dimens.Space12))
        ScoreTrendChart(trend.map { shown(it.score) })
        Spacer(Modifier.height(Dimens.Space4))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(trend.first().label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(trend.last().label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 변화 문구 방향 반전(§3.2): 점수는 높을수록 좋음 → 오르면 긍정. */
private fun scoreChangeCopy(trend: List<ScorePoint>): String {
    val delta = shown(trend.last().score) - shown(trend[trend.size - 2].score)
    return when {
        delta > 0 -> "지난 기록보다 ${delta}점 올랐어요. 지금처럼 이어가 봐요."
        delta < 0 -> "지난 기록보다 ${-delta}점 낮아졌어요. 걷기·근력 챌린지로 다시 올려봐요."
        else -> "지난 기록과 비슷하게 유지되고 있어요."
    }
}

@Composable
private fun ScoreTrendChart(scores: List<Int>) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        val left = 8.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx(); val bottom = size.height - 8.dp.toPx()
        val w = right - left; val h = bottom - top
        listOf(0f, 0.5f, 1f).forEach { r ->
            val y = bottom - h * r
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        fun pt(i: Int): Offset {
            val x = left + w * (i.toFloat() / (scores.size - 1))
            val y = bottom - h * (scores[i] / 100f) // y=점수(0~100), 위로 갈수록 높은 점수
            return Offset(x, y)
        }
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until scores.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(path, primary, style = Stroke(3.dp.toPx()))
        scores.indices.forEach { i ->
            drawCircle(primary, radius = if (i == scores.lastIndex) 6.dp.toPx() else 5.dp.toPx(), center = pt(i))
        }
    }
}

// ── §4 시뮬레이션(점수 곡선) ──────────────────────────────────────────────────
@Composable
private fun ScoreSimulationCard(muscSim: List<ScoreSimPoint>) {
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

    // 발화율 관측(§3.4, 필수): 카드 노출 시 이벤트 로깅. (서버 수집 엔드포인트는 백엔드 필요 목록.)
    LaunchedEffect(tier, sts, ui.bmi, ui.band) {
        Log.i("sts_overlay_shown", "tier=$tier, sts_sec=$sts, bmi=${ui.bmi}, score_band=${ui.band}")
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
