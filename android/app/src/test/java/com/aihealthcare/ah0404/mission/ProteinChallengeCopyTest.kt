package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#225 리뷰] 단백질 저장 화면의 표시 규칙(순수 함수) 고정.
 *  - 재저장에 '포인트를 받았어요'를 반복하지 않는다(P1: earned_points 는 신규 지급이 아니라 오늘 반영값).
 *  - 빈 기록(0개)은 '오늘 안 먹었어요' 저장으로 안내한다(P2: #224 계약과 일치).
 */
class ProteinChallengeCopyTest {

    // ── wasCountedFromTodayLog: 진입 시점 '이미 달성 상태' 추정 ──

    @Test
    fun `today_log 가 없으면 미달성으로 본다`() {
        assertFalse(wasCountedFromTodayLog(null))
        assertFalse(wasCountedFromTodayLog(emptyList()))
    }

    @Test
    fun `유효 카테고리 1종 이상이면 이미 달성 상태다(팀 결정 - 목표 1종)`() {
        assertTrue(wasCountedFromTodayLog(listOf("meat")))
        assertTrue(wasCountedFromTodayLog(listOf("meat", "fish", "egg")))
    }

    @Test
    fun `미지의 id 만으로는 달성으로 보지 않는다`() {
        // 서버 판정(서로 다른 유효 카테고리 수)과 같은 기준 — 오타/미지 값으로 과대평가하지 않는다.
        assertFalse(wasCountedFromTodayLog(listOf("unknown_id", "banana")))
    }

    // ── proteinResultMessage: 신규 달성/재저장/빈 기록 구분 ──

    @Test
    fun `첫 달성에만 받았어요를 안내한다`() {
        val first = ProteinSaveState.Saved(countedForDaily = true, earnedPoints = 10, newlyCounted = true, savedCount = 3)
        assertTrue(proteinResultMessage(first).contains("받았어요"))
    }

    @Test
    fun `재저장은 이미 반영으로 안내한다(추가 지급 단정 금지)`() {
        val resave = ProteinSaveState.Saved(countedForDaily = true, earnedPoints = 10, newlyCounted = false, savedCount = 4)
        val msg = proteinResultMessage(resave)
        assertFalse(msg.contains("받았어요"))
        assertTrue(msg.contains("이미 반영"))
    }

    @Test
    fun `빈 기록은 안 먹었어요 저장으로 안내한다`() {
        // 계약(#227): 0종만 미완료다. 1종 이상 + 미완료 조합은 정상 경로에서 존재하지 않는다.
        val empty = ProteinSaveState.Saved(countedForDaily = false, earnedPoints = 0, newlyCounted = false, savedCount = 0)
        assertTrue(proteinResultMessage(empty).contains("안 드신 것으로 저장"))
    }

    // ── proteinSaveButtonLabel: 0개 저장 경로 노출 ──

    @Test
    fun `버튼 문구 - 0개는 안 먹었어요, 목표(1종) 이상은 축하를 표시한다`() {
        assertEquals("안 먹었어요로 저장하기", proteinSaveButtonLabel(0))
        assertEquals("목표 달성! 저장하기", proteinSaveButtonLabel(PROTEIN_DAILY_GOAL))
        assertEquals("목표 달성! 저장하기", proteinSaveButtonLabel(PROTEIN_DAILY_GOAL + 1))
    }
}
