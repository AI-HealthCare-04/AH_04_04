package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.feedback.HapticPattern
import com.aihealthcare.ah0404.feedback.HapticPlayer
import com.aihealthcare.ah0404.feedback.SpeechQueueMode
import com.aihealthcare.ah0404.feedback.TtsSpeaker

/**
 * 걷기 피드백 실행기(#92 A-5) — 걷기 신호(cue)를 앱 공용 피드백 엔진의 "의미" 호출로 번역한다.
 *
 *  TTS·진동 엔진 자체는 소유하지 않는다. 생성·해제는 Application 이 소유하는 공용 컴포넌트
 *  (#149 [com.aihealthcare.ah0404.feedback.AppFeedback])의 몫이고, 이 클래스는 주입받아 쓰기만 한다.
 *  화면은 이 인터페이스에만 의존해, 테스트에서는 fake 로 대체하고 실기기에선 공용 엔진을 주입한다.
 */
interface WalkingFeedback {
    /** 신호를 진동 + 음성으로 낸다. */
    fun play(cue: WalkingFeedbackCue)

    /**
     * 이 화면의 진행 중인 발화·진동만 중단한다(화면 이탈 시). **공용 엔진을 release 하지 않는다** —
     * release 는 Application 소유라 화면에서 부르면 다른 화면의 음성까지 죽는다(#149 계약).
     */
    fun stop()
}

/**
 * 공용 [TtsSpeaker]·[HapticPlayer] 를 주입받아 걷기 cue 를 재생하는 구현.
 *
 *  - STARTED      → 진동 [HapticPattern.CONFIRMATION](시작 인정) + 음성
 *  - GOAL_REACHED → 진동 [HapticPattern.SUCCESS](완료 성취) + 음성
 *
 *  발화는 [SpeechQueueMode.FLUSH] — 상태 변화 통지라 "지금 것이 더 중요"하다.
 */
class SharedWalkingFeedback(
    private val tts: TtsSpeaker,
    private val haptic: HapticPlayer,
) : WalkingFeedback {

    override fun play(cue: WalkingFeedbackCue) {
        haptic.play(cue.haptic)
        tts.speak(cue.speech, SpeechQueueMode.FLUSH)
    }

    override fun stop() {
        tts.stop()
        haptic.cancel()
    }
}

/** 걷기 cue → 공용 진동 패턴(의미) 매핑. 문구([speech])와 함께 #148 이 소유한다(리뷰). */
internal val WalkingFeedbackCue.haptic: HapticPattern
    get() = when (this) {
        WalkingFeedbackCue.STARTED -> HapticPattern.CONFIRMATION
        WalkingFeedbackCue.GOAL_REACHED -> HapticPattern.SUCCESS
    }
