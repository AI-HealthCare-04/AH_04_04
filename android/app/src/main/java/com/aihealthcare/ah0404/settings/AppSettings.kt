package com.aihealthcare.ah0404.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * 앱 전역 UI 적용값(글자·소리 크기) — 설정(_15)에서 고른 값을 **실제로 화면·미디어에 적용**한다(묶음 C-2, 방식 B).
 *
 *  - `fontScale`: MyApplicationTheme 이 LocalDensity 의 fontScale 에 곱해 **모든 sp 텍스트를 전역 확대/축소**.
 *  - `soundScale`: 미디어 플레이어(스트리밍/BGM) volume 에 적용.
 *  Compose 가 관찰하도록 State 로 두어 설정 변경 시 즉시 반영되고, SharedPreferences 로 재시작에도 유지된다.
 *  (설정 저장의 단일 원천은 서버 #73 이지만, 전역 즉시 적용·오프라인/시작 시점을 위해 로컬 캐시를 둔다.)
 */
object AppSettings {
    const val SIZE_SMALL = "small"
    const val SIZE_MEDIUM = "medium"
    const val SIZE_LARGE = "large"
    const val SOUND_MEDIUM = 0.8f

    var fontScale by mutableFloatStateOf(1.0f); private set
    var soundScale by mutableFloatStateOf(SOUND_MEDIUM); private set

    private const val PREFS = "aigo_ui_settings"
    private const val KEY_FONT = "font_size"
    private const val KEY_SOUND = "sound_size"

    /** 글자 배율: 작게 0.9 / 보통 1.0 / 크게 1.2. (시니어 가독성 위해 크게를 넉넉히) */
    fun fontScaleFor(size: String): Float = when (size) {
        SIZE_SMALL -> 0.9f
        SIZE_LARGE -> 1.2f
        else -> 1.0f
    }

    /** 소리 배율(미디어 volume): 작게 0.5 / 보통 0.8 / 크게 1.0. */
    fun soundScaleFor(size: String): Float = when (size) {
        SIZE_SMALL -> 0.5f
        SIZE_LARGE -> 1.0f
        else -> SOUND_MEDIUM
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 앱 시작 시: 마지막으로 저장된 크기로 전역 적용값 복원(시작 즉시 반영). */
    fun load(context: Context) {
        val p = prefs(context)
        fontScale = fontScaleFor(p.getString(KEY_FONT, SIZE_MEDIUM) ?: SIZE_MEDIUM)
        soundScale = soundScaleFor(p.getString(KEY_SOUND, SIZE_MEDIUM) ?: SIZE_MEDIUM)
    }

    fun setFontSize(context: Context, size: String) {
        fontScale = fontScaleFor(size)
        prefs(context).edit().putString(KEY_FONT, size).apply()
    }

    fun setSoundSize(context: Context, size: String) {
        soundScale = soundScaleFor(size)
        prefs(context).edit().putString(KEY_SOUND, size).apply()
    }
}
