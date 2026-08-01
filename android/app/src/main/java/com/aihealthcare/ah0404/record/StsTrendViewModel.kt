package com.aihealthcare.ah0404.record

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.StsAssessmentItem
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.launch

/**
 * 5STS 재측정·이력(#353) — 기록 탭 StsTrendCard 의 상태.
 *
 *  - 이력: GET /physical-assessments/me/history (측정 기록만, 최신순 — #354 백엔드).
 *  - 재측정 저장: POST /physical-assessments (assessment_type=reassessment, session_id=null 독립 제출).
 *    온보딩 세션 경로와 같은 엔드포인트라 레벨 산출 등 서버 로직을 그대로 탄다.
 *  - 저장 실패는 측정값을 보관해 카드에서 재시도할 수 있게 한다(측정 다시 하기 강요 금지 — 시니어 UX).
 */
class StsTrendViewModel(
    // 테스트 주입용 기본 인자 — 프로덕션은 공용 retrofit.
    private val recordApi: RecordApi = retrofit.create(RecordApi::class.java),
    private val assessmentApi: OnboardingApi = retrofit.create(OnboardingApi::class.java),
) : ViewModel() {

    var history by mutableStateOf<List<StsAssessmentItem>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var saveError by mutableStateOf(false); private set

    /** 저장 실패 시 재시도용으로 보관하는 마지막 측정값(초). */
    private var pendingSeconds: Double? = null

    fun load() {
        viewModelScope.launch {
            history = try {
                recordApi.getStsHistory().assessments
            } catch (e: Exception) {
                Log.w(TAG, "5STS 이력 조회 실패(카드는 빈 상태 표시): ${e.message}")
                emptyList()
            }
            loaded = true
        }
    }

    /** 측정 완료값 저장. 성공 시 이력 재조회로 추이·변화 문구가 갱신된다. */
    fun submit(seconds: Double) {
        pendingSeconds = seconds
        doSubmit(seconds)
    }

    /** 저장 실패 후 재시도 — 같은 측정값을 다시 보낸다(재측정 강요 금지). */
    fun retrySubmit() {
        pendingSeconds?.let(::doSubmit)
    }

    private fun doSubmit(seconds: Double) {
        if (saving) return
        viewModelScope.launch {
            saving = true
            saveError = false
            try {
                assessmentApi.createPhysicalAssessment(
                    PhysicalAssessmentRequest(
                        assessmentType = "reassessment",
                        sessionId = null,
                        chairStand5TimeSec = seconds,
                    )
                )
                pendingSeconds = null
                load()
            } catch (e: Exception) {
                Log.w(TAG, "5STS 재측정 저장 실패(재시도 가능): ${e.message}")
                saveError = true
            } finally {
                saving = false
            }
        }
    }

    private companion object { const val TAG = "StsTrend" }
}
