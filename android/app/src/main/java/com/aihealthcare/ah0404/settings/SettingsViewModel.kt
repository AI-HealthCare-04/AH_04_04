package com.aihealthcare.ah0404.settings

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.SettingsApi
import com.aihealthcare.ah0404.network.UserSettingsResponse
import com.aihealthcare.ah0404.network.UserSettingsUpdateRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 설정(_15) 상태 + 백엔드 영속화(GET/PATCH /users/me/settings).
 *
 *  진입 시 서버 값 로드. 변경은 **낙관적 업데이트 후 PATCH** — 실패하면 이전 값으로 롤백 + 안내.
 *  PATCH 응답(권위값)으로 상태를 재동기화해 서버 정규화와 어긋나지 않게 한다.
 *  진입마다 재조회 + generation 가드(리뷰 #68 교훈). 목업 저장 처리 없음(실패는 명시 안내).
 */
class SettingsViewModel(
    private val api: SettingsApi = retrofit.create(SettingsApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf(false); private set

    var fontSize by mutableStateOf("medium"); private set
    var soundSize by mutableStateOf("medium"); private set
    var petType by mutableStateOf("dog"); private set
    var musicEnabled by mutableStateOf(true); private set

    var saving by mutableStateOf(false); private set
    var saveError by mutableStateOf<String?>(null); private set

    private var generation = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** GET /users/me/settings. 최신 세대만 상태 commit. */
    suspend fun refresh() {
        val gen = ++generation
        loading = true
        loadError = false
        val result = safeCall { api.getSettings() }
        if (gen != generation) return
        result
            .onSuccess { applyResponse(it) }
            .onFailure { loadError = true; Log.w(TAG, "설정 조회 실패: ${it.message}") }
        loaded = true
        loading = false
    }

    private fun applyResponse(s: UserSettingsResponse) {
        fontSize = s.fontSize
        soundSize = s.soundSize
        petType = s.petType
        musicEnabled = s.musicEnabled
    }

    // ── 개별 변경(낙관적 적용 → PATCH, 실패 시 롤백) ─────────────────────────────
    fun changeFontSize(value: String) {
        val prev = fontSize; fontSize = value
        save(UserSettingsUpdateRequest(fontSize = value)) { fontSize = prev }
    }

    fun changeSoundSize(value: String) {
        val prev = soundSize; soundSize = value
        save(UserSettingsUpdateRequest(soundSize = value)) { soundSize = prev }
    }

    fun changePetType(value: String) {
        val prev = petType; petType = value
        save(UserSettingsUpdateRequest(petType = value)) { petType = prev }
    }

    fun changeMusicEnabled(value: Boolean) {
        val prev = musicEnabled; musicEnabled = value
        save(UserSettingsUpdateRequest(musicEnabled = value)) { musicEnabled = prev }
    }

    fun dismissSaveError() { saveError = null }

    private fun save(body: UserSettingsUpdateRequest, rollback: () -> Unit) {
        viewModelScope.launch { saveSettings(body, rollback) }
    }

    /**
     * PATCH 코어(테스트 진입점). 성공하면 응답(권위값)으로 재동기화 후 true,
     * 실패하면 rollback() 으로 이전 값 복구 + saveError 후 false.
     */
    suspend fun saveSettings(body: UserSettingsUpdateRequest, rollback: () -> Unit): Boolean {
        saving = true
        saveError = null
        val ok = runCatching { api.updateSettings(body) }
            .onSuccess { applyResponse(it) }
            .onFailure {
                rollback()
                saveError = "설정을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
                Log.w(TAG, "설정 저장 실패: ${it.message}")
            }
            .isSuccess
        saving = false
        return ok
    }

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        const val TAG = "Settings"
    }
}
