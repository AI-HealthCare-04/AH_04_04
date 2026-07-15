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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 설정(_15) 상태 + 백엔드 영속화(GET/PATCH /users/me/settings).
 *
 *  ⚠️ 동시성 정책(리뷰 #71·#73) — **읽기·쓰기 모두 하나의 Mutex 로 전면 직렬화**하고, **저장 큐가 비면
 *     서버 권위값으로 재동기화(GET)** 한다. 이 두 규칙만으로 모든 경쟁/실패/순서 조합에서 화면이 서버
 *     상태로 **수렴**한다(version 같은 별도 토큰 불필요):
 *      - GET·PATCH 가 총순서로 실행되어 오래된 GET 이 최신 PATCH 를 앞지르지 못한다.
 *      - 저장이 몇 건 실패하든, 큐가 비는 순간 GET 으로 서버값을 다시 반영해 낙관적 변경이 잔류하지 않는다.
 *  변경은 낙관적으로 즉시 반영(고령 사용자 즉시 피드백)하되, 최종 수렴이 정합을 보장한다.
 *  CancellationException 은 safeCall 로 재전파(구조적 동시성 보존).
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

    // 읽기·쓰기 전면 직렬화(총순서 보장). 진행 중 저장 건수(0이 되면 서버로 수렴).
    private val gate = Mutex()
    private var pending = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** GET /users/me/settings — 직렬 큐를 통해 실행. */
    suspend fun refresh() {
        loading = true
        loadError = false
        gate.withLock {
            safeCall { api.getSettings() }
                .onSuccess { applyResponse(it) }
                .onFailure { loadError = true; Log.w(TAG, "설정 조회 실패: ${it.message}") }
        }
        loaded = true
        loading = false
    }

    private fun applyResponse(s: UserSettingsResponse) {
        fontSize = s.fontSize
        soundSize = s.soundSize
        petType = normalizePet(s.petType)
        musicEnabled = s.musicEnabled
    }

    /** dog/cat 이외(서버 기본 "default" 포함)는 제품 기본 펫 "dog"로 매핑 → 세그먼트가 항상 선택 표시. */
    private fun normalizePet(pet: String): String = if (pet == "dog" || pet == "cat") pet else "dog"

    // ── 개별 변경(낙관적 즉시 반영 → 직렬 PATCH → 큐 비면 서버 수렴) ───────────────
    fun changeFontSize(value: String) { fontSize = value; enqueueSave(UserSettingsUpdateRequest(fontSize = value)) }
    fun changeSoundSize(value: String) { soundSize = value; enqueueSave(UserSettingsUpdateRequest(soundSize = value)) }
    fun changePetType(value: String) { petType = value; enqueueSave(UserSettingsUpdateRequest(petType = value)) }
    fun changeMusicEnabled(value: Boolean) { musicEnabled = value; enqueueSave(UserSettingsUpdateRequest(musicEnabled = value)) }

    fun dismissSaveError() { saveError = null }

    private fun enqueueSave(body: UserSettingsUpdateRequest) {
        viewModelScope.launch { save(body) }
    }

    /**
     * PATCH(직렬화) + 큐가 비면 서버 권위값으로 수렴. 테스트 진입점.
     * 성공하면 응답 반영, 실패하면 saveError. 어느 경우든 큐가 비는 순간 GET 으로 서버 상태에 정렬한다.
     */
    suspend fun save(body: UserSettingsUpdateRequest): Boolean {
        pending++
        saving = true
        saveError = null
        val ok = gate.withLock {
            safeCall { api.updateSettings(body) }
                .onSuccess { applyResponse(it) }
                .onFailure {
                    saveError = "설정을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
                    Log.w(TAG, "설정 저장 실패: ${it.message}")
                }
                .isSuccess
        }
        pending--
        if (pending == 0) {
            // 큐가 비면 서버 상태로 수렴(실패/경쟁/순서 뒤섞임 모든 조합에서 화면=서버). saveError 는 유지.
            gate.withLock {
                safeCall { api.getSettings() }.onSuccess { applyResponse(it) }
            }
            saving = false
        }
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
