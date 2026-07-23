package com.aihealthcare.ah0404.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic

/**
 * ============================================================================
 *  WalkingSessionViewModel : 걷기 측정 화면의 "세션 상태 머신" (#90 A-4b)
 * ============================================================================
 *
 *  걸음 감지 자체의 상태 머신(IDLE/WALKING·워밍업 소급·짧은 멈춤 병합)은
 *  WalkingStepDetectorLogic 에 이미 있다(#89). 이 ViewModel 은 그 위에서
 *  '측정 세션'의 상태(준비→측정중→완료)를 관리하고,
 *  걸음 수·경과 시간·산책 영상이 모두 이 한 세션 상태를 공유하게 한다(산정 지점 1곳).
 *
 *  "확정 전 잠정 표시": 보행이 확정(WALKING)되기 전 워밍업 구간에는 걸음 수가 0이라,
 *  숫자 대신 '측정 중…'을 보여준다(confirmed=false). 확정되면 소급 카운트가 반영돼 숫자를 노출.
 *
 *  갱신 주기: 화면(LaunchedEffect)이 측정 중 동안 poll() 을 주기 호출한다.
 *  ViewModel 자체는 무한 타이머를 돌리지 않아 순수 JVM 테스트로 상태 전이를 검증할 수 있다.
 *
 *  ⚠️ 서버 제출은 이 단계에서 하지 않는다. 종료 시 불변 스냅샷(걸음+경과)만 확정하고,
 *     실제 업로드·멱등성(session_id)·거리 계산은 #91(#105 이후) 소관.
 *  생명주기 복원(백그라운드/회전 후 세션 유지)은 후속(#90 2단계).
 * ============================================================================
 */
class WalkingSessionViewModel(
    private val session: WalkingSessionController,
) : ViewModel() {

    enum class Phase { READY, MEASURING, DONE }

    data class UiState(
        val phase: Phase = Phase.READY,
        /** 이 기기가 가속도계를 지원하는가. false 면 측정 불가 안내. */
        val sensorAvailable: Boolean = true,
        /** 센서는 있으나 등록에 실패한 경우. sensorAvailable=false 와 구분되는 재시도 안내용. */
        val startFailed: Boolean = false,
        /** 현재 보행 상태가 WALKING 인가(배지 표시용). */
        val walking: Boolean = false,
        /** 보행이 확정돼 걸음 수가 유효한가. false 면 '측정 중…' 잠정 표시. */
        val confirmed: Boolean = false,
        val steps: Int = 0,
        val elapsedSec: Int = 0,
        /** 종료 후 확정된 불변 스냅샷(DONE 에서 표시). */
        val result: WalkingSnapshot? = null,
    )

    var uiState by mutableStateOf(UiState(sensorAvailable = session.isSensorAvailable))
        private set

    /**
     * 진동·음성 피드백 신호(#92) 발생 이력을 든 트래커. **화면(remember)이 아니라 VM 수명**에 둔다.
     * VM 은 Activity ViewModelStore 에 있어 구성 변경(글꼴 크기·다크모드·멀티윈도우·폴더블 접기/펴기 등)에
     * 생존하므로, 화면이 재구성돼 세션 상태(confirmed/steps)를 다시 전달해도 STARTED/GOAL_REACHED 가
     * 재발생하지 않는다(리뷰 #148 블로커 3). 새 세션 시작·이탈 리셋에서만 함께 초기화된다.
     */
    private val feedbackTracker = WalkingFeedbackTracker()

    /**
     * 측정 시작.
     *  - 가속도계 미지원 → READY 유지, sensorAvailable=false.
     *  - 지원하나 등록 실패 → READY 유지, startFailed=true (다시 시도 안내). MEASURING 으로 가지 않는다.
     */
    fun startMeasuring() {
        if (uiState.phase == Phase.MEASURING) return
        if (!session.isSensorAvailable) {
            uiState = uiState.copy(sensorAvailable = false, startFailed = false)
            return
        }
        if (!session.start()) {
            uiState = uiState.copy(sensorAvailable = true, startFailed = true)
            return
        }
        feedbackTracker.reset() // 새 세션 → 시작/목표 신호 재활성
        uiState = UiState(phase = Phase.MEASURING, sensorAvailable = true)
    }

    /**
     * 화면이 세션 상태 변화(confirmed/steps) 때 호출 — 이번에 **새로 발생한** 피드백 신호만 반환한다.
     * 트래커가 VM 수명이라 구성 변경 후 같은 상태를 다시 전달해도 중복되지 않는다.
     *
     * @param goalSteps 목표 걸음 수(단위가 걸음일 때만). null/0 이하면 목표 도달 신호를 내지 않는다.
     */
    fun drainFeedbackCues(goalSteps: Int?): List<WalkingFeedbackCue> =
        feedbackTracker.onUpdate(uiState.confirmed, uiState.steps, goalSteps)

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

    /**
     * onResume: 측정 중이면 센서 재등록 + 경과 시계 재개.
     * 재등록에 실패하면(백그라운드 복귀 시 센서 확보 실패) 걸음 없이 경과만 흐르는 걸 막기 위해
     * READY 로 되돌리고 재시도 안내(startFailed)를 띄운다 — 센서 자체 유무로 안내를 구분한다.
     */
    fun onResume() {
        if (uiState.phase != Phase.MEASURING) return
        if (!session.resume()) {
            uiState = UiState(
                sensorAvailable = session.isSensorAvailable,
                startFailed = session.isSensorAvailable,
            )
        }
    }

    /** onPause: 측정 중이면 센서 해제(누적값은 유지). onResume 과 대칭. */
    fun onPause() {
        if (uiState.phase == Phase.MEASURING) session.pause()
    }

    /** 측정 종료 → 세션 스냅샷을 확정해 DONE 으로. 서버 제출은 없다(#91). MEASURING 에서만 유효. */
    fun finish() {
        if (uiState.phase != Phase.MEASURING) return
        val snapshot = session.stop()
        uiState = uiState.copy(
            phase = Phase.DONE,
            steps = snapshot.steps,
            elapsedSec = snapshot.durationSec,
            result = snapshot,
        )
    }

    /**
     * 세션을 준비 상태로 초기화한다 — 화면을 완전히 떠날 때(뒤로/완료 후 확인) 호출.
     *
     * 이 VM은 화면 오버레이가 자체 ViewModelStore 가 없어 **Activity 스토어에 바인딩**되므로
     * 구성 변경(회전 등)에는 살아남지만, 화면을 떠나도 Activity 가 살아있는 한 인스턴스가 남는다.
     * 그래서 이탈 시 명시적으로 리셋해 (1) 센서를 확실히 해제하고 (2) 다음 미션 재진입 시
     * 이전 세션 상태(DONE/걸음 수)가 되살아나지 않게 한다.
     *
     * ⚠️ pause() 가 아니라 cancel() 을 부른다. pause() 는 센서만 해제하고 running=true 를 유지해,
     *    같은 Activity 에서 재진입해 start() 를 눌러도 `if (running) return registered` 로 이미
     *    해제된 false 가 즉시 반환돼 재측정이 막힌다(#144 리뷰 블로커 1). cancel() 은 running 까지
     *    해제해 재시작을 보장한다.
     */
    fun reset() {
        session.cancel()
        feedbackTracker.reset() // 이탈 시 신호 이력도 초기화(다음 세션에서 다시 울리도록)
        uiState = UiState(sensorAvailable = session.isSensorAvailable)
    }

    override fun onCleared() {
        // Activity 파괴 시 방어적으로 세션 중단(구성 변경에서는 호출되지 않는다).
        session.cancel()
    }
}
