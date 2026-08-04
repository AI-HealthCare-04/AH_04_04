package com.aihealthcare.ah0404.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 초기화 전 발화 보관 규칙 검증.
 *
 *  두 가지를 동시에 지켜야 한다.
 *   1. 초기화가 비동기라 준비 전 speak() 가 들어오는데, 버리면 진입 직후 안내가 사라진다.
 *   2. 그렇다고 호출부의 큐 정책을 바꾸면 안 된다 — FLUSH 는 "대기 중인 낡은 안내를 폐기하고
 *      이것으로 대체하라"는 뜻이므로, 보관 단계에서도 그대로 지켜야 한다.
 */
class PendingSpeechQueueTest {

    @Test
    fun `ADD 로 이어진 발화는 넣은 순서대로 보관된다`() {
        val queue = PendingSpeechQueue()

        queue.offer("첫 번째", SpeechQueueMode.FLUSH)
        queue.offer("두 번째", SpeechQueueMode.ADD)

        val drained = queue.drain()

        assertEquals(listOf("첫 번째", "두 번째"), drained.map { it.text })
    }

    @Test
    fun `FLUSH 는 대기 중인 안내를 폐기한다 — 호출부 정책을 그대로 지킨다`() {
        val queue = PendingSpeechQueue()

        queue.offer("낡은 안내", SpeechQueueMode.FLUSH)
        queue.offer("이걸로 대체", SpeechQueueMode.FLUSH)

        val drained = queue.drain()

        // 둘 다 재생하려고 뒤 항목을 ADD 로 바꾸면, 호출부가 버리라고 한 안내가 되살아난다.
        assertEquals(listOf("이걸로 대체"), drained.map { it.text })
        assertEquals(SpeechQueueMode.FLUSH, drained.single().mode)
    }

    @Test
    fun `FLUSH 는 앞의 ADD 묶음까지 통째로 폐기한다`() {
        val queue = PendingSpeechQueue()

        queue.offer("A", SpeechQueueMode.FLUSH)
        queue.offer("B", SpeechQueueMode.ADD)
        queue.offer("C", SpeechQueueMode.ADD)
        queue.offer("새 안내", SpeechQueueMode.FLUSH)

        assertEquals(listOf("새 안내"), queue.drain().map { it.text })
    }

    @Test
    fun `drain 은 정책을 재해석하지 않는다`() {
        val queue = PendingSpeechQueue()

        queue.offer("먼저", SpeechQueueMode.FLUSH)
        queue.offer("이어서", SpeechQueueMode.ADD)

        val drained = queue.drain()

        assertEquals(SpeechQueueMode.FLUSH, drained[0].mode)
        assertEquals(SpeechQueueMode.ADD, drained[1].mode)
    }

    @Test
    fun `꺼내고 나면 비워진다 — 재생이 두 번 되지 않는다`() {
        val queue = PendingSpeechQueue()
        queue.offer("한 번만", SpeechQueueMode.FLUSH)

        assertEquals(1, queue.drain().size)
        assertTrue(queue.drain().isEmpty())
        assertEquals(0, queue.size)
    }

    @Test
    fun `ADD 가 상한을 넘으면 가장 오래된 것을 버린다`() {
        val queue = PendingSpeechQueue(capacity = 2)

        queue.offer("가장 오래된 것", SpeechQueueMode.ADD)
        queue.offer("중간 것", SpeechQueueMode.ADD)
        queue.offer("가장 최근 것", SpeechQueueMode.ADD)

        val drained = queue.drain()

        assertEquals(2, drained.size)
        assertEquals(listOf("중간 것", "가장 최근 것"), drained.map { it.text })
    }

    @Test
    fun `초기화 실패·비활성화 시 보관분을 버린다`() {
        val queue = PendingSpeechQueue()
        queue.offer("재생되지 않아야 함", SpeechQueueMode.FLUSH)

        queue.clear()

        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `보관분이 없으면 빈 목록을 준다`() {
        assertTrue(PendingSpeechQueue().drain().isEmpty())
    }

    @Test
    fun `capacity 가 0 이면 아무것도 보관하지 않는다`() {
        val queue = PendingSpeechQueue(capacity = 0)

        queue.offer("버려짐", SpeechQueueMode.FLUSH)

        assertEquals(0, queue.size)
        assertTrue(queue.drain().isEmpty())
    }
}
