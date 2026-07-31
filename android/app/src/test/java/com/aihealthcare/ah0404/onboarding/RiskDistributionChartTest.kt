package com.aihealthcare.ah0404.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 또래 분포 차트(#193) 순수 로직: 백분위→문구 매핑·꼬리 케이스·확률 포맷·x매핑·density 보간. */
class RiskDistributionChartTest {

    @Test
    fun `성별 라벨 매핑`() {
        assertEquals("남성", sexLabelKo("male"))
        assertEquals("여성", sexLabelKo("female"))
        assertEquals("남성", sexLabelKo("unknown")) // 기본 남성(방어)
    }

    @Test
    fun `확률 포맷 - 소수 1자리, 0점5퍼센트 미만, 50퍼센트 초과 실제값`() {
        assertEquals("18.0%", formatProbabilityPercent(0.18f))
        assertEquals("5.3%", formatProbabilityPercent(0.053f))
        assertEquals("0.5% 미만", formatProbabilityPercent(0.004f)) // 0.5% 미만
        assertEquals("0.5%", formatProbabilityPercent(0.005f)) // 경계는 표시
        assertEquals("62.0%", formatProbabilityPercent(0.62f)) // 50% 초과여도 실제값 표시(§4.3)
    }

    @Test
    fun `순번과 면적 인원 - lowerCount 기준`() {
        assertEquals(73, rankFromLowerCount(72)) // 헤드라인 순번 = lowerCount+1
        assertEquals(27, higherCount(72)) // 오른쪽 = 99 - lowerCount(본인 제외 합 99)
        assertEquals(1, higherCount(98))
    }

    @Test
    fun `꼬리 케이스 경계 - 위험 높은쪽 85 이상, 낮은쪽 15 이하`() {
        assertFalse(isHighRiskTail(84))
        assertTrue(isHighRiskTail(85)) // 85 이상 → 행동 유도
        assertTrue(isHighRiskTail(99))
        assertTrue(isLowRiskTail(15)) // 15 이하 → 유지 문구
        assertFalse(isLowRiskTail(16))
        assertTrue(isLowRiskTail(1))
        // 중간대는 어느 꼬리도 아님
        assertFalse(isHighRiskTail(50))
        assertFalse(isLowRiskTail(50))
    }

    @Test
    fun `헤드라인 문장 - 스펙 문구 그대로`() {
        assertEquals("같은 연령대(75–79세) 남성 100명 중", riskAgeSexLine("75–79세", "남성"))
        assertEquals("위험이 낮은 쪽에서 73번째", riskRankLine(73))
        assertEquals("나보다 낮음 72명", riskAreaLower(72))
        assertEquals("나보다 높음 27명", riskAreaHigher(27))
        assertEquals("나 · 18.0%", riskMarkerLabel("18.0%"))
        assertEquals("또래 여성 분포 (국민건강영양조사 기반)", riskCurveCaption("여성"))
    }

    @Test
    fun `차트 접근성 설명 - 순번과 확률`() {
        assertEquals(
            "또래 100명 중 위험이 낮은 쪽에서 73번째. 추정 확률 18.0%",
            riskChartContentDescription(72, "18.0%"),
        )
    }

    @Test
    fun `x 매핑 분율 - 0에서 1, 50퍼센트에서 클램프`() {
        assertEquals(0f, probToPlotFraction(0f), 1e-6f)
        assertEquals(0.5f, probToPlotFraction(0.25f), 1e-6f) // 25% → 절반
        assertEquals(1f, probToPlotFraction(0.5f), 1e-6f)
        assertEquals(1f, probToPlotFraction(0.8f), 1e-6f) // 50% 초과는 오른쪽 끝 클램프(§2)
    }

    @Test
    fun `마커 라벨 기준선 - 봉우리에서 상단 안쪽으로 clamp`() {
        // 봉우리(밀도 100) 케이스(리뷰 #302): 320dp 폭이면 높이 100dp, curveTop=16dp → 원하는 기준선 6dp.
        //   13sp 라벨 ascent≈-12.4dp 라 기준선을 2dp - ascent = 14.4dp 로 내려야 상단이 안 잘린다.
        assertEquals(14.4f, clampedLabelBaseline(desiredBaseline = 6f, ascent = -12.4f, minTop = 2f), 1e-4f)
        // fontScale 1.3: ascent 도 1.3배 → clamp 위치가 그만큼 더 내려온다.
        assertEquals(2f + 12.4f * 1.3f, clampedLabelBaseline(6f, -12.4f * 1.3f, 2f), 1e-3f)
        // 봉우리가 아니어서 여유가 충분하면 원래 기준선 유지.
        assertEquals(40f, clampedLabelBaseline(40f, -12.4f, 2f), 1e-4f)
        // 이미 경계에 정확히 걸치면 그대로.
        assertEquals(14.4f, clampedLabelBaseline(14.4f, -12.4f, 2f), 1e-4f)
    }

    @Test
    fun `density 선형보간 - 사이값과 범위 밖`() {
        val density = listOf(listOf(0.0f, 0f), listOf(0.1f, 100f), listOf(0.2f, 40f))
        assertEquals(0f, densityYAt(density, -0.1f), 1e-4f) // 범위 왼쪽 밖 → 첫값
        assertEquals(40f, densityYAt(density, 0.3f), 1e-4f) // 범위 오른쪽 밖 → 끝값
        assertEquals(50f, densityYAt(density, 0.05f), 1e-4f) // 0~0.1 사이 절반
        assertEquals(70f, densityYAt(density, 0.15f), 1e-4f) // 0.1~0.2 사이 절반(100→40)
        assertEquals(0f, densityYAt(emptyList(), 0.1f), 1e-4f) // 빈 입력
    }
}
