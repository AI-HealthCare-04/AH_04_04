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
 *  진입 시 서버 값 로드. 변경은 낙관적 적용 후 PATCH — 실패하면 이전 값으로 롤백 + 안내.
 *  성공 시 응답(권위값)으로 재동기화(서버 정규화와 어긋나지 않게).
 *
 *  ⚠️ 리뷰 #71 반영:
 *   1) 읽기·쓰기 공용 `version` — refresh(GET)/변경(PATCH) 모두 시작 시 version 을 올리고, 자기
 *      version 이 최신일 때만 상태를 commit. 진입 GET 이 저장 성공값을 이전 값으로 덮지 못한다.
 *   2) 저장 **직렬화(Mutex)** — 빠른 연속 변경도 사용자 입력 순서대로 PATCH. 마지막 저장의 응답만
 *      재동기화되어 오래된 응답/롤백이 최신 값을 덮지 않는다.
 *   3) pet_type 정규화 — 서버 기본 "default" 등 dog/cat 이외 값은 "dog"(제품 기본 펫)로 매핑해
 *      세그먼트가 항상 하나를 선택하도록 한다.
 *   4) safeCall — CancellationException 은 삼키지 않고 재전파(구조적 동시성 보존).
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

    // 읽기(GET)·쓰기(PATCH)가 공유하는 요청 버전. 상태를 commit 하는 모든 연산이 시작 시 올린다.
    private var version = 0

    // 저장을 사용자 입력 순서대로 직렬화(응답/롤백 순서 뒤섞임 방지).
    private val saveMutex = Mutex()
    private var pendingSaves = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** GET /users/me/settings. 자기 version 이 최신일 때만 상태 commit. loading 은 이 조회가 정리. */
    suspend fun refresh() {
        val myVersion = ++version
        loading = true
        loadError = false
        val result = safeCall { api.getSettings() }
        if (myVersion == version) {
            result
                .onSuccess { applyResponse(it) }
                .onFailure { loadError = true; Log.w(TAG, "설정 조회 실패: ${it.message}") }
            loaded = true
        }
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

    // ── 개별 변경(낙관적 적용 → 직렬 PATCH, 실패 시 롤백) ────────────────────────
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
        viewModelScope.launch { commitSave(body, rollback) }
    }

    /**
     * PATCH 코어(테스트 진입점). 시작 시 version 을 올려 진행 중 refresh 를 무효화하고,
     * Mutex 로 저장을 직렬화한다. 성공하면 최신 version 일 때만 응답(권위값)으로 재동기화,
     * 실패하면 최신 version 일 때만 rollback + saveError. 오래된 응답/롤백은 버린다.
     */
    suspend fun commitSave(body: UserSettingsUpdateRequest, rollback: () -> Unit): Boolean {
        val myVersion = ++version
        pendingSaves++
        saving = true
        saveError = null
        try {
            return saveMutex.withLock {
                safeCall { api.updateSettings(body) }
                    .onSuccess { if (myVersion == version) applyResponse(it) }
                    .onFailure {
                        if (myVersion == version) rollback()
                        saveError = "설정을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
                        Log.w(TAG, "설정 저장 실패: ${it.message}")
                    }
                    .isSuccess
            }
        } finally {
            pendingSaves--
            if (pendingSaves == 0) saving = false
        }
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
