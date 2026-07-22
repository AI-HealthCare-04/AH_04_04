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
 *      준비되는 즉시 순서대로 재생한다.
 *   2. **graceful degrade** — 엔진 미탑재·한국어 미지원·초기화 실패 시 조용히 무동작.
 *      호출부는 예외를 신경 쓰지 않아도 되고 앱은 정상 동작한다.
 *   3. **오디오 포커스** — 발화 동안만 `TRANSIENT_MAY_DUCK` 을 요청해 배경음(몸풀기 BGM 등)을
 *      낮추고, 큐가 비면 반납한다. 시니어 대상에서 안내가 배경음에 묻히지 않게 한다.
 *   4. **중복 호출 안전** — [stop]·[release] 를 여러 번 불러도 문제없다.
 *
 *  수명주기는 [TtsSpeaker] 문서 참고 — **앱 공용이며 [release] 는 Application 만 호출한다.**
 * ============================================================================
 */
class AndroidTtsSpeaker(
    context: Context,
    private val locale: Locale = Locale.KOREAN,
) : TtsSpeaker {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val pending = PendingSpeechQueue()
    private val utteranceSeq = AtomicInteger(0)

    /** 발화 중인(=아직 done/error 통지가 오지 않은) 건수. 0 이 되면 오디오 포커스를 반납한다. */
    private var outstanding = 0

    /** 엔진이 실제로 말할 수 있는 상태인가(초기화 성공 + 해당 언어 사용 가능). */
    private var speakable = false
    private var released = false

    private var speechRate = 1.0f
    private var volume = 1.0f

    override var isEnabled: Boolean = true

    private var tts: TextToSpeech? = null

    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* 발화는 짧아 별도 대응 없음 */ }

    init {
        // OnInitListener 는 메인 스레드로 콜백된다. 상태 변경은 모두 lock 으로 감싼다.
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) = synchronized(this) {
        if (released) return@synchronized
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS 초기화 실패(status=$status) → 음성 안내 비활성(앱 동작에는 영향 없음)")
            pending.clear()
            return@synchronized
        }
        val engine = tts ?: return@synchronized
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "$locale 미지원/데이터 없음 → 음성 안내 비활성(앱 동작에는 영향 없음)")
            pending.clear()
            return@synchronized
        }
        engine.setSpeechRate(speechRate)
        engine.setOnUtteranceProgressListener(progressListener)
        speakable = true

        // 준비 전에 들어온 안내를 순서대로 재생한다(유실 방지).
        val queued = pending.drain()
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
        if (released || !isEnabled || text.isBlank()) return@synchronized
        if (!speakable) {
            // 아직 초기화 중일 수 있다 → 버리지 않고 보관. 초기화가 실패했다면 onInit 이 이미 비웠다.
            pending.offer(text, mode)
            return@synchronized
        }
        enqueue(text, mode)
    }

    /** 실제 재생. 호출 전에 [speakable] 이 보장돼야 한다. */
    private fun enqueue(text: String, mode: SpeechQueueMode) {
        val engine = tts ?: return
        if (outstanding == 0) requestFocus()
        val queueMode = if (mode == SpeechQueueMode.FLUSH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val id = "aigo-${utteranceSeq.incrementAndGet()}"
        outstanding += 1
        val result = engine.speak(text, queueMode, params, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "발화 요청 실패(result=$result)")
            onUtteranceSettled()
        }
    }

    override fun stop() = synchronized(this) {
        pending.clear()
        tts?.stop()
        outstanding = 0
        abandonFocus()
    }

    override fun release() = synchronized(this) {
        if (released) return@synchronized
        released = true
        speakable = false
        pending.clear()
        outstanding = 0
        abandonFocus()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        Log.i(TAG, "TTS 해제")
    }

    // ── 발화 완료 추적 ─────────────────────────────────────────────
    //   큐가 완전히 빈 시점에만 오디오 포커스를 반납한다. 연속 발화 사이에 배경음이
    //   커졌다 작아졌다 하는 것을 막는다.

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = synchronized(this@AndroidTtsSpeaker) { onUtteranceSettled() }

        @Deprecated("레거시 콜백 — 신형 onError(String, Int) 와 함께 유지해야 전 버전에서 누락되지 않는다.")
        override fun onError(utteranceId: String?) = synchronized(this@AndroidTtsSpeaker) { onUtteranceSettled() }

        override fun onError(utteranceId: String?, errorCode: Int) =
            synchronized(this@AndroidTtsSpeaker) { onUtteranceSettled() }
    }

    private fun onUtteranceSettled() {
        if (outstanding > 0) outstanding -= 1
        if (outstanding == 0) abandonFocus()
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
