package com.aihealthcare.ah0404.mission

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * ============================================================================
 *  WalkingSessionViewModel : 걷기 측정 화면의 "세션 상태 머신" (#90 A-4b)
 * ============================================================================
 *
 *  걸음 감지 자체의 상태 머신(IDLE/WALKING·워밍업 소급·짧은 멈춤 병합)은
 *  WalkingStepDetectorLogic 에 이미 있다(#89). 이 ViewModel 은 그 위에서
 *  '측정 세션'의 상태(준비→측정중→제출중→완료/오류)를 관리하고,
 *  걸음 수·경과 시간·산책 영상이 모두 이 한 세션 상태를 공유하게 한다(산정 지점 1곳).
 *
 *  "확정 전 잠정 표시": 보행이 확정(WALKING)되기 전 워밍업 구간에는 걸음 수가 0이라,
 *  숫자 대신 '측정 중…'을 보여준다(confirmed=false). 확정되면 소급 카운트가 반영돼 숫자를 노출.
 *
 *  갱신 주기: 화면(LaunchedEffect)이 측정 중 동안 poll() 을 주기 호출한다.
 *  ViewModel 자체는 무한 타이머를 돌리지 않아 순수 JVM 테스트로 상태 전이를 검증할 수 있다.
 *
 *  생명주기 복원(백그라운드/회전 후 세션 유지)은 후속(#90 2단계)에서 다룬다.
 * ============================================================================
 */
class WalkingSessionViewModel(
    private val session: WalkingSessionController,
) : ViewModel() {

    enum class Phase { READY, MEASURING, SUBMITTING, DONE, ERROR }

    data class UiState(
        val phase: Phase = Phase.READY,
        val sensorAvailable: Boolean = true,
        /** 현재 보행 상태가 WALKING 인가(배지 표시용). */
        val walking: Boolean = false,
        /** 보행이 확정돼 걸음 수가 유효한가. false 면 '측정 중…' 잠정 표시. */
        val confirmed: Boolean = false,
        val steps: Int = 0,
        val elapsedSec: Int = 0,
        val result: WalkingFlowUseCase.Result? = null,
        val errorMessage: String? = null,
    )

    var uiState by mutableStateOf(UiState(sensorAvailable = session.isSensorAvailable))
        private set

    /** 측정 시작. 센서 미지원이면 READY 를 유지하고 sensorAvailable=false 로 안내. */
    fun startMeasuring() {
        if (uiState.phase == Phase.MEASURING) return
        if (!session.isSensorAvailable) {
            uiState = uiState.copy(sensorAvailable = false)
            return
        }
        session.start()
        uiState = UiState(
            phase = Phase.MEASURING,
            sensorAvailable = true,
        )
    }

    /** 화면이 측정 중 동안 주기적으로 호출 — 세션에서 걸음/상태/경과를 읽어 반영한다. */
    fun poll() {
        if (uiState.phase != Phase.MEASURING) return
        val walking = session.state == WalkingStepDetectorLogic.State.WALKING
        val steps = session.steps
        uiState = uiState.copy(
            walking = walking,
            confirmed = walking || steps > 0,
            steps = steps,
            elapsedSec = session.elapsedSec(),
        )
    }

    /** onResume: 측정 중이면 센서 재등록. */
    fun onResume() {
        if (uiState.phase == Phase.MEASURING) session.resume()
    }

    /** onPause: 센서 해제(누적값은 유지). */
    fun onPause() {
        session.pause()
    }

    /**
     * 측정 종료 → 서버 흐름 실행. MEASURING 상태에서만 유효.
     * @param missionTemplateId 대상 걷기 미션 템플릿 id
     */
    fun finish(missionTemplateId: Int) {
        if (uiState.phase != Phase.MEASURING) return
        uiState = uiState.copy(
            phase = Phase.SUBMITTING,
            steps = session.steps,
            elapsedSec = session.elapsedSec(),
        )
        submit(missionTemplateId)
    }

    /** 전송 실패(ERROR) 후 재시도. 세션은 이미 종료돼 누적 걸음/시간이 고정돼 있어 그대로 다시 보낸다. */
    fun retrySubmit(missionTemplateId: Int) {
        if (uiState.phase != Phase.ERROR) return
        uiState = uiState.copy(phase = Phase.SUBMITTING, errorMessage = null)
        submit(missionTemplateId)
    }

    private fun submit(missionTemplateId: Int) {
        viewModelScope.launch {
            val outcome = try {
                Result.success(session.stopAndSubmit(missionTemplateId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            outcome
                .onSuccess { r ->
                    uiState = uiState.copy(
                        phase = Phase.DONE,
                        result = r,
                        steps = r.steps,
                    )
                }
                .onFailure { e ->
                    Log.w(TAG, "걷기 결과 전송 실패: ${e.message}")
                    uiState = uiState.copy(
                        phase = Phase.ERROR,
                        errorMessage = e.message ?: "전송에 실패했어요. 잠시 후 다시 시도해 주세요.",
                    )
                }
        }
    }

    override fun onCleared() {
        session.pause()
    }

    companion object {
        const val TAG = "WalkingSessionVM"
    }
}
