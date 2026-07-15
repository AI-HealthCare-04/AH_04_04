package com.aihealthcare.ah0404.profile

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.UserApi
import com.aihealthcare.ah0404.network.UserInfoResponse
import com.aihealthcare.ah0404.network.UserUpdateRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * `_14 내 정보` 상태 + 백엔드 배선(GET/PATCH /users/me, #67 통합 응답).
 *
 *  화면 진입마다 load() 재조회(리뷰 #68 교훈 1).
 *  ⚠️ 읽기·쓰기 공용 version(리뷰 #70 지적 1): refresh(GET)/saveNickname(PATCH) 모두 시작 시
 *     version 을 올리고, 자기 version 이 여전히 최신일 때만 info 를 commit 한다. 저장이 성공하면
 *     진행 중이던 느린 GET 이 새 이름을 이전 이름으로 덮지 못한다(최신 mutation 보호).
 *  취소 예외(CancellationException)는 삼키지 않고 재전파(리뷰 #70 비블로킹, #68과 동일).
 */
class ProfileViewModel(
    private val api: UserApi = retrofit.create(UserApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var info by mutableStateOf<UserInfoResponse?>(null); private set

    var saving by mutableStateOf(false); private set
    var saveError by mutableStateOf<String?>(null); private set

    // 읽기(GET)·쓰기(PATCH)가 공유하는 요청 버전. info 를 commit 하는 모든 연산이 시작 시 올린다.
    private var version = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** GET /users/me. 자기 version 이 최신일 때만 info/error 반영. loading 은 이 조회가 항상 정리. */
    suspend fun refresh() {
        val myVersion = ++version
        loading = true
        error = false
        val result = safeCall { api.getMe() }
        if (myVersion == version) {
            result
                .onSuccess { info = it }
                .onFailure { error = true; Log.w(TAG, "내 정보 조회 실패: ${it.message}") }
            loaded = true
        }
        loading = false
    }

    fun dismissSaveError() { saveError = null }

    /** 닉네임 변경. 성공 시 info 갱신 후 onSaved() 콜백(편집 모드 종료용). */
    fun updateNickname(newName: String, onSaved: () -> Unit) {
        viewModelScope.launch { if (saveNickname(newName)) onSaved() }
    }

    /**
     * 닉네임 변경 코어(PATCH /users/me). 성공하면 info 갱신 후 true, 실패/검증오류면 saveError + false.
     * 시작 시 version 을 올려 진행 중이던 refresh 를 무효화한다(저장값이 늦은 GET 에 덮이지 않게).
     */
    suspend fun saveNickname(newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty() || name.length > 50) {
            saveError = "닉네임은 1~50자로 입력해 주세요."
            return false
        }
        val myVersion = ++version
        saving = true
        saveError = null
        val ok = safeCall { api.updateMe(UserUpdateRequest(name)) }
            .onSuccess { if (myVersion == version) info = it }
            .onFailure {
                saveError = "저장하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요."
                Log.w(TAG, "닉네임 변경 실패: ${it.message}")
            }
            .isSuccess
        saving = false
        return ok
    }

    /** 취소 예외는 그대로 전파(구조적 동시성 보존), 실제 오류만 Result.failure 로 변환. */
    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        const val TAG = "Profile"
    }
}
