package com.aihealthcare.ah0404.record

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import kotlin.math.min

/**
 * 또래 분포 병합 차트(#193) — "분포 곡선 + 내 위치 마커 + 구간 띠"를 한 축에 병합해 기록탭 근육 건강 정보에 표시한다.
 *
 * 기록탭 개편의 '확률(%) 제거' 원칙 적용(결정 2026-07-31): raw 확률=위험 예측률이므로 헤드라인·마커·x축·
 *  접근성 문구에서 % 숫자를 노출하지 않는다. 또래 중 내 위치는 **lower_count 백분위**("100명 중 낮은 쪽에서
 *  N번째")로만 표현한다 — quantiles 기반이라 근육 건강 점수 순서와 일관된다(#193 §3.1 백분위↔점수 공존).
 *  마커 x 위치는 내부적으로 확률로 배치되지만 숫자는 드러내지 않고, 낮음/중간/높음 '띠'로만 질적 위치를 준다.
 *
 * 스펙(#193 issue193): 곡선은 중립 단색(녹→적 그라데이션 금지), 녹/황/적은 축 아래 '띠'에만 + 텍스트 라벨 병기,
 *  내 위치 왼쪽 면적을 진하게, y축 눈금 없음. lowerCount≥85 는 행동 유도 헤드라인으로 전환(§5.1).
 *  앱은 라이트 고정 테마라 §2 색상표의 라이트값을 쓴다(다크 스킴은 앱 전역 미도입).
 */

// ── 색상 토큰(#193 §2, 라이트) — 앱이 라이트 고정이라 라이트값만. 다크 도입 시 여기만 분기하면 된다.
private val CurveColor = Color(0xFF2A78D6)
private val CurveFillFull = Color(0xFF2A78D6).copy(alpha = 0.10f)
private val CurveFillLower = Color(0xFF2A78D6).copy(alpha = 0.28f)
private val MarkerColor = Color(0xFF0B0B0B)
private val ZoneLowFill = Color(0xFF1D9E75).copy(alpha = 0.35f)
private val ZoneLowLabel = Color(0xFF0F6E56)
private val ZoneMidFill = Color(0xFFEDA100).copy(alpha = 0.35f)
private val ZoneMidLabel = Color(0xFF854F0B)
private val ZoneHighFill = Color(0xFFD85A30).copy(alpha = 0.35f)
private val ZoneHighLabel = Color(0xFF993C1D)
private val AreaLowerLabel = Color(0xFF3A3A3A)
private val AreaHigherLabel = Color(0xFF8A8A8A)

@Composable
fun RiskDistributionChart(data: CohortDistributionResponse, modifier: Modifier = Modifier) {
    val sexLabel = sexLabelKo(data.sex)
    val rank = rankFromLowerCount(data.lowerCount)
    val highTail = isHighRiskTail(data.lowerCount)
    val lowTail = isLowRiskTail(data.lowerCount)
    val chartDescription = riskChartContentDescription(data.lowerCount)

    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = chartDescription }) {
        // 헤드라인은 % 대신 백분위 순번. 또래 범위 문장과 항상 함께 둔다(꼬리 케이스에서도 유지, 리뷰 #302).
        //   단 위험 높은 꼬리에서는 순번을 숨기므로(아래) 이 도입부도 함께 문장으로 합친다 —
        //   "100명 중"만 남고 순번이 없으면 문장이 끊긴다.
        if (highTail) {
            Text(
                riskHighTailHeadline(data.ageLabel, sexLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CurveColor,
            )
        } else {
            Text(
                riskAgeSexLine(data.ageLabel, sexLabel),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                riskRankLine(rank),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CurveColor,
            )
        }
        // §5.1 꼬리 프레임: 위험 높은 쪽이면 행동 유도 문구를 '추가', 낮은 쪽이면 유지 격려.
        when {
            highTail -> Text(
                RISK_HEADLINE_TAIL,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = CurveColor,
            )
            lowTail -> Text(
                RISK_LOW_TAIL_SUFFIX,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            riskCurveCaption(sexLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(640f / 200f),
        ) {
            drawDistribution(
                density = data.density,
                probability = data.probability,
                lowerCount = data.lowerCount,
                showAreaLabels = !highTail,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            RISK_DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 병합 차트 본체를 그린다(스펙 §2 그리는 순서). y축 눈금 없음. x축 % 숫자는 표기하지 않는다(확률 제거). */
private fun DrawScope.drawDistribution(
    density: List<List<Float>>,
    probability: Float,
    lowerCount: Int,
    showAreaLabels: Boolean,
) {
    val w = size.width
    val h = size.height
    val padL = w * 0.06f
    val padR = w * 0.06f
    val plotW = w - padL - padR
    val curveTop = h * 0.16f
    val baseline = h * 0.58f
    val curveH = baseline - curveTop

    // x = 확률 → 화면 좌표(스펙 §2 gpos: 0.5 에서 클램프). 숫자는 노출하지 않고 위치만 쓴다.
    fun px(p: Float): Float = padL + plotW * probToPlotFraction(p)
    fun cy(y: Float): Float = baseline - (y / 100f) * curveH

    val markerP = min(probability, 0.5f)
    val markerX = px(probability)

    // 1~3) 곡선 채움(전체/내 위치까지)·스트로크 — density 가 있을 때만.
    if (density.size >= 2) {
        val curve = Path()
        density.forEachIndexed { i, pt ->
            val x = px(pt[0])
            val y = cy(pt[1])
            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(px(density.last()[0]), baseline)
            lineTo(px(density.first()[0]), baseline)
            close()
        }
        // 오른쪽(내 위치 이후) 옅게, 왼쪽(내 위치까지) 진하게 — 각 영역만 clip 해 정확한 알파 적용.
        clipRect(left = markerX, top = 0f, right = w, bottom = h) { drawPath(fill, CurveFillFull) }
        clipRect(left = 0f, top = 0f, right = markerX, bottom = h) { drawPath(fill, CurveFillLower) }
        drawPath(curve, CurveColor, style = Stroke(width = 2.dp.toPx()))
    }

    // 4) 내 위치 마커: 곡선 위 지점부터 베이스라인까지 수직선 + 원. 라벨은 % 없이 "나"만.
    val markerY = if (density.isNotEmpty()) cy(densityYAt(density, markerP)) else curveTop
    drawLine(
        MarkerColor,
        start = Offset(markerX, markerY - 6.dp.toPx()),
        end = Offset(markerX, baseline),
        strokeWidth = 2.dp.toPx(),
    )
    drawCircle(MarkerColor, radius = 5.dp.toPx(), center = Offset(markerX, markerY))
    // 마커 라벨: 봉우리(밀도 100)에선 markerY≈curveTop 이라 고정 오프셋 기준선이 Canvas 상단 밖으로
    //   ascent 만큼 벗어나 잘린다. 글꼴 메트릭 기준으로 상단 안쪽에 clamp(리뷰 #302, fontScale 확대 포함).
    val markerLabelPaint = chartTextPaint(CurveColor, 13.sp.toPx(), bold = true)
    val markerLabelBaseline = clampedLabelBaseline(
        desiredBaseline = markerY - 10.dp.toPx(),
        ascent = markerLabelPaint.fontMetrics.ascent,
        minTop = 2.dp.toPx(),
    )
    drawContext.canvas.nativeCanvas.drawText(RISK_MARKER_LABEL, markerX, markerLabelBaseline, markerLabelPaint)

    // 5) 면적 라벨(꼬리 케이스면 숨김) — 좁아 겹치면 생략.
    if (showAreaLabels) {
        val minLabelW = 64.dp.toPx()
        val labelY = baseline - curveH * 0.35f
        if (markerX - padL > minLabelW) {
            drawChartText(riskAreaLower(lowerCount), (padL + markerX) / 2f, labelY, AreaLowerLabel, 12.sp.toPx())
        }
        val rightEdge = w - padR
        if (rightEdge - markerX > minLabelW) {
            drawChartText(riskAreaHigher(higherCount(lowerCount)), (markerX + rightEdge) / 2f, labelY, AreaHigherLabel, 12.sp.toPx())
        }
    }

    // 6) 구간 띠(낮음 / 중간 / 높음) — 색만으로 의미 X, 아래에 텍스트 라벨 병기. % 눈금은 없다.
    val bandTop = baseline + h * 0.06f
    val bandH = h * 0.09f
    val corner = CornerRadius(2.dp.toPx())
    drawRoundRect(ZoneLowFill, Offset(px(0f), bandTop), Size(px(0.15f) - px(0f), bandH), corner)
    drawRoundRect(ZoneMidFill, Offset(px(0.15f), bandTop), Size(px(0.30f) - px(0.15f), bandH), corner)
    drawRoundRect(ZoneHighFill, Offset(px(0.30f), bandTop), Size(px(0.50f) - px(0.30f), bandH), corner)
    val zoneLabelY = bandTop + bandH + 13.dp.toPx()
    drawChartText(RISK_ZONE_LOW, (px(0f) + px(0.15f)) / 2f, zoneLabelY, ZoneLowLabel, 11.sp.toPx())
    drawChartText(RISK_ZONE_MID, (px(0.15f) + px(0.30f)) / 2f, zoneLabelY, ZoneMidLabel, 11.sp.toPx())
    drawChartText(RISK_ZONE_HIGH, (px(0.30f) + px(0.50f)) / 2f, zoneLabelY, ZoneHighLabel, 11.sp.toPx())
}

private fun DrawScope.drawChartText(text: String, centerX: Float, baselineY: Float, color: Color, sizePx: Float, bold: Boolean = false) {
    drawContext.canvas.nativeCanvas.drawText(text, centerX, baselineY, chartTextPaint(color, sizePx, bold))
}

private fun chartTextPaint(color: Color, sizePx: Float, bold: Boolean = false): Paint = Paint().apply {
    this.color = color.toArgb()
    textSize = sizePx
    textAlign = Paint.Align.CENTER
    isAntiAlias = true
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

// ==================== 순수 헬퍼(테스트 대상) ====================

/**
 * 위험 높은 꼬리에 덧붙이는 행동 문구.
 *
 * ⚠️ 이전 문구("지금 근력운동을 시작하면 가장 크게 낮아지는 구간이에요")는 우리 데이터로 뒷받침할 수 없어
 *   교체했다. 한 문장에 검증하지 않은 주장이 둘 있었다.
 *   ① 인과 — 모델은 KNHANES 2022–2024 **단면 조사** 기반 로지스틱 회귀로, 같은 시점의 활동량과
 *      근감소증 상태의 *연관*을 학습한다. 근력운동을 시작한 사람을 추적해 위험이 떨어지는지 본
 *      개입·추적 연구가 없으므로 "시작하면 낮아진다"고 단정할 수 없다.
 *   ② 최상급 — "가장 크게"는 구간별 개입 효과 비교를 전제하는데 그런 분석 자체가 없다.
 *   같은 카드에 "진단이 아닙니다" 고지를 붙여두고 바로 위에서 효과를 단정하면 고지의 신뢰도도 깎인다.
 *   근거: docs/ml/sarcopenia_validation_awgs2025_summary.md (내부 검증 전용, 외부·시간적 검증 없음)
 */
internal const val RISK_HEADLINE_TAIL = "근력운동은 근육 건강 관리에 도움이 될 수 있어요"
internal const val RISK_LOW_TAIL_SUFFIX = "잘 유지하고 있어요"
internal const val RISK_DISCLAIMER = "동일 모델로 예측한 국민건강영양조사 표본 내 위치이며 진단이 아닙니다"
internal const val RISK_ZONE_LOW = "낮음"
internal const val RISK_ZONE_MID = "중간"
internal const val RISK_ZONE_HIGH = "높음"
internal const val RISK_MARKER_LABEL = "나"

internal fun sexLabelKo(sex: String): String = if (sex == "female") "여성" else "남성"

/** 순번(§4.1): 헤드라인은 lowerCount+1 번째. */
internal fun rankFromLowerCount(lowerCount: Int): Int = lowerCount + 1

/** 오른쪽 면적 인원(§4.1): 본인 1명 제외 합계 99. */
internal fun higherCount(lowerCount: Int): Int = 99 - lowerCount

/** §5.1 위험 높은 쪽 꼬리(나보다 낮은 사람이 대다수) → 행동 유도. */
internal fun isHighRiskTail(lowerCount: Int): Boolean = lowerCount >= 85

/** §5.1 위험 낮은 쪽 꼬리(또래 대부분보다 위험 낮음) → 유지 문구. */
internal fun isLowRiskTail(lowerCount: Int): Boolean = lowerCount <= 15

internal fun riskAgeSexLine(ageLabel: String, sexLabel: String): String =
    "같은 연령대($ageLabel) $sexLabel 100명 중"

/**
 * 순번 문구(§4.1). 방향은 점수와 같은 '높을수록 좋음'으로 말한다 — 기록탭은 확률(%)을 지우고 긍정
 * 점수만 쓰기로 했는데(RecordScreen §3·§4), 이 문장만 "위험" 프레임이 남아 바로 위 점수 카드와
 * 반대 방향을 가리키고 있었다. 순서 자체는 그대로다(rank 1 = 가장 좋음).
 */
internal fun riskRankLine(rank: Int): String = "근육 건강이 좋은 쪽에서 ${rank}번째"

/**
 * 위험 높은 꼬리(lowerCount≥85)용 헤드라인 — 순번을 쓰지 않는다.
 *
 * 이 구간의 순번은 96~100번째라 "100명 중 꼴찌"로 읽힌다. 시니어 사용자에게 좌절만 남기고,
 * 상태 자체는 위 점수 카드의 점수와 '주의' 배지가 이미 전달하므로 숫자가 더 주는 정보가 없다.
 * 분포 차트의 "나보다 낮음/높음 N명"은 그대로 남아 원하면 확인할 수 있다.
 */
internal fun riskHighTailHeadline(ageLabel: String, sexLabel: String): String =
    "같은 연령대($ageLabel) $sexLabel 중에서는 근육 건강을 더 챙기시면 좋은 편이에요"

internal fun riskAreaLower(lowerCount: Int): String = "나보다 낮음 ${lowerCount}명"

internal fun riskAreaHigher(higherCount: Int): String = "나보다 높음 ${higherCount}명"

internal fun riskCurveCaption(sexLabel: String): String = "또래 $sexLabel 분포 (국민건강영양조사 기반)"

/**
 * 차트 전체 contentDescription(§6). N=순번(lowerCount+1). 확률 % 는 읽지 않는다(확률 제거).
 * 화면에서 순번을 숨기는 위험 높은 꼬리에서는 읽어주는 문장도 같이 숨긴다 — TalkBack 사용자에게만
 * "100명 중 100번째"가 들리면 화면과 어긋난다.
 */
internal fun riskChartContentDescription(lowerCount: Int): String =
    if (isHighRiskTail(lowerCount)) {
        "또래 100명 중 근육 건강을 더 챙기시면 좋은 쪽에 있어요."
    } else {
        "또래 100명 중 근육 건강이 좋은 쪽에서 ${rankFromLowerCount(lowerCount)}번째."
    }

/** x 좌표 매핑의 분율(0~1). p 는 0.5 에서 클램프(스펙 §2 gpos). */
internal fun probToPlotFraction(p: Float): Float = (min(p, 0.5f) / 0.5f).coerceIn(0f, 1f)

/**
 * 텍스트 기준선 clamp(리뷰 #302): 기준선+ascent(음수)가 minTop 보다 위면 글자 윗부분이 Canvas 밖으로
 * 잘리므로 `minTop - ascent` 까지 내린다. ascent 가 fontScale 에 비례해 커져도 항상 상단 안쪽에 놓인다.
 */
internal fun clampedLabelBaseline(desiredBaseline: Float, ascent: Float, minTop: Float): Float =
    maxOf(desiredBaseline, minTop - ascent)

/** density([[x,y]]) 에서 확률 x 의 상대밀도 y 를 선형보간. 범위 밖은 가장자리 값. */
internal fun densityYAt(density: List<List<Float>>, x: Float): Float {
    if (density.isEmpty()) return 0f
    if (x <= density.first()[0]) return density.first()[1]
    if (x >= density.last()[0]) return density.last()[1]
    for (i in 0 until density.size - 1) {
        val x0 = density[i][0]
        val x1 = density[i + 1][0]
        if (x in x0..x1) {
            val span = x1 - x0
            val t = if (span <= 0f) 0f else (x - x0) / span
            return density[i][1] + t * (density[i + 1][1] - density[i][1])
        }
    }
    return 0f
}
