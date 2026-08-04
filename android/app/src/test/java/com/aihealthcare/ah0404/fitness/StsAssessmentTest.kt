package com.aihealthcare.ah0404.fitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5STS 기초체력 평가 순수 로직 — 회당 발화·종료 판정·시간 표기·결과 문구. */
class StsAssessmentTest {

    @Test
    fun count_words_are_reactive_within_range_only() {
        assertEquals("하나", stsCountWord(1))
        assertEquals("둘", stsCountWord(2))
        assertEquals("셋", stsCountWord(3))
        assertEquals("넷", stsCountWord(4))
        assertEquals("다섯", stsCountWord(5))
        // 범위 밖은 발화하지 않는다(빈 문자열).
        assertEquals("", stsCountWord(0))
        assertEquals("", stsCountWord(6))
    }

    @Test
    fun complete_only_at_target_reps() {
        assertFalse(stsIsComplete(4))
        assertTrue(stsIsComplete(5))
        assertTrue(stsIsComplete(6)) // 방어적으로 초과도 완료
        assertEquals(5, STS_TARGET_REPS)
    }

    @Test
    fun format_seconds_to_one_decimal() {
        assertEquals("12.4", formatStsSeconds(12.44))
        assertEquals("12.5", formatStsSeconds(12.45)) // 반올림
        assertEquals("0.0", formatStsSeconds(0.0))
        assertEquals("9.0", formatStsSeconds(9.0))
    }

    @Test
    fun result_speech_reads_number_without_verdict() {
        val speech = stsResultSpeech(12.4)
        assertTrue("결과는 시간만 읽는다", speech.contains("12.4초"))
        // 비의료 가드레일: 판정 표현이 들어가면 안 된다.
        listOf("저하", "정상", "진단", "근감소증").forEach {
            assertFalse("결과 발화에 판정 표현 '$it' 금지", speech.contains(it))
        }
    }

    @Test
    fun countdown_starts_measurement_on_last_word() {
        // 시작 카운트다운의 마지막 단어에서 스톱워치를 start 한다(발화 대본 계약).
        assertEquals("시작하세요!", STS_COUNTDOWN_WORDS.last())
        assertEquals(4, STS_COUNTDOWN_WORDS.size)
    }

    @Test
    fun clock_starts_only_at_last_countdown_word() {
        // 스톱워치는 마지막 "시작하세요!"(index 3)에서만 start — 그 전 단어 뒤에는 delay(대기)만(리뷰 #221-1).
        assertFalse(stsClockStartsAtCountdownIndex(0))
        assertFalse(stsClockStartsAtCountdownIndex(1))
        assertFalse(stsClockStartsAtCountdownIndex(2))
        assertTrue(stsClockStartsAtCountdownIndex(3))
    }
}
