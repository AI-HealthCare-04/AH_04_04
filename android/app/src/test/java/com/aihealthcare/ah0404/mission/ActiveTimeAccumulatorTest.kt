package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ActiveTimeAccumulator(#90 2단계) — "실제 측정 중이던 전경 시간만 누적" 검증.
 * fake clock 으로 pause 시 동결·resume 시 이어감·백그라운드 구간 제외를 순수 JVM 에서 확인한다.
 */
class ActiveTimeAccumulatorTest {

    /** 테스트에서 시간을 임의로 전진시키는 가짜 단조 시계. */
    private class FakeClock(var now: Long = 0L) {
        val source: () -> Long = { now }
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun elapsed_progresses_while_running() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        clock.advance(5_000)
        assertEquals(5_000L, acc.elapsedMs())
    }

    @Test
    fun pause_freezes_elapsed() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        clock.advance(3_000)
        acc.pause()
        // 백그라운드(전화 등)로 pause 된 동안 시간이 흘러도 경과는 늘지 않아야 한다.
        clock.advance(10_000)
        assertEquals(3_000L, acc.elapsedMs())
    }

    @Test
    fun resume_continues_and_excludes_paused_gap() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        clock.advance(3_000)   // 활성 3s
        acc.pause()
        clock.advance(10_000)  // 백그라운드 10s (제외돼야 함)
        acc.resume()
        clock.advance(2_000)   // 활성 2s
        assertEquals(5_000L, acc.elapsedMs()) // 3 + 2, 백그라운드 10 제외
    }

    @Test
    fun repeated_pause_resume_accumulates_only_active() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        repeat(3) {
            clock.advance(1_000) // 활성 1s
            acc.pause()
            clock.advance(5_000) // 비활성 5s
            acc.resume()
        }
        clock.advance(1_000)     // 마지막 활성 1s
        assertEquals(4_000L, acc.elapsedMs()) // 활성 1s × 4, 비활성 전부 제외
    }

    @Test
    fun stop_fixes_value_regardless_of_later_time() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        clock.advance(7_000)
        acc.stop()
        clock.advance(9_999)
        assertEquals(7_000L, acc.elapsedMs()) // 종료 후엔 시간이 흘러도 고정
    }

    @Test
    fun start_resets_previous_accumulation() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        clock.advance(4_000)
        acc.stop()
        // 새 세션 시작 → 이전 누적은 비워진다.
        acc.start()
        clock.advance(1_000)
        assertEquals(1_000L, acc.elapsedMs())
    }

    @Test
    fun idempotent_pause_and_resume() {
        val clock = FakeClock()
        val acc = ActiveTimeAccumulator(clock.source)
        acc.start()
        clock.advance(2_000)
        acc.pause()
        acc.pause() // 중복 pause 무시
        clock.advance(3_000)
        acc.resume()
        acc.resume() // 중복 resume 무시(구간 재시작 아님)
        clock.advance(1_000)
        assertEquals(3_000L, acc.elapsedMs()) // 2 + 1
    }
}
