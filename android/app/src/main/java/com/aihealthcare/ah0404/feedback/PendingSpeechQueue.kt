package com.aihealthcare.ah0404.feedback

/** 초기화 완료 전에 들어온 발화 한 건. */
internal data class PendingSpeech(val text: String, val mode: SpeechQueueMode)

/**
 * TextToSpeech 초기화가 끝나기 전에 들어온 발화를 보관한다.
 *
 *  엔진 초기화는 비동기라 수백 ms 가 걸린다. 그 사이의 [TtsSpeaker.speak] 를 그냥 버리면
 *  **화면 진입 직후의 안내가 통째로 사라진다**(걷기는 확정까지 10초가 걸려 우연히 늦지 않았을 뿐,
 *  스트레칭처럼 진입 즉시 안내가 필요한 화면에서는 첫 문장을 잃는다).
 *
 *  안드로이드 의존이 없는 순수 클래스라 JVM 단위테스트로 검증한다.
 *
 *  @param capacity 보관 상한. 초기화가 비정상적으로 지연돼도 무한히 쌓이지 않게 한다.
 *   가득 차면 **가장 오래된 것을 버린다** — 안내 음성은 최신 상태가 더 유효하기 때문이다.
 */
internal class PendingSpeechQueue(private val capacity: Int = DEFAULT_CAPACITY) {

    private val items = ArrayDeque<PendingSpeech>()

    val size: Int get() = items.size

    /** 보관한다. 상한을 넘으면 가장 오래된 항목을 버린다. */
    fun offer(text: String, mode: SpeechQueueMode) {
        if (capacity <= 0) return
        if (items.size >= capacity) items.removeFirst()
        items.addLast(PendingSpeech(text, mode))
    }

    /**
     * 보관분을 **넣은 순서대로** 꺼내고 비운다.
     *
     *  첫 항목의 큐 정책은 그대로 두고 이후 항목은 [SpeechQueueMode.ADD] 로 바꿔 반환한다.
     *  그대로 재생하면 뒤 항목의 `FLUSH` 가 앞 항목을 끊어버려 **마지막 하나만 들리기** 때문이다.
     */
    fun drain(): List<PendingSpeech> {
        if (items.isEmpty()) return emptyList()
        val drained = items.toList()
        items.clear()
        return drained.mapIndexed { index, pending ->
            if (index == 0) pending else pending.copy(mode = SpeechQueueMode.ADD)
        }
    }

    /** 보관분을 버린다(초기화 실패·release 등). */
    fun clear() = items.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
