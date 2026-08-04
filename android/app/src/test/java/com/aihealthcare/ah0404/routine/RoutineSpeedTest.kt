package com.aihealthcare.ah0404.routine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 루틴(몸풀기·마무리) 배속별 콘텐츠 시간 계산 순수 로직 테스트(지영 리뷰).
 *   타이머 elapsedMs 는 '콘텐츠(동작 기준) 시간'이라 한 실시간 틱에 speed 배로 흐른다 → 단계가 speed 배 빨리 끝난다.
 *   영상·타이머·BGM 이 같은 배속으로 움직여 항상 동기(스펙 §4-4).
 */
class RoutineSpeedTest {

    @Test
    fun `콘텐츠 틱은 배속에 비례한다`() {
        assertEquals(50L, scaledContentTickMs(50, 1.0f))
        assertEquals(75L, scaledContentTickMs(50, 1.5f))
        assertEquals(62L, scaledContentTickMs(50, 1.25f)) // 62.5 → 62(내림)
        assertEquals(37L, scaledContentTickMs(50, 0.75f)) // 37.5 → 37(내림)
    }

    @Test
    fun `단계 실시간 길이는 배속에 반비례한다`() {
        // 240초 동작: 1.0배=240s, 1.5배=160s, 0.75배=320s
        assertEquals(240_000L, realStepDurationMs(240, 1.0f))
        assertEquals(160_000L, realStepDurationMs(240, 1.5f))
        assertEquals(320_000L, realStepDurationMs(240, 0.75f))
    }

    @Test
    fun `콘텐츠 시간이 단계 길이에 도달하는 실시간이 실시간 길이와 일치한다`() {
        // 60초(60_000ms) 단계를 1.5배로 재생: 50ms 틱마다 75ms 콘텐츠 → 800틱(40s) 근처에서 도달.
        val sec = 60
        val speed = 1.5f
        val targetContentMs = sec * 1000L
        var content = 0L
        var realMs = 0L
        while (content < targetContentMs) {
            content += scaledContentTickMs(50, speed)
            realMs += 50
        }
        // 실시간 길이(40s)와 ±1틱 이내로 일치해야 한다.
        assertEquals(realStepDurationMs(sec, speed).toFloat(), realMs.toFloat(), 50f)
    }
}
