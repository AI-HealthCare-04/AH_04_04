package com.aihealthcare.ah0404.profile

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.HealthProfileApi
import com.aihealthcare.ah0404.network.HealthProfileLatest
import com.aihealthcare.ah0404.network.HealthProfilePatchRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * 설정 '내 정보' 신체 정보(#기록탭 §2) 상태 + 배선. GET /health-profiles/me/latest · PATCH /health-profiles/me.
 *  저장은 최신 스냅샷에 편집분을 덮어 **새 프로필 행**을 만든다 → 다음 근육 건강 정보(추론)부터 반영.
 *  ProfileViewModel 과 같은 version 보호 패턴(느린 GET 이 저장값을 덮지 않게).
 */
class HealthInfoViewModel(
    private val api: HealthProfileApi = retrofit.create(HealthProfileApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var profile by mutableStateOf<HealthProfileLatest?>(null); private set

    var saving by mutableStateOf(false); private set
    var saveError by mutableStateOf<String?>(null); private set
    var savedMessage by mutableStateOf<String?>(null); private set

    private var version = 0

    fun load() {
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val myVersion = ++version
        loading = true
        error = false
        val result = safeCall { api.getLatest() }
        if (myVersion == version) {
            result
                .onSuccess { profile = it }
                .onFailure { error = true; Log.w(TAG, "신체 정보 조회 실패: ${it.message}") }
        }
        loading = false
    }

    fun dismissSaveError() { saveError = null }
    fun dismissSavedMessage() { savedMessage = null }

    /** 편집 저장. 성공 시 profile 갱신 + 안내 메시지, onSaved() 콜백(편집 모드 종료). */
    fun save(
        heightText: String,
        weightText: String,
        waistText: String,
        kidney: String,
        protein: String,
        onSaved: () -> Unit,
    ) {
        val height = heightText.trim().toDoubleOrNull()
        val weight = weightText.trim().toDoubleOrNull()
        if (height == null || height <= 0 || weight == null || weight <= 0) {
            saveError = "키·몸무게를 0보다 큰 값으로 입력해 주세요."
            return
        }
        // 허리둘레는 선택 — **비우면** '측정 안 함'(null)으로 저장(서버가 기존 값을 지운다).
        //   비어 있지 않은데 숫자가 아니거나 0 이하면 삭제 의도가 아니라 입력 오류이므로 저장을 막는다(리뷰 #275).
        val waistTrimmed = waistText.trim()
        val waist: Double?
        if (waistTrimmed.isEmpty()) {
            waist = null
        } else {
            val parsed = waistTrimmed.toDoubleOrNull()
            if (parsed == null || parsed <= 0) {
                saveError = "허리둘레는 0보다 큰 숫자로 입력하거나, 비워서 '측정 안 함'으로 두세요."
                return
            }
            waist = parsed
        }
        viewModelScope.launch {
            val myVersion = ++version
            saving = true
            saveError = null
            val ok = safeCall {
                api.updateProfile(
                    HealthProfilePatchRequest(
                        heightCm = height,
                        weightKg = weight,
                        waistCm = waist,
                        kidneyStatus = kidney,
                        proteinRestrictionStatus = protein,
                    ),
                )
            }
                .onSuccess {
                    if (myVersion == version) profile = it
                    savedMessage = "저장했어요. 다음 근육 건강 정보부터 반영돼요."
                }
                .onFailure { e ->
                    // 서버는 유효 변경 없는 요청을 400 으로 준다(#272) — 사용자에겐 부드럽게 안내.
                    saveError = if (e is HttpException && e.code() == 400) {
                        "변경된 내용이 없어요."
                    } else {
                        "저장하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요."
                    }
                    Log.w(TAG, "신체 정보 저장 실패: ${e.message}")
                }
                .isSuccess
            saving = false
            if (ok) onSaved()
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
        const val TAG = "HealthInfo"
    }
}
