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
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskReassessRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * 설정 '내 정보' 신체 정보(#기록탭 §2) 상태 + 배선. GET /health-profiles/me/latest · PATCH /health-profiles/me.
 *  저장은 최신 스냅샷에 편집분을 덮어 **새 프로필 행**을 만들고, 이어서 재평가(POST
 *  /risk-predictions/reassess)를 불러 **새 예측을 생성**한다 — GET latest 는 저장된 마지막
 *  예측을 돌려줄 뿐이라, 재평가 없이는 프로필을 고쳐도 점수가 갱신되지 않는다(특히 점수 모델
 *  배포 전 가입 계정은 muscle_score=null 인 옛 예측만 남아 "준비 중"에 영영 머문다).
 *
 *  ⚠️ 저장과 재평가는 **분리된 상태**다(리뷰 #294 P1): PATCH 성공 즉시 저장을 확정(saving 해제·
 *  onSaved·"저장했어요")하고, 재평가는 [scoreRefresh] 로 따로 흐른다 — 재평가가 타임아웃까지
 *  늦어도 사용자가 '저장 중…'에 갇히지 않는다. 실패 유형도 구분한다: 422/점수 미제공(재시도
 *  무의미)은 NOT_ELIGIBLE, 네트워크·5xx 는 FAILED(재시도 버튼 제공).
 *  ProfileViewModel 과 같은 version 보호 패턴(느린 GET 이 저장값을 덮지 않게).
 */
class HealthInfoViewModel(
    private val api: HealthProfileApi = retrofit.create(HealthProfileApi::class.java),
    private val recordApi: RecordApi = retrofit.create(RecordApi::class.java),
) : ViewModel() {

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var profile by mutableStateOf<HealthProfileLatest?>(null); private set

    var saving by mutableStateOf(false); private set
    var saveError by mutableStateOf<String?>(null); private set
    var savedMessage by mutableStateOf<String?>(null); private set

    /** 점수 재평가 진행 상태 — 저장(PATCH)과 분리된 부가 상태(리뷰 #294 P1). null = 아직 시도 전. */
    enum class ScoreRefreshState {
        IN_PROGRESS,
        APPLIED, // 새 예측 생성 + 점수 존재 → 즉시 반영됨
        NOT_ELIGIBLE, // 422(연령 미지원 등)·2xx 점수 미제공 — 재시도해도 같으므로 버튼 없음
        FAILED, // 네트워크·5xx — 정보는 저장됐고 재시도 버튼 제공
    }

    var scoreRefresh by mutableStateOf<ScoreRefreshState?>(null); private set

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
    fun save(heightText: String, weightText: String, waistText: String, kidney: String, onSaved: () -> Unit) {
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
                    HealthProfilePatchRequest(heightCm = height, weightKg = weight, waistCm = waist, kidneyStatus = kidney),
                )
            }
                .onSuccess {
                    if (myVersion == version) profile = it
                    savedMessage = "저장했어요."
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
            // 저장 상태는 여기서 확정한다 — 아래 재평가가 아무리 늦어도 '저장 중…'에 갇히지 않는다(리뷰 #294 P1).
            saving = false
            if (ok) {
                onSaved()
                launchScoreRefresh()
            }
        }
    }

    /** FAILED 상태에서 사용자가 다시 시도(화면의 '점수 다시 계산' 버튼). 진행 중이면 무시. */
    fun retryScoreRefresh() {
        if (scoreRefresh != ScoreRefreshState.IN_PROGRESS) launchScoreRefresh()
    }

    private fun launchScoreRefresh() {
        viewModelScope.launch { runScoreRefresh() }
    }

    /**
     * 재평가 1회 실행 → [scoreRefresh] 판정(리뷰 #294 상태 경계).
     *  - 2xx + muscle_score 존재 → APPLIED (즉시 반영 확정)
     *  - 2xx 인데 점수 없음 → NOT_ELIGIBLE ("바로 반영"으로 확정하지 않는다 — 코호트 미탑재 등)
     *  - 422(연령 미지원) → NOT_ELIGIBLE (재시도해도 같음 — "다음부터 반영" 같은 거짓 안내 금지)
     *  - 그 외(네트워크·5xx) → FAILED (정보는 저장됨 — 재시도 제공)
     */
    private suspend fun runScoreRefresh() {
        scoreRefresh = ScoreRefreshState.IN_PROGRESS
        scoreRefresh = try {
            val result = recordApi.reassessRiskPrediction(RiskReassessRequest())
            if (result.muscleScore != null) ScoreRefreshState.APPLIED else ScoreRefreshState.NOT_ELIGIBLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.w(TAG, "점수 재평가 거절(정보 저장은 완료, code=${e.code()}): ${e.message}")
            if (e.code() == 422) ScoreRefreshState.NOT_ELIGIBLE else ScoreRefreshState.FAILED
        } catch (e: Exception) {
            Log.w(TAG, "점수 재평가 실패(정보 저장은 완료): ${e.message}")
            ScoreRefreshState.FAILED
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
