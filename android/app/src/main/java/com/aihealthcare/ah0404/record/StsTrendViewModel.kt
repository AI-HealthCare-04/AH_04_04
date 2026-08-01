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
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.StsAssessmentItem
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.launch

/**
 * 5STS 재측정·이력(#353) — 기록 탭 StsTrendCard 의 상태.
 *
 *  - 이력: GET /physical-assessments/me/history (측정 기록만, 최신순 — #354 백엔드).
 *  - 재측정 저장(리뷰 #355 P1 — 멱등): 세션을 먼저 만들고(POST /health-check-sessions, STARTED 재사용이라
 *    고아 없음) 그 session_id 로 제출한다. 서버의 '세션당 검사 1건 유니크 + COMPLETED 재제출은 기존 결과
 *    반환'(#180)이 그대로 멱등성을 보장해, 응답 유실 후 재시도해도 측정 행이 중복되지 않는다.
 *  - 저장 실패는 측정값·세션을 보관해 카드에서 재시도할 수 있게 한다(측정 다시 하기 강요 금지 — 시니어 UX).
 *  - 이력 조회 실패(리뷰 #355 P2)는 기존 목록을 유지하고 loadError 로 구분한다 — 실제 '측정 전'과
 *    구분되지 않으면 저장 직후 재조회 실패만으로 화면 이력이 사라져 보인다.
 */
class StsTrendViewModel(
    // 테스트 주입용 기본 인자 — 프로덕션은 공용 retrofit.
    private val recordApi: RecordApi = retrofit.create(RecordApi::class.java),
    private val assessmentApi: OnboardingApi = retrofit.create(OnboardingApi::class.java),
) : ViewModel() {

    var history by mutableStateOf<List<StsAssessmentItem>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var saveError by mutableStateOf(false); private set

    /** 저장 실패 시 재시도용으로 보관하는 마지막 측정값(초)과 멱등 세션 id(리뷰 #355 P1). */
    private var pendingSeconds: Double? = null
    private var pendingSessionId: Int? = null

    fun load() {
        viewModelScope.launch {
            try {
                history = recordApi.getStsHistory().assessments
                loadError = false
            } catch (e: Exception) {
                // 기존 목록은 유지(리뷰 #355 P2): 조회 실패를 '측정 전'으로 위장하지 않는다.
                Log.w(TAG, "5STS 이력 조회 실패(기존 목록 유지): ${e.message}")
                loadError = true
            }
            loaded = true
        }
    }

    /** 측정 완료값 저장. 성공 시 이력 재조회로 추이·변화 문구가 갱신된다. */
    fun submit(seconds: Double) {
        // 미해결 제출 방어(리뷰 #355 3차): 완료 여부가 불명확한 세션이 남아 있으면 새 측정값을 받지 않고
        //   보관된 값의 재시도로 돌린다 — 다른 payload 가 완료된 세션에 붙어 영구 409 가 되는 경로 차단.
        //   (UI 도 saveError 동안 재측정 버튼을 비활성화하지만, 상태 전이 틈을 이중 방어한다.)
        val unresolved = pendingSeconds
        if (pendingSessionId != null && unresolved != null) {
            doSubmit(unresolved)
            return
        }
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
                // 멱등 제출(리뷰 #355 P1): 재시도는 같은 세션 id 를 재사용한다 — 첫 요청이 저장되고 응답만
                //   유실됐어도 서버가 COMPLETED 재제출로 보고 기존 결과를 돌려줘(#180) 중복 행이 안 생긴다.
                //   세션 생성 자체도 STARTED 재사용이라(서버) 응답 유실 재시도에 고아가 쌓이지 않는다.
                val sessionId = pendingSessionId
                    ?: assessmentApi.createSession(SessionCreateRequest()).sessionId.also { pendingSessionId = it }
                assessmentApi.createPhysicalAssessment(
                    PhysicalAssessmentRequest(
                        assessmentType = "reassessment",
                        sessionId = sessionId,
                        chairStand5TimeSec = seconds,
                    )
                )
                pendingSeconds = null
                pendingSessionId = null
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
