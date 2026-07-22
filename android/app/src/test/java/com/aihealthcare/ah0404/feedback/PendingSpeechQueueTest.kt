package com.aihealthcare.ah0404.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 초기화 전 발화 보관 규칙 검증.
 *
 *  TextToSpeech 초기화는 비동기라 준비 전 speak() 가 들어올 수 있다. 그걸 버리면
 *  화면 진입 직후 안내가 통째로 사라지므로, 보관 후 재생하는 것이 이 큐의 존재 이유다.
 */
class PendingSpeechQueueTest {

    @Test
    fun `보관한 발화를 넣은 순서대로 꺼낸다`() {
        val queue = PendingSpeechQueue()

        queue.offer("첫 번째", SpeechQueueMode.FLUSH)
        queue.offer("두 번째", SpeechQueueMode.ADD)

        val drained = queue.drain()

        assertEquals(listOf("첫 번째", "두 번째"), drained.map { it.text })
    }

    @Test
    fun `두 번째 이후 발화는 ADD 로 바뀐다 — FLUSH 가 앞 발화를 끊지 않도록`() {
        val queue = PendingSpeechQueue()

        // 둘 다 FLUSH 로 들어와도, 그대로 재생하면 두 번째가 첫 번째를 끊어 하나만 들린다.
        queue.offer("먼저 말할 것", SpeechQueueMode.FLUSH)
        queue.offer("이어서 말할 것", SpeechQueueMode.FLUSH)

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
    fun `상한을 넘으면 가장 오래된 것을 버린다 — 안내는 최신이 유효하다`() {
        val queue = PendingSpeechQueue(capacity = 2)

        queue.offer("가장 오래된 것", SpeechQueueMode.FLUSH)
        queue.offer("중간 것", SpeechQueueMode.FLUSH)
        queue.offer("가장 최근 것", SpeechQueueMode.FLUSH)

        val drained = queue.drain()

        assertEquals(2, drained.size)
        assertEquals(listOf("중간 것", "가장 최근 것"), drained.map { it.text })
    }

    @Test
    fun `초기화 실패 시 보관분을 버린다`() {
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
