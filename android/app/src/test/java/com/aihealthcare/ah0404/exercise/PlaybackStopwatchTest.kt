package com.aihealthcare.ah0404.exercise

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PlaybackStopwatch — 실제 재생(isPlaying) 구간만 합산하는지 검증(#234 리뷰 P1-A).
 *   핵심: 일시정지·버퍼링·백그라운드 정지(isPlaying=false) 시간은 총 재생 시간에서 빠져야 한다.
 *   시각(now, ms)은 테스트가 직접 주입한다(SystemClock 대체).
 */
class PlaybackStopwatchTest {

    @Test
    fun `재생 구간만 합산하고 일시정지·버퍼 시간은 뺀다`() {
        val sw = PlaybackStopwatch()
        // 0s 재생 시작 → 60s 일시정지(60s 재생) → 120s 재개(60s 멈춤은 제외) → 150s 이탈(30s 재생)
        sw.onIsPlayingChanged(isPlaying = true, now = 0L)
        sw.onIsPlayingChanged(isPlaying = false, now = 60_000L)   // 여기까지 60s 재생
        sw.onIsPlayingChanged(isPlaying = true, now = 120_000L)   // 60s 멈춤(버퍼/일시정지) — 제외
        val minutes = sw.elapsedMinutes(now = 150_000L)           // 다시 30s 재생 후 이탈

        assertEquals("재생 60s + 30s = 90s = 1.5분(멈춘 60s 제외)", 1.5f, minutes, 0.0001f)
    }

    @Test
    fun `한 번도 재생되지 않으면 0분`() {
        val sw = PlaybackStopwatch()
        assertEquals("재생 이벤트가 없으면 0분(호출부 0분 가드로 전송 안 됨)", 0f, sw.elapsedMinutes(now = 99_000L), 0.0001f)
    }

    @Test
    fun `재생 중 이탈하면 열린 구간을 닫아 합산한다`() {
        val sw = PlaybackStopwatch()
        sw.onIsPlayingChanged(isPlaying = true, now = 10_000L)
        // 정지 이벤트 없이 바로 이탈 — elapsedMinutes 가 열린 구간을 닫아야 한다.
        assertEquals("10s~130s = 120s = 2분", 2f, sw.elapsedMinutes(now = 130_000L), 0.0001f)
    }

    @Test
    fun `중복 재생 이벤트는 구간을 새로 열지 않는다`() {
        val sw = PlaybackStopwatch()
        sw.onIsPlayingChanged(isPlaying = true, now = 0L)
        sw.onIsPlayingChanged(isPlaying = true, now = 30_000L) // 중복 true — 시작점을 30s 로 밀지 않는다
        val minutes = sw.elapsedMinutes(now = 60_000L)

        assertEquals("0s~60s = 60s = 1분(중복 true 무시)", 1f, minutes, 0.0001f)
    }
}
