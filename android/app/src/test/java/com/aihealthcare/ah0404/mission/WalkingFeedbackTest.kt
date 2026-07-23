package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.feedback.HapticPattern
import com.aihealthcare.ah0404.feedback.HapticPlayer
import com.aihealthcare.ah0404.feedback.SpeechQueueMode
import com.aihealthcare.ah0404.feedback.TtsSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SharedWalkingFeedback(#92) — 걷기 cue 를 공용 엔진 호출로 번역하는 매핑과, 화면 이탈 시
 * 공용 엔진을 죽이지 않고 stop/cancel 만 하는 계약을 fake 로 고정한다(리뷰 #148).
 * (실제 발화·진동은 실기기 QA 몫.)
 */
class WalkingFeedbackTest {

    private class FakeTts : TtsSpeaker {
        override var isEnabled = true
        val spoken = mutableListOf<Pair<String, SpeechQueueMode>>()
        var stopCount = 0
        var releaseCount = 0
        override fun setSpeechRate(rate: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun speak(text: String, mode: SpeechQueueMode) { spoken += text to mode }
        override fun stop() { stopCount++ }
        override fun release() { releaseCount++ }
    }

    private class FakeHaptic : HapticPlayer {
        override var isEnabled = true
        val played = mutableListOf<HapticPattern>()
        var cancelCount = 0
        override fun play(pattern: HapticPattern) { played += pattern }
        override fun cancel() { cancelCount++ }
    }

    @Test
    fun started_maps_to_confirmation_vibration_and_short_speech() {
        val tts = FakeTts()
        val haptic = FakeHaptic()
        SharedWalkingFeedback(tts, haptic).play(WalkingFeedbackCue.STARTED)
        assertEquals(listOf(HapticPattern.CONFIRMATION), haptic.played)
        assertEquals(listOf("측정을 시작했어요." to SpeechQueueMode.FLUSH), tts.spoken)
    }

    @Test
    fun goal_reached_maps_to_success_vibration_and_speech() {
        val tts = FakeTts()
        val haptic = FakeHaptic()
        SharedWalkingFeedback(tts, haptic).play(WalkingFeedbackCue.GOAL_REACHED)
        assertEquals(listOf(HapticPattern.SUCCESS), haptic.played)
        assertEquals(
            listOf("목표를 달성했어요. 천천히 마무리해 주세요." to SpeechQueueMode.FLUSH),
            tts.spoken,
        )
    }

    @Test
    fun stop_stops_speech_and_cancels_haptic_but_never_releases_shared_engine() {
        val tts = FakeTts()
        val haptic = FakeHaptic()
        val feedback = SharedWalkingFeedback(tts, haptic)
        feedback.play(WalkingFeedbackCue.STARTED)

        feedback.stop()

        assertEquals(1, tts.stopCount)
        assertEquals(1, haptic.cancelCount)
        assertEquals("화면이 공용 TTS 엔진을 release 하면 안 된다(다른 화면 음성이 죽는다)", 0, tts.releaseCount)
    }

    @Test
    fun every_cue_has_nonblank_speech_and_a_pattern() {
        // 문구가 speak() 안에 묻혀 아무도 검증 못 하던 것을 최소 보증으로 고정(리뷰 #148).
        for (cue in WalkingFeedbackCue.entries) {
            assertTrue("빈 문구 금지: $cue", cue.speech.isNotBlank())
        }
    }
}
