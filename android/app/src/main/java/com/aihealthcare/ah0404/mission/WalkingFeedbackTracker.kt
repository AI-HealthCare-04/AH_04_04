package com.aihealthcare.ah0404.mission

/** 화면을 보지 않아도 진행을 알 수 있게 하는 피드백 신호(#92 A-5). */
enum class WalkingFeedbackCue {
    /** 보행이 확정돼 측정이 시작된 순간. */
    STARTED,

    /** 오늘 목표(분 또는 걸음)에 도달한 순간. 단위 판정은 호출부가 하고 여기엔 도달 여부만 온다. */
    GOAL_REACHED,
}

/**
 * ============================================================================
 *  WalkingFeedbackTracker : "언제 진동/음성 신호를 보낼지" 결정하는 순수 로직 (#92 A-5)
 * ============================================================================
 *
 *  낙상 위험 때문에 고령 사용자가 걷는 동안 화면을 응시하지 않게 하려면, 꼭 필요한 몇
 *  순간에만 진동·음성으로 알려야 한다. 이 트래커는 **공유 세션 상태(#90)에서 파생**한
 *  값(확정 여부·목표 도달 여부)을 받아, 새로 발생한 신호만 **1회씩** 방출한다(중복 방지).
 *
 *  ⚠️ 목표 판정 자체(분 누적 vs 걸음)는 여기서 하지 않는다 — 걷기 목표는 '당일 누적 시간(분)'이라
 *  세션 시작 시 받아온 오늘 누적분 + 이번 세션 경과분으로 호출부가 계산해 goalReached 로 넘긴다.
 *  안드로이드 의존이 없어(진동·TTS는 실행기 몫) JVM 단위테스트로 신호 시점을 검증한다.
 * ============================================================================
 */
class WalkingFeedbackTracker {
    private var started = false
    private var goalReached = false

    /**
     * 세션 상태가 갱신될 때마다 호출. 이번 호출에서 **새로 발생한** 신호만 반환한다.
     *
     * @param confirmed 보행 확정 여부(ui.confirmed).
     * @param goalReached 오늘 목표(분/걸음)에 도달했는가. 호출부가 단위에 맞게 계산해 넘긴다.
     *   한 번 true 로 신호가 나가면 같은 세션에선 다시 나가지 않는다(중복 방지).
     */
    fun onUpdate(confirmed: Boolean, goalReached: Boolean): List<WalkingFeedbackCue> {
        val cues = mutableListOf<WalkingFeedbackCue>()
        if (confirmed && !started) {
            started = true
            cues += WalkingFeedbackCue.STARTED
        }
        if (goalReached && !this.goalReached) {
            this.goalReached = true
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

/**
 * 오늘 목표 도달 여부(측정 중 실시간 판정). 순수 함수라 JVM 단위테스트로 경계를 검증한다.
 *
 * 걷기 목표는 서버 계약상 **'당일 누적 시간(분)'** 이 기본이다(easy 20·normal 30·hard 40, 걸음 수는 표시 전용).
 *   → 측정 중 누적분 = **세션 시작 시 받아온 오늘 누적분(priorDailyMin, 이번 세션 제외)** + 이번 세션 경과분.
 *   나눠 걷기(여러 세션)에서도 서버의 당일 누적 판정과 같은 순간에 도달 신호가 나가게 한다.
 *
 * ⚠️ 서버의 최초 달성 조건 `prior_min < target <= total_min` 을 그대로 따른다 — **이번 세션에서 목표를 처음
 *   넘긴 경우에만** true. 이미 오늘 목표를 채운(priorDailyMin ≥ target) 사용자가 새 세션을 시작해도 재발화하지
 *   않는다(중복 진동·음성 방지). 단위가 '걸음'인 미션만 걸음으로 판정하고, 그 외 단위는 신호를 내지 않는다.
 *
 * @param targetUnit 미션 목표 단위("minutes" | "steps" | …)
 * @param targetValue 목표값(분 또는 걸음)
 * @param priorDailyMin 세션 시작 시 받아온 오늘 누적분(이번 세션 제외). 받아오지 못했으면 0.0(단일 세션처럼 취급).
 * @param elapsedSec 이번 세션 경과 시간(초)
 * @param steps 이번 세션 걸음 수(걸음 단위 미션용)
 */
fun walkingGoalReached(
    targetUnit: String,
    targetValue: Int,
    priorDailyMin: Double,
    elapsedSec: Int,
    steps: Int,
): Boolean = when (targetUnit) {
    // prior < target <= total : 이미 목표를 넘긴 상태(prior ≥ target)면 이번 세션은 '첫 교차'가 아니라 미발화.
    "minutes" -> targetValue > 0 && priorDailyMin < targetValue && priorDailyMin + elapsedSec / 60.0 >= targetValue
    "steps" -> targetValue > 0 && steps >= targetValue
    else -> false
}
