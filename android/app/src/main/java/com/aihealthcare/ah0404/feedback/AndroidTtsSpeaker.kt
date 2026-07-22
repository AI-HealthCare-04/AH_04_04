package com.aihealthcare.ah0404.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 *  AndroidTtsSpeaker : 플랫폼 TextToSpeech 기반 구현
 * ============================================================================
 *
 *  안드로이드 내장 API(`android.speech.tts`)만 쓴다 — 추가 의존성·API 키·네트워크가 없다.
 *
 *  이 클래스가 책임지는 것:
 *   1. **비동기 초기화 중 발화 보관** — 준비 전 [speak] 를 버리지 않고 [PendingSpeechQueue] 에 모아
 *      준비되는 즉시 순서·정책 그대로 재생한다.
 *   2. **graceful degrade** — 엔진 미탑재·언어 미지원·초기화 실패 시 조용히 무동작.
 *      호출부는 예외를 신경 쓰지 않아도 되고 앱은 정상 동작한다.
 *   3. **오디오 포커스** — 발화 동안만 `TRANSIENT_MAY_DUCK` 을 요청해 배경음(몸풀기 BGM 등)을
 *      낮추고, 미완료 발화가 없어지면 반납한다.
 *   4. **중복 호출 안전** — [stop]·[release] 를 여러 번 불러도 문제없다.
 *
 *  **상태 기계** — 아래 4상태로 "지금 발화를 어떻게 처리할지"가 결정된다.
 *
 *  | 상태 | speak() 동작 |
 *  |---|---|
 *  | INITIALIZING | 보관(pending) — 준비되면 재생 |
 *  | READY | 즉시 재생 |
 *  | UNAVAILABLE | 무시 — **보관하지 않는다**(엔진을 못 쓰므로 쌓아도 재생될 일이 없다) |
 *  | RELEASED | 무시 |
 *
 *  수명주기는 [TtsSpeaker] 문서 참고 — **앱 공용이며 [release] 는 Application 만 호출한다.**
 * ============================================================================
 */
class AndroidTtsSpeaker(
    context: Context,
    private val locale: Locale = Locale.KOREAN,
) : TtsSpeaker {

    private enum class State { INITIALIZING, READY, UNAVAILABLE, RELEASED }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val pending = PendingSpeechQueue()
    private val utteranceSeq = AtomicInteger(0)

    /**
     * 아직 완료(done/error/stop) 통지가 오지 않은 발화 id.
     *
     *  단순 카운터가 아니라 **id 집합**인 이유: [stop] 이후 늦게 도착한 이전 발화의 콜백이
     *  새 발화의 카운트를 깎아 오디오 포커스를 조기 반납하는 것을 막기 위해서다.
     *  집합에 없는 id 의 콜백은 그냥 무시된다.
     */
    private val outstandingIds = mutableSetOf<String>()

    private var state = State.INITIALIZING

    private var speechRate = 1.0f
    private var volume = 1.0f

    override var isEnabled: Boolean = true
        set(value) = synchronized(this) {
            field = value
            // 끄는 순간 대기 중인 안내도 폐기한다. 그러지 않으면 초기화가 끝나는 시점에
            // 이미 꺼진 음성이 되살아난다.
            if (!value) {
                pending.clear()
                stopInternal()
            }
        }

    private var tts: TextToSpeech? = null

    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* 발화는 짧아 별도 대응 없음 */ }

    init {
        // OnInitListener 는 메인 스레드로 콜백된다. 상태 변경은 모두 lock 으로 감싼다.
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) = synchronized(this) {
        if (state == State.RELEASED) return@synchronized

        val engine = tts
        val languageOk = engine != null && status == TextToSpeech.SUCCESS && run {
            val result = engine.setLanguage(locale)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }

        if (!languageOk) {
            val reason = if (status != TextToSpeech.SUCCESS) "초기화 실패(status=$status)" else "$locale 미지원/데이터 없음"
            Log.w(TAG, "$reason → 음성 안내 비활성(앱 동작에는 영향 없음)")
            state = State.UNAVAILABLE
            pending.clear()
            return@synchronized
        }

        engine!!.setSpeechRate(speechRate)
        engine.setOnUtteranceProgressListener(progressListener)
        state = State.READY

        // 준비 전에 들어온 안내를 순서·정책 그대로 재생한다(유실 방지).
        val queued = pending.drain()
        if (!isEnabled) return@synchronized // 초기화 중 꺼졌으면 재생하지 않는다.
        if (queued.isNotEmpty()) Log.i(TAG, "초기화 전 보관된 발화 ${queued.size}건 재생")
        queued.forEach { enqueue(it.text, it.mode) }
    }

    override fun setSpeechRate(rate: Float) = synchronized(this) {
        speechRate = rate
        tts?.setSpeechRate(rate)
        Unit
    }

    override fun setVolume(volume: Float) = synchronized(this) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun speak(text: String, mode: SpeechQueueMode) = synchronized(this) {
        if (!isEnabled || text.isBlank()) return@synchronized
        when (state) {
            // 아직 초기화 중 → 버리지 않고 보관.
            State.INITIALIZING -> pending.offer(text, mode)
            State.READY -> enqueue(text, mode)
            // 엔진을 쓸 수 없거나 이미 해제됨 → 보관해도 재생될 일이 없으므로 그냥 버린다.
            State.UNAVAILABLE, State.RELEASED -> Unit
        }
    }

    /** 실제 재생. 호출 전에 [State.READY] 가 보장돼야 한다. */
    private fun enqueue(text: String, mode: SpeechQueueMode) {
        val engine = tts ?: return
        if (mode == SpeechQueueMode.FLUSH) {
            // 끊긴 발화의 onStop 이 늦게 와도 새 발화를 깎지 않도록 미리 정리한다.
            outstandingIds.clear()
        }
        if (outstandingIds.isEmpty()) requestFocus()
        val queueMode = if (mode == SpeechQueueMode.FLUSH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val id = "aigo-${utteranceSeq.incrementAndGet()}"
        outstandingIds += id
        val result = engine.speak(text, queueMode, params, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "발화 요청 실패(result=$result)")
            settle(id)
        }
    }

    override fun stop() = synchronized(this) { stopInternal() }

    private fun stopInternal() {
        pending.clear()
        tts?.stop()
        // 남은 발화의 콜백은 집합에 없으므로 무시된다.
        outstandingIds.clear()
        abandonFocus()
    }

    override fun release() = synchronized(this) {
        if (state == State.RELEASED) return@synchronized
        state = State.RELEASED
        pending.clear()
        outstandingIds.clear()
        abandonFocus()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        Log.i(TAG, "TTS 해제")
    }

    // ── 발화 완료 추적 ─────────────────────────────────────────────
    //   done / error / **stop** 세 경로를 모두 정산해야 한다. QUEUE_FLUSH 나 stop() 으로 끊긴
    //   발화는 onStop 으로만 통지되므로, 이걸 빠뜨리면 outstanding 이 비지 않아 오디오 포커스를
    //   영영 반납하지 못한다.

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) =
            synchronized(this@AndroidTtsSpeaker) { settle(utteranceId) }

        @Deprecated("레거시 콜백 — 신형 onError(String, Int) 와 함께 유지해야 전 버전에서 누락되지 않는다.")
        override fun onError(utteranceId: String?) =
            synchronized(this@AndroidTtsSpeaker) { settle(utteranceId) }

        override fun onError(utteranceId: String?, errorCode: Int) =
            synchronized(this@AndroidTtsSpeaker) { settle(utteranceId) }

        /** QUEUE_FLUSH·stop() 으로 중단된 발화. 이 경로가 없으면 포커스가 반납되지 않는다. */
        override fun onStop(utteranceId: String?, interrupted: Boolean) =
            synchronized(this@AndroidTtsSpeaker) { settle(utteranceId) }
    }

    /** 해당 발화를 미완료 목록에서 지운다. 이미 정리된 id(늦게 도착한 콜백)는 무시된다. */
    private fun settle(utteranceId: String?) {
        if (utteranceId == null) return
        if (!outstandingIds.remove(utteranceId)) return
        if (outstandingIds.isEmpty()) abandonFocus()
    }

    // ── 오디오 포커스 ──────────────────────────────────────────────

    private fun requestFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
    }

    private companion object {
        const val TAG = "AigoTts"
    }
}
