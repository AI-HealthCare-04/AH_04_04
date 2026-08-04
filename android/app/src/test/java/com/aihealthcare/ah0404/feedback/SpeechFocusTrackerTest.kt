package com.aihealthcare.ah0404.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오디오 포커스 정산 규칙 검증.
 *
 *  정산 경로가 완료·오류·중단·FLUSH·stop 으로 여러 갈래라 눈으로 맞추기 어렵고,
 *  틀리면 **배경음이 낮아진 채 영영 복구되지 않는다.** 실기기에서만 드러나는 유형이라
 *  순수 로직으로 분리해 고정한다.
 */
class SpeechFocusTrackerTest {

    private var acquireCount = 0
    private var releaseCount = 0
    private var grant = true

    private fun tracker() = SpeechFocusTracker(
        acquireFocus = { acquireCount++; grant },
        releaseFocus = { releaseCount++ },
    )

    @Test
    fun `A 발화 중 FLUSH B — 포커스 요청 1회, B 완료 후 반납 1회`() {
        val tracker = tracker()

        tracker.onSpeakStart("A", flush = true)
        tracker.onSpeakStart("B", flush = true) // A 진행 중 끼어듦

        // 이미 보유 중이므로 재요청하지 않는다. 재요청하면 이전 요청 객체를 잃어 반납 불가가 된다.
        assertEquals(1, acquireCount)
        assertEquals(0, releaseCount)

        tracker.onSettled("B")

        assertEquals(1, releaseCount)
        assertFalse(tracker.isFocusHeld)
    }

    @Test
    fun `FLUSH 로 끊긴 A 의 늦은 콜백은 B 의 포커스를 뺏지 않는다`() {
        val tracker = tracker()

        tracker.onSpeakStart("A", flush = true)
        tracker.onSpeakStart("B", flush = true)

        // 플랫폼이 끊긴 A 의 onStop 을 뒤늦게 보낸다.
        tracker.onSettled("A")

        assertEquals(0, releaseCount)
        assertTrue(tracker.isFocusHeld)
        assertEquals(1, tracker.outstandingCount)
    }

    @Test
    fun `연속 ADD 발화는 요청 1회, 마지막 완료에서만 반납한다`() {
        val tracker = tracker()

        tracker.onSpeakStart("A", flush = true)
        tracker.onSpeakStart("B", flush = false)
        tracker.onSpeakStart("C", flush = false)

        assertEquals(1, acquireCount)

        tracker.onSettled("A")
        tracker.onSettled("B")
        assertEquals(0, releaseCount) // 아직 C 가 남아 배경음이 오르내리지 않는다

        tracker.onSettled("C")
        assertEquals(1, releaseCount)
    }

    @Test
    fun `reset 은 미완료 발화를 포기하고 반납한다 — 중복 호출은 반납하지 않는다`() {
        val tracker = tracker()
        tracker.onSpeakStart("A", flush = true)

        tracker.reset()
        assertEquals(1, releaseCount)

        tracker.reset()
        assertEquals(1, releaseCount)
    }

    @Test
    fun `reset 이후 늦게 도착한 콜백은 무시된다`() {
        val tracker = tracker()
        tracker.onSpeakStart("A", flush = true)
        tracker.reset()

        tracker.onSettled("A")

        assertEquals(1, releaseCount)
    }

    @Test
    fun `포커스 획득에 실패하면 반납을 시도하지 않는다`() {
        grant = false
        val tracker = tracker()

        tracker.onSpeakStart("A", flush = true)
        assertFalse(tracker.isFocusHeld)

        tracker.onSettled("A")
        assertEquals(0, releaseCount)
    }

    @Test
    fun `모르는 id 와 null 은 무시된다`() {
        val tracker = tracker()
        tracker.onSpeakStart("A", flush = true)

        tracker.onSettled("존재하지 않는 id")
        tracker.onSettled(null)

        assertEquals(0, releaseCount)
        assertEquals(1, tracker.outstandingCount)
    }

    @Test
    fun `반납 후 새 발화는 포커스를 다시 요청한다`() {
        val tracker = tracker()

        tracker.onSpeakStart("A", flush = true)
        tracker.onSettled("A")
        assertEquals(1, acquireCount)

        tracker.onSpeakStart("B", flush = true)

        assertEquals(2, acquireCount)
        assertTrue(tracker.isFocusHeld)
    }
}
