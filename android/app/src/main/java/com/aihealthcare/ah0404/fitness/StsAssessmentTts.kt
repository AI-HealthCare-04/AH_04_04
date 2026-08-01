package com.aihealthcare.ah0404.fitness

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.aihealthcare.ah0404.settings.AppSettings
import java.util.Locale

/**
 * 엔진 초기화 전에 요청된 안내문을 순서대로 보관하는 대기열(#380).
 *
 * 순수 자료구조라 JVM 단위 테스트로 고정한다. 상한을 두는 이유: 초기화가 비정상적으로 지연되는
 * 기기에서 단계 안내가 계속 쌓였다가 한꺼번에 쏟아지지 않게 — 오래된 것부터 버린다.
 */
internal class PendingGuides(private val maxSize: Int = MAX_PENDING) {
    private val queue = ArrayDeque<String>()

    val size: Int get() = queue.size

    fun hold(text: String) {
        if (queue.size >= maxSize) queue.removeFirst()
        queue.addLast(text)
    }

    /** 보관분을 순서대로 꺼내고 비운다. */
    fun drain(): List<String> {
        val held = queue.toList()
        queue.clear()
        return held
    }

    fun clear() = queue.clear()

    companion object {
        const val MAX_PENDING = 4
    }
}

/**
 * 기초체력 평가용 음성 안내(TTS). **이 화면에서는 TTS가 영상보다 중요하다** — 측정 중 어르신은
 * 일어섰다 앉느라 화면을 못 본다. 걷기 챌린지와 동일하게 Android TextToSpeech 사용.
 *
 *  - 카운트는 `QUEUE_FLUSH`(항상 현재 숫자만) — 빠르게 반복하면 발화가 밀려 4회째에 "둘"이 나오는 혼란 방지.
 *  - 안내문은 `QUEUE_ADD`(순서대로 다 들려야).
 *  - 한국어 미설치(LANG_MISSING_DATA/NOT_SUPPORTED)면 음성 없이 **시각 안내만으로 동작**하도록 폴백.
 *  - 생명주기: 화면 이탈 시 stop, 소멸 시 shutdown(누수 방지).
 *
 *  ⚠️ 초기화 레이스(#380): 엔진 준비(onInit) 전에 온 **안내문은 버리지 않고 보관했다가** 준비되면
 *  순서대로 발화한다. 기록 탭 재측정 진입은 화면에 들어오자마자 첫 안내를 요청해서, 버리는 구조에서는
 *  첫 안내("양팔 엑스자…")만 통째로 유실됐다(온보딩 경로는 앞 단계들이 초기화 시간을 벌어 줘 정상).
 *  카운트는 보관하지 않는다 — 지연 후 발화되면 현재 횟수와 어긋나 오히려 혼란을 준다.
 */
class StsAssessmentTts(context: Context) {
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    var languageAvailable = false
        private set

    private val pendingGuides = PendingGuides()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(Locale.KOREAN)
                languageAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setSpeechRate(0.9f) // 시니어 대상: 기본(1.0)보다 느리게
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                ready = true
                flushPendingGuides()
            } else {
                // 초기화 실패 — 보관분을 버려 나중에 엉뚱한 시점에 발화되지 않게 한다(시각 안내로 폴백).
                clearPendingGuides()
            }
        }
    }

    /** 카운트 — 항상 최신 것으로 교체(QUEUE_FLUSH). 빈 문자열은 발화하지 않는다. */
    fun speakCount(word: String) {
        if (!ready || !languageAvailable || word.isBlank()) return
        tts?.speak(word, TextToSpeech.QUEUE_FLUSH, volumeParams(), "sts_count")
    }

    /**
     * 안내문 — 순서대로(QUEUE_ADD). 엔진이 아직 준비되지 않았으면 **버리지 않고 보관**했다가
     * onInit 성공 시 순서대로 발화한다(#380).
     */
    fun speakGuide(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            holdPendingGuide(text)
            return
        }
        if (!languageAvailable) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, volumeParams(), "sts_guide")
    }

    // onInit 콜백 스레드와 화면(메인) 스레드가 대기열을 함께 만지므로 접근을 직렬화한다.
    @Synchronized
    private fun holdPendingGuide(text: String) = pendingGuides.hold(text)

    @Synchronized
    private fun clearPendingGuides() = pendingGuides.clear()

    @Synchronized
    private fun drainPendingGuides(): List<String> = pendingGuides.drain()

    private fun flushPendingGuides() {
        val held = drainPendingGuides()
        if (!languageAvailable) return // 한국어 미설치 — 보관분은 버리고 시각 안내로만 진행
        held.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, volumeParams(), "sts_guide") }
    }

    // 사용자 소리 크기 설정(sound_size)을 발화 음량에 반영(#237). STS는 자체 TTS 인스턴스라 공용 배선과 별개로
    //   발화마다 현재 설정값을 실어 준다(전에는 params=null 이라 항상 기본 음량이었다).
    private fun volumeParams() = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, AppSettings.soundScale.coerceIn(0f, 1f))
    }

    /** 현재 발화 중단(화면 이탈·중단 버튼). 보관 중이던 안내도 함께 버린다 — 떠난 화면의 안내를
     *  나중에 발화하면 안 되기 때문(#380). */
    fun stop() {
        clearPendingGuides()
        tts?.stop()
    }

    /** 완전 해제(소멸 시). */
    fun shutdown() {
        clearPendingGuides()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
