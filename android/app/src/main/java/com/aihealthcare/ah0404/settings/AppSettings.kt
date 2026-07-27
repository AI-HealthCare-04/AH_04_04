package com.aihealthcare.ah0404.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 앱 전역 UI 적용값(글자·소리 크기·배경음악) — 설정(_15)에서 고른 값을 **실제로 화면·미디어에 적용**한다(묶음 C-2/C-3, 방식 B).
 *
 *  - `fontScale`: MyApplicationTheme 이 LocalDensity 의 fontScale 에 곱해 **모든 sp 텍스트를 전역 확대/축소**.
 *  - `soundScale`: 미디어 플레이어 volume 배율.
 *  - `musicEnabled`: **배경음악(루틴 BGM) 켜기/끄기**. 끄기면 BGM을 준비·재생·오디오포커스 요청 안 함(다른 앱 음악 미방해, #87). (운동 영상 나레이션은 배경음악이 아니므로 대상 아님)
 *  Compose 가 관찰하도록 State 로 두어 설정 변경 시 즉시 반영되고, SharedPreferences 로 재시작에도 유지된다.
 *  (설정 저장의 단일 원천은 서버 #73 이지만, 전역 즉시 적용·오프라인/시작 시점을 위해 로컬 캐시를 둔다.)
 */
object AppSettings {
    const val SIZE_SMALL = "small"
    const val SIZE_MEDIUM = "medium"
    const val SIZE_LARGE = "large"
    const val SOUND_MEDIUM = 0.8f

    // 운동 난이도(서버 활동레벨 문자열과 동일): 운동영상 재생 속도를 정한다.
    const val DIFF_EASY = "easy"
    const val DIFF_NORMAL = "normal"
    const val DIFF_HARD = "hard"

    var fontScale by mutableFloatStateOf(1.0f); private set
    var soundScale by mutableFloatStateOf(SOUND_MEDIUM); private set
    var musicEnabled by mutableStateOf(true); private set

    /** 운동 난이도(easy/normal/hard). 기본은 STS 평가레벨을 따라가고, 설정에서 직접 고르면 그 값이 우선. */
    var exerciseDifficulty by mutableStateOf(DIFF_NORMAL); private set
    // 사용자가 설정에서 직접 골랐는가 — true면 평가레벨 자동추종(sync)을 하지 않는다.
    private var exerciseDifficultyUserSet = false

    private const val PREFS = "aigo_ui_settings"
    private const val KEY_FONT = "font_size"
    private const val KEY_SOUND = "sound_size"
    private const val KEY_MUSIC = "music_enabled"
    private const val KEY_DIFFICULTY = "exercise_difficulty"
    private const val KEY_DIFFICULTY_USER_SET = "exercise_difficulty_user_set"

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

    /** 운동영상 재생 속도: 가볍게 0.8x / 보통 1.0x / 힘차게 1.25x.
     *  ExoPlayer setPlaybackSpeed 는 기본 시간축 신축(pitch 유지)이라 빨라져도 목소리 음정은 자연스럽다. */
    fun exerciseSpeedFor(difficulty: String): Float = when (difficulty) {
        DIFF_EASY -> 0.8f
        DIFF_HARD -> 1.25f
        else -> 1.0f
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 앱 시작 시: 마지막으로 저장된 값으로 전역 적용값 복원(시작 즉시 반영). */
    fun load(context: Context) {
        val p = prefs(context)
        fontScale = fontScaleFor(p.getString(KEY_FONT, SIZE_MEDIUM) ?: SIZE_MEDIUM)
        soundScale = soundScaleFor(p.getString(KEY_SOUND, SIZE_MEDIUM) ?: SIZE_MEDIUM)
        musicEnabled = p.getBoolean(KEY_MUSIC, true)
        exerciseDifficulty = p.getString(KEY_DIFFICULTY, DIFF_NORMAL) ?: DIFF_NORMAL
        exerciseDifficultyUserSet = p.getBoolean(KEY_DIFFICULTY_USER_SET, false)
    }

    fun setFontSize(context: Context, size: String) {
        fontScale = fontScaleFor(size)
        prefs(context).edit().putString(KEY_FONT, size).apply()
    }

    fun setSoundSize(context: Context, size: String) {
        soundScale = soundScaleFor(size)
        prefs(context).edit().putString(KEY_SOUND, size).apply()
    }

    fun setMusicEnabled(context: Context, enabled: Boolean) {
        musicEnabled = enabled
        prefs(context).edit().putBoolean(KEY_MUSIC, enabled).apply()
    }

    /** 설정에서 사용자가 직접 고른 운동 난이도(영속). 이후에는 평가레벨 자동추종보다 우선한다. */
    fun setExerciseDifficulty(context: Context, difficulty: String) {
        exerciseDifficulty = difficulty
        exerciseDifficultyUserSet = true
        prefs(context).edit()
            .putString(KEY_DIFFICULTY, difficulty)
            .putBoolean(KEY_DIFFICULTY_USER_SET, true)
            .apply()
    }

    /** STS 평가 결과 레벨로 운동 난이도 기본값을 맞춘다 — 사용자가 아직 직접 고르지 않았을 때만 따라간다.
     *  (홈/온보딩이 활동레벨을 받아올 때 호출) 알 수 없는 값은 무시한다. */
    fun syncExerciseDifficultyFromLevel(context: Context, level: String) {
        if (exerciseDifficultyUserSet) return
        val normalized = when (level) {
            DIFF_EASY, DIFF_NORMAL, DIFF_HARD -> level
            else -> return
        }
        exerciseDifficulty = normalized
        prefs(context).edit().putString(KEY_DIFFICULTY, normalized).apply()
    }
}
