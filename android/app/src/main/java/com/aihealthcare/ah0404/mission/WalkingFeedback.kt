package com.aihealthcare.ah0404.mission

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 걷기 피드백 실행기(#92 A-5) — 신호를 진동·음성으로 사용자에게 전달.
 * 화면은 이 인터페이스에만 의존해, 테스트에서는 fake 로 대체하고 실기기에선 Android 구현을 쓴다.
 */
interface WalkingFeedback {
    fun play(cue: WalkingFeedbackCue)
    fun release()
}

/**
 * ============================================================================
 *  AndroidWalkingFeedback : 진동 + 한국어 TTS 음성 (#92 A-5, 낙상 위험 안전)
 * ============================================================================
 *
 *  고령 사용자가 걷는 동안 화면을 응시하지 않아도 진행을 알 수 있도록, 핵심 순간에
 *  진동과 짧은 음성 안내를 낸다.
 *
 *  견고성:
 *   - 진동: minSdk 24 대응 버전 분기(VibratorManager/VibrationEffect/레거시).
 *   - 음성: TextToSpeech 지연 초기화. 미탑재·한국어 미지원·초기화 실패 시 **음성만 건너뛰고
 *     진동은 유지**한다(피드백이 완전히 사라지지 않도록 graceful degrade).
 * ============================================================================
 */
class AndroidWalkingFeedback(context: Context) : WalkingFeedback {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = resolveVibrator(appContext)

    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) Log.w(TAG, "한국어 TTS 미지원 → 음성 생략(진동만)")
        } else {
            Log.w(TAG, "TTS 초기화 실패($status) → 음성 생략(진동만)")
        }
    }

    override fun play(cue: WalkingFeedbackCue) {
        vibrate(cue)
        speak(cue)
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun vibrate(cue: WalkingFeedbackCue) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        when (cue) {
            WalkingFeedbackCue.STARTED -> oneShot(vib, 120L)
            WalkingFeedbackCue.GOAL_REACHED -> waveform(vib, longArrayOf(0L, 120L, 100L, 120L))
        }
    }

    private fun oneShot(vib: Vibrator, ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(ms)
        }
    }

    private fun waveform(vib: Vibrator, pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
        }
    }

    private fun speak(cue: WalkingFeedbackCue) {
        if (!ttsReady) return
        val text = when (cue) {
            WalkingFeedbackCue.STARTED -> "측정을 시작했어요. 화면을 보지 않고 편하게 걸으셔도 돼요."
            WalkingFeedbackCue.GOAL_REACHED -> "목표를 달성했어요. 천천히 마무리해 주세요."
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, cue.name)
    }

    private companion object {
        const val TAG = "WalkingFeedback"

        fun resolveVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
