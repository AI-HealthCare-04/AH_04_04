package com.aihealthcare.ah0404.feedback

/**
 * 미완료 발화를 추적하고, 그에 맞춰 **오디오 포커스 보유 여부**를 관리한다.
 *
 *  플랫폼 호출(`requestAudioFocus`/`abandonAudioFocusRequest`)을 람다로 주입받아
 *  안드로이드 의존 없이 JVM 단위테스트로 검증한다. 이 정산은 경로가 여러 개라
 *  (완료·오류·중단·FLUSH·stop) 눈으로 맞추기 어렵고, 틀리면 **배경음이 영영 낮아진 채로
 *  남거나** 반대로 발화 중에 포커스를 놓치는 문제가 생긴다.
 *
 *  ## 핵심 규칙
 *
 *  **포커스는 "미보유 → 보유" 전환에서만 요청한다.**
 *   발화 A 진행 중 FLUSH B 가 들어오면 A 의 정산을 포기하지만(콜백이 늦게 와도 무시),
 *   **포커스는 이미 갖고 있으므로 다시 요청하지 않는다.** 다시 요청하면 플랫폼이 새 요청 객체를
 *   만들고 이전 객체 참조를 잃어, 나중에 그 요청은 영영 반납되지 않는다.
 *
 *  **id 집합으로 추적한다(카운터가 아니라).**
 *   [reset] 이나 FLUSH 뒤 늦게 도착한 이전 발화 콜백이 새 발화의 정산을 깎지 않도록,
 *   집합에 없는 id 는 그냥 무시한다.
 */
internal class SpeechFocusTracker(
    private val acquireFocus: () -> Boolean,
    private val releaseFocus: () -> Unit,
) {

    private val outstanding = LinkedHashSet<String>()
    private var focusHeld = false

    val outstandingCount: Int get() = outstanding.size
    val isFocusHeld: Boolean get() = focusHeld

    /**
     * 발화를 시작했다.
     *
     * @param flush 호출부가 [SpeechQueueMode.FLUSH] 로 요청했는가.
     *   `true` 면 진행 중이던 발화는 플랫폼이 끊으므로 정산 대상에서 제외한다.
     */
    fun onSpeakStart(utteranceId: String, flush: Boolean) {
        if (flush) outstanding.clear()
        outstanding += utteranceId
        // 이미 보유 중이면 재요청하지 않는다 — 기존 요청 객체를 잃지 않기 위해.
        if (!focusHeld) focusHeld = acquireFocus()
    }

    /** 완료·오류·중단 통지. 모르는 id(늦게 도착한 콜백)는 무시한다. */
    fun onSettled(utteranceId: String?) {
        if (utteranceId == null) return
        if (!outstanding.remove(utteranceId)) return
        if (outstanding.isEmpty()) releaseHeldFocus()
    }

    /** `stop()`·`release()` — 미완료 발화를 모두 포기하고 포커스를 반납한다. 중복 호출 안전. */
    fun reset() {
        outstanding.clear()
        releaseHeldFocus()
    }

    private fun releaseHeldFocus() {
        if (!focusHeld) return
        releaseFocus()
        focusHeld = false
    }
}
