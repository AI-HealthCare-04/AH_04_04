package com.aihealthcare.ah0404.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 또래 분포 차트(#193) 순수 로직: 백분위→문구 매핑·꼬리 케이스·x매핑·density 보간.
 * 기록탭 개편의 확률(%) 제거 원칙에 따라 % 포맷·마커 % 라벨은 이 화면에서 노출하지 않으므로 검증 대상도 아니다.
 */
class RiskDistributionChartTest {

    @Test
    fun `성별 라벨 매핑`() {
        assertEquals("남성", sexLabelKo("male"))
        assertEquals("여성", sexLabelKo("female"))
        assertEquals("남성", sexLabelKo("unknown")) // 기본 남성(방어)
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
    fun `헤드라인 문장 - 스펙 문구 그대로(퍼센트 없음)`() {
        assertEquals("같은 연령대(75–79세) 남성 100명 중", riskAgeSexLine("75–79세", "남성"))
        // 순번 방향은 점수와 같은 '높을수록 좋음'. 확률 제거 원칙과 함께 '위험' 프레임도 쓰지 않는다.
        assertEquals("근육 건강이 좋은 쪽에서 73번째", riskRankLine(73))
        assertEquals("나보다 낮음 72명", riskAreaLower(72))
        assertEquals("나보다 높음 27명", riskAreaHigher(27))
        assertEquals("나", RISK_MARKER_LABEL) // 마커 라벨은 % 없이 "나"만
        assertEquals("또래 여성 분포 (국민건강영양조사 기반)", riskCurveCaption("여성"))
    }

    @Test
    fun `위험 높은 꼬리 - 순번 대신 질적 표현`() {
        // 96~100번째를 그대로 보여주면 "100명 중 꼴찌"로 읽힌다 → 순번 없는 문장.
        val headline = riskHighTailHeadline("65–68세", "여성")
        assertEquals("같은 연령대(65–68세) 여성 중에서는 근육 건강을 더 챙기시면 좋은 편이에요", headline)
        assertFalse(headline.contains("번째"))
    }

    @Test
    fun `행동 문구 - 인과·최상급 주장 없음`() {
        // 단면 조사 기반 연관 모델이라 "시작하면 낮아진다"(인과)·"가장 크게"(최상급)를 말할 수 없다.
        assertEquals("근력운동은 근육 건강 관리에 도움이 될 수 있어요", RISK_HEADLINE_TAIL)
        assertFalse(RISK_HEADLINE_TAIL.contains("가장"))
        assertFalse(RISK_HEADLINE_TAIL.contains("낮아지는"))
    }

    @Test
    fun `차트 접근성 설명 - 순번만, 확률 미노출`() {
        assertEquals(
            "또래 100명 중 근육 건강이 좋은 쪽에서 73번째.",
            riskChartContentDescription(72),
        )
    }

    @Test
    fun `차트 접근성 설명 - 위험 높은 꼬리는 화면과 동일하게 순번 미노출`() {
        val description = riskChartContentDescription(99) // 순번이라면 100번째
        assertEquals("또래 100명 중 근육 건강을 더 챙기시면 좋은 쪽에 있어요.", description)
        assertFalse(description.contains("번째"))
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
