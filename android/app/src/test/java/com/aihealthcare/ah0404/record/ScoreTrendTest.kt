package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.RiskHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 점수 추이 경계 처리(#기록탭 §3.2, 리뷰 #275-②) — 비교 불가 지점(model_changed·코호트표 버전 변경)에서
 * 선·증감이 이어지지 않는다. 삭제됐던 확률 추이 회귀 테스트(RiskTrendTest)의 점수판 복원이다.
 */
class ScoreTrendTest {

    private fun item(
        date: String,
        score: Int?,
        status: String = "comparable",
        cohort: String? = "knhanes2022_2024_v1",
    ) = RiskHistoryItem(
        createdAt = "${date}T09:00:00+09:00",
        careStage = "maintain",
        muscleScore = score,
        cohortVersion = cohort,
        comparisonStatus = status,
    )

    @Test
    fun model_changed_marks_new_baseline_and_splits_segments() {
        val trend = buildScoreTrend(
            listOf(
                item("2026-07-01", 70),
                item("2026-07-08", 72),
                item("2026-07-15", 60, status = "model_changed"),
                item("2026-07-22", 63),
            ),
        )
        assertEquals(listOf(false, false, true, false), trend.map { it.newBaseline })
        assertEquals(listOf(2, 2), splitByBaseline(trend).map { it.size })
    }

    @Test
    fun cohort_version_change_marks_new_baseline() {
        val trend = buildScoreTrend(
            listOf(
                item("2026-07-01", 70, cohort = "v1"),
                item("2026-07-08", 74, cohort = "v2"), // 코호트표 갱신 → 다른 기준
            ),
        )
        assertEquals(listOf(false, true), trend.map { it.newBaseline })
        assertEquals(2, splitByBaseline(trend).size)
    }

    @Test
    fun scoreless_items_are_skipped_and_first_point_is_never_baseline() {
        // #273 마이그레이션 이전 행(점수 null)은 추이에서 제외되고, 남은 첫 점은 경계가 아니다.
        val trend = buildScoreTrend(
            listOf(
                item("2026-07-01", null),
                item("2026-07-08", 70, status = "model_changed"),
            ),
        )
        assertEquals(1, trend.size)
        assertEquals(false, trend.single().newBaseline)
    }

    @Test
    fun change_copy_stops_at_baseline_boundary() {
        // 경계 직후: 증감 대신 기준 변경 안내(다른 기준의 점수 차이를 '하락'으로 안내하지 않는다).
        val boundary = buildScoreTrend(
            listOf(item("2026-07-01", 70), item("2026-07-15", 60, status = "model_changed")),
        )
        assertEquals("새로운 기준으로 다시 살펴보기 시작했어요.", scoreChangeCopy(boundary))

        // 같은 기준 안에서는 방향 반전된 증감 문구(오르면 긍정).
        val up = buildScoreTrend(listOf(item("2026-07-01", 70), item("2026-07-08", 74)))
        assertEquals("지난 기록보다 4점 올랐어요. 지금처럼 이어가 봐요.", scoreChangeCopy(up))

        val down = buildScoreTrend(listOf(item("2026-07-01", 74), item("2026-07-08", 70)))
        // 하락 원인 미단정(#389 문제 2): 신체 정보 갱신으로도 내려가므로 활동 탓으로 읽히면 안 된다.
        assertEquals("지난 기록보다 4점 낮아졌어요.", scoreChangeCopy(down))
        assertFalse(scoreChangeCopy(down).contains("걷기"))

        val flat = buildScoreTrend(listOf(item("2026-07-01", 70), item("2026-07-08", 70)))
        assertEquals("지난 기록과 비슷하게 유지되고 있어요.", scoreChangeCopy(flat))
    }

    @Test
    fun accessibility_description_announces_baseline_boundary() {
        val segments = splitByBaseline(
            buildScoreTrend(
                listOf(
                    item("2026-07-01", 70),
                    item("2026-07-15", 60, status = "model_changed"),
                ),
            ),
        )
        assertEquals("07.01 70점. 새로운 기준으로 다시 시작. 07.15 60점", trendDescription(segments))
    }

    @Test
    fun walk_summary_line_shows_gain_and_omits_zero_gain() {
        // 리뷰 #275-①: 걷기 시뮬은 최대 일수 요약 1줄. 이득 0이면 생략(걷기 무용 오해 방지).
        assertEquals(
            "걷기를 주 7일로 늘리면 → 73점 (+3점)",
            walkSummaryLine(listOf(ScoreSimPoint(0, 70), ScoreSimPoint(7, 73)), currentScore = 70),
        )
        assertNull(walkSummaryLine(listOf(ScoreSimPoint(7, 70)), currentScore = 70))
        assertNull(walkSummaryLine(emptyList(), currentScore = 70))
    }

    @Test
    fun display_floor_applies_to_copy_delta() {
        // 표시 하한 5점(§3.1): 문구의 증감도 표시값 기준이라 0→4점 변화는 5→5로 '유지'.
        val trend = buildScoreTrend(listOf(item("2026-07-01", 0), item("2026-07-08", 4)))
        assertEquals("지난 기록과 비슷하게 유지되고 있어요.", scoreChangeCopy(trend))
    }

    @Test
    fun empty_trend_copy_guides_instead_of_silence() {
        // #334 문제3 + 리뷰 #339: 점수가 있을 때만 도달하므로 침묵 대신 정확한 안내를 준다.
        val one = buildScoreTrend(listOf(item("2026-07-01", 72)))
        assertEquals(1, one.size)
        // size=1: 두 번째 점은 '내 정보' 저장(재평가)에서 생김 — 챌린지 아님(리뷰 #339-①).
        //   다만 점수를 바꾸는 것은 활동이므로(재평가가 최근 7일 걷기·근력을 입력으로 쓴다) 둘을 나눠 말한다.
        val copy = trendEmptyCopy(one.size)
        assertEquals(
            "아직 점수가 하나라 변화를 보여드릴 수 없어요. 걷기·근력 기록이 다음 점수에 반영돼요. " +
                "'내 정보'를 저장하면 그때까지의 활동으로 점수를 다시 계산해요.",
            copy,
        )
        assertTrue(copy.contains("걷기·근력")) // 활동이 점수를 바꾼다는 사실이 빠지면 안 된다
        assertTrue(copy.contains("'내 정보'")) // 다시 계산되는 시점도 함께 말한다
        // size=0: 점수는 있는데 추이만 비었다 = 추이 조회 실패. '첫 평가를 마치면'은 점수 존재와 모순이므로 조회 실패 안내(리뷰 #339-②).
        assertEquals("변화 추이를 불러오지 못했어요. 잠시 후 다시 확인해 주세요.", trendEmptyCopy(0))
    }

    // ── #389 문제 1: 기준 경계 캡션 ──────────────────────────────────────

    @Test
    fun baseline_caption_only_when_boundary_exists() {
        // 경계 없는 추이(같은 기준) → 캡션 없음(불필요한 설명을 띄우지 않는다).
        val same = buildScoreTrend(
            listOf(item("2026-07-01", 70), item("2026-07-08", 72)),
        )
        assertNull(trendBaselineCaption(splitByBaseline(same)))

        // 모델이 바뀐 지점이 있으면 → 왜 끊겼는지 설명하는 캡션.
        val boundary = buildScoreTrend(
            listOf(
                item("2026-07-01", 70),
                item("2026-07-08", 62, status = "model_changed"),
            ),
        )
        val caption = trendBaselineCaption(splitByBaseline(boundary))
        assertNotNull(caption)
        assertTrue(caption!!.contains("계산 기준"))
        // 원인을 단정하지 않는다 — API 가 사유를 안 주므로(#389 B 후속) 활동·허리둘레 등을 지목하지 않는다.
        assertFalse(caption.contains("걷기"))
        assertFalse(caption.contains("허리"))
    }
}
