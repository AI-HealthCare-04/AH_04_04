package com.aihealthcare.ah0404.mission

/**
 * ============================================================================
 *  ActiveTimeAccumulator : "실제 측정 중이던 전경 시간"만 누적 (#90 A-4b 2단계)
 * ============================================================================
 *
 *  왜 필요한가:
 *   경과 시간을 벽시계(start~now) 하나로 재면, 백그라운드/전화로 센서를 해제해
 *   걸음이 멈춘 동안에도 시간만 계속 늘어난다(걸음↔시간 정합 붕괴).
 *   이 누적기는 pause() 시점에 그때까지의 구간을 흡수해 시계를 **동결**하고,
 *   resume() 시 새 구간을 다시 연다. 즉 걸음을 세지 못하는 구간은 시간에서도 뺀다.
 *
 *  순수 로직(안드로이드 의존 없음) — clock 을 주입받아 JVM 단위테스트로 검증한다.
 *   시간 소스는 단조 증가하는 값이어야 한다(호출부는 SystemClock.elapsedRealtime()).
 *
 *  상태는 명시적 running 플래그로 관리한다(타임스탬프 0 을 sentinel 로 쓰지 않는다 — 값 0 과 충돌).
 * ============================================================================
 */
class ActiveTimeAccumulator(private val nowMs: () -> Long) {
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L
    private var running = false

    /** 새 측정 시작 — 누적값을 비우고 첫 구간을 연다. */
    fun start() {
        accumulatedMs = 0L
        segmentStartMs = nowMs()
        running = true
    }

    /** 일시정지 — 진행 중이면 현재 구간을 누적에 흡수하고 시계를 동결한다. 중복 호출은 무시. */
    fun pause() {
        if (!running) return
        accumulatedMs += nowMs() - segmentStartMs
        running = false
    }

    /** 재개 — 정지 상태면 누적값은 유지한 채 새 구간을 연다. 이미 진행 중이면 무시. */
    fun resume() {
        if (running) return
        segmentStartMs = nowMs()
        running = true
    }

    /** 종료 — 마지막 구간을 흡수해 값을 고정한다(pause 와 동일 동작, 의미 구분용). */
    fun stop() = pause()

    /** 지금까지 누적된 활성 측정 시간(ms). 진행 중이면 현재 구간을 더해 반환. */
    fun elapsedMs(): Long =
        accumulatedMs + if (running) nowMs() - segmentStartMs else 0L
}
