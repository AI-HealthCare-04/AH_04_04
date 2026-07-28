package com.aihealthcare.ah0404.fitness

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 기초체력 평가용 음성 안내(TTS). **이 화면에서는 TTS가 영상보다 중요하다** — 측정 중 어르신은
 * 일어섰다 앉느라 화면을 못 본다. 걷기 챌린지와 동일하게 Android TextToSpeech 사용.
 *
 *  - 카운트는 `QUEUE_FLUSH`(항상 현재 숫자만) — 빠르게 반복하면 발화가 밀려 4회째에 "둘"이 나오는 혼란 방지.
 *  - 안내문은 `QUEUE_ADD`(순서대로 다 들려야).
 *  - 한국어 미설치(LANG_MISSING_DATA/NOT_SUPPORTED)면 음성 없이 **시각 안내만으로 동작**하도록 폴백.
 *  - 생명주기: 화면 이탈 시 stop, 소멸 시 shutdown(누수 방지).
 */
class StsAssessmentTts(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var languageAvailable = false
        private set

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
            }
        }
    }

    /** 카운트 — 항상 최신 것으로 교체(QUEUE_FLUSH). 빈 문자열은 발화하지 않는다. */
    fun speakCount(word: String) {
        if (!ready || !languageAvailable || word.isBlank()) return
        tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "sts_count")
    }

    /** 안내문 — 순서대로(QUEUE_ADD). */
    fun speakGuide(text: String) {
        if (!ready || !languageAvailable || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "sts_guide")
    }

    /** 현재 발화 중단(화면 이탈·중단 버튼). */
    fun stop() {
        tts?.stop()
    }

    /** 완전 해제(소멸 시). */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
