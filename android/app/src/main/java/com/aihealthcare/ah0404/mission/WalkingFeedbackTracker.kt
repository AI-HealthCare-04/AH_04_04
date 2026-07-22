package com.aihealthcare.ah0404.mission

/** 화면을 보지 않아도 진행을 알 수 있게 하는 피드백 신호(#92 A-5). */
enum class WalkingFeedbackCue {
    /** 보행이 확정돼 측정이 시작된 순간. */
    STARTED,

    /** 목표 걸음 수에 도달한 순간. */
    GOAL_REACHED,
}

/**
 * ============================================================================
 *  WalkingFeedbackTracker : "언제 진동/음성 신호를 보낼지" 결정하는 순수 로직 (#92 A-5)
 * ============================================================================
 *
 *  낙상 위험 때문에 고령 사용자가 걷는 동안 화면을 응시하지 않게 하려면, 꼭 필요한 몇
 *  순간에만 진동·음성으로 알려야 한다. 이 트래커는 **공유 세션 상태(#90)에서 파생**한
 *  값(확정 여부·걸음 수·목표)을 받아, 새로 발생한 신호만 **1회씩** 방출한다(중복 방지).
 *
 *  안드로이드 의존이 없어(진동·TTS는 실행기 몫) JVM 단위테스트로 신호 시점을 검증한다.
 *  별도 걸음 산정을 하지 않는다 — 입력은 전부 세션 상태에서 온다(#92 완료조건).
 * ============================================================================
 */
class WalkingFeedbackTracker {
    private var started = false
    private var goalReached = false

    /**
     * 세션 상태가 갱신될 때마다 호출. 이번 호출에서 **새로 발생한** 신호만 반환한다.
     *
     * @param confirmed 보행 확정 여부(ui.confirmed).
     * @param steps 누적 걸음 수(ui.steps).
     * @param goal 목표 걸음 수. null 이거나 0 이하면 목표 신호를 내지 않는다(단위가 걸음이 아닐 때).
     */
    fun onUpdate(confirmed: Boolean, steps: Int, goal: Int?): List<WalkingFeedbackCue> {
        val cues = mutableListOf<WalkingFeedbackCue>()
        if (confirmed && !started) {
            started = true
            cues += WalkingFeedbackCue.STARTED
        }
        if (goal != null && goal > 0 && steps >= goal && !goalReached) {
            goalReached = true
            cues += WalkingFeedbackCue.GOAL_REACHED
        }
        return cues
    }

    /** 새 세션을 위해 신호 발생 이력을 초기화한다(동일 화면 재사용 대비). */
    fun reset() {
        started = false
        goalReached = false
    }
}
