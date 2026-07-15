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
 *  화면 진입마다 load() 재조회(리뷰 #68 교훈 1). 겹친 재조회는 generation 토큰으로 최신만 commit(교훈 3).
 *  닉네임 변경은 PATCH 성공 시에만 화면 반영(목업 성공 처리 없음, 실패 시 안내 + 편집 유지).
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

    private var generation = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** GET /users/me. 최신 세대만 상태 commit(겹친 조회 시 낡은 응답이 덮지 않음). */
    suspend fun refresh() {
        val gen = ++generation
        loading = true
        error = false
        val result = try {
            Result.success(api.getMe())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (gen != generation) return // 더 최신 refresh 가 시작됨 → 낡은 결과 버림
        result
            .onSuccess { info = it }
            .onFailure { error = true; Log.w(TAG, "내 정보 조회 실패: ${it.message}") }
        loaded = true
        loading = false
    }

    fun dismissSaveError() { saveError = null }

    /** 닉네임 변경. 성공 시 info 갱신 후 onSaved() 콜백(편집 모드 종료용). */
    fun updateNickname(newName: String, onSaved: () -> Unit) {
        viewModelScope.launch { if (saveNickname(newName)) onSaved() }
    }

    /**
     * 닉네임 변경 코어(PATCH /users/me). 성공하면 info 갱신 후 true, 실패/검증오류면 saveError + false.
     * 클라 검증: 공백 제거 후 1~50자(백엔드 계약과 동일). 테스트 진입점.
     */
    suspend fun saveNickname(newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty() || name.length > 50) {
            saveError = "닉네임은 1~50자로 입력해 주세요."
            return false
        }
        saving = true
        saveError = null
        val ok = runCatching { api.updateMe(UserUpdateRequest(name)) }
            .onSuccess { info = it }
            .onFailure {
                saveError = "저장하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요."
                Log.w(TAG, "닉네임 변경 실패: ${it.message}")
            }
            .isSuccess
        saving = false
        return ok
    }

    companion object {
        const val TAG = "Profile"
    }
}
