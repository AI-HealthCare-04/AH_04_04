package com.aihealthcare.ah0404.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ============================================================================
 *  SpeechToTextController : 안드로이드 온디바이스 STT(SpeechRecognizer, ko-KR) 래퍼
 * ============================================================================
 *
 *  실기기 인식률 검증용 최소 프로브. 상태는 Compose 스냅샷(mutableStateOf)으로 노출한다.
 *   - partialText : 말하는 도중 실시간 부분 인식 결과
 *   - finalText   : 최종 확정된 인식 원문(이걸 /voice/parse 로 보낸다)
 *   - isListening / error : UI 표시용
 *
 *  ⚠️ RECORD_AUDIO 런타임 권한이 있어야 startListening 이 동작한다(화면에서 먼저 요청).
 *  ⚠️ 사용 종료 시 destroy() 로 SpeechRecognizer 해제(누수 방지).
 * ============================================================================
 */
class SpeechToTextController(context: Context) {

    private val appContext = context.applicationContext
    private val available = SpeechRecognizer.isRecognitionAvailable(appContext)
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = available

    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var finalText by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            error = null
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }

        override fun onError(errorCode: Int) {
            isListening = false
            error = errorMessage(errorCode)
            Log.w(TAG, "STT 에러: $error (code=$errorCode)")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { partialText = it }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            firstResult(results)?.let {
                finalText = it
                partialText = it
                Log.i(TAG, "STT 최종 인식: \"$it\"")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** 인식 시작. 직전 결과/에러를 초기화하고 ko-KR 로 듣기 시작한다. */
    fun start(languageTag: String = "ko-KR") {
        if (!available) {
            error = "이 기기에서 음성 인식을 사용할 수 없습니다."
            return
        }
        // SpeechRecognizer 는 재사용 시 상태가 꼬일 수 있어 매 호출마다 새로 만든다.
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }
        partialText = ""
        finalText = ""
        error = null

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        isListening = false
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 오류"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없습니다."
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
        SpeechRecognizer.ERROR_NO_MATCH -> "말을 인식하지 못했어요. 다시 말해 주세요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기가 사용 중입니다. 잠시 후 다시 시도하세요."
        SpeechRecognizer.ERROR_SERVER -> "서버 오류"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 들리지 않았어요."
        else -> "알 수 없는 오류 (code=$code)"
    }

    companion object {
        const val TAG = "VoiceProbe"
    }
}
