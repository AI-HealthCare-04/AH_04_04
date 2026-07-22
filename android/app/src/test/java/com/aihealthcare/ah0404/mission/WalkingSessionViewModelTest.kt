package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WalkingSessionViewModel 세션 상태 머신 테스트(#90) — 센서 없이 fake controller 로 전이 검증.
 *
 *  ⚠️ 걸음 감지 정확도(피크·게이팅)는 WalkingStepDetectorLogicTest 소관. 여기서는 세션 상태 전이만.
 *  ⚠️ 서버 제출은 #90 범위에서 제거됐다(→ #91). 종료는 로컬 스냅샷 확정까지만 검증한다.
 */
class WalkingSessionViewModelTest {

    /** WalkingSessionController 를 흉내내는 fake — 걸음/상태/센서지원/시작성공을 테스트에서 조작. */
    private class FakeController : WalkingSessionController {
        override var isSensorAvailable = true
        override var steps = 0
        override var state = WalkingStepDetectorLogic.State.IDLE
        var elapsed = 0
        /** start() 반환값(센서 등록 성공 여부)을 흉내 — 등록 실패 경로 검증용. */
        var startSucceeds = true
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var snapshot = WalkingSnapshot(steps = 0, durationSec = 0)

        override fun start(): Boolean { startCount++; return startSucceeds }
        override fun pause() { pauseCount++ }
        override fun resume() { resumeCount++ }
        override fun elapsedSec() = elapsed
        override fun stop(): WalkingSnapshot { stopCount++; return snapshot }
    }

    @Test
    fun start_moves_to_measuring_and_registers_sensor() {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)

        vm.startMeasuring()

        assertEquals(WalkingSessionViewModel.Phase.MEASURING, vm.uiState.phase)
        assertTrue(vm.uiState.sensorAvailable)
        assertFalse(vm.uiState.startFailed)
        assertEquals(1, fake.startCount)
    }

    @Test
    fun start_without_sensor_stays_ready() {
        val fake = FakeController().apply { isSensorAvailable = false }
        val vm = WalkingSessionViewModel(fake)

        vm.startMeasuring()

        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
        assertFalse(vm.uiState.sensorAvailable)
        assertEquals(0, fake.startCount) // 센서 없으면 세션을 시작하지 않는다
    }

    @Test
    fun start_registration_failure_stays_ready_with_flag() {
        // 가속도계 객체는 있으나 registerListener 실패 → MEASURING 진입 금지, 다시 시도 안내
        val fake = FakeController().apply { isSensorAvailable = true; startSucceeds = false }
        val vm = WalkingSessionViewModel(fake)

        vm.startMeasuring()

        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
        assertTrue(vm.uiState.sensorAvailable)     // 센서 자체는 있음
        assertTrue(vm.uiState.startFailed)         // 등록 실패 안내
        assertEquals(1, fake.startCount)           // start 는 시도했다
    }

    @Test
    fun poll_before_walking_is_provisional_not_confirmed() {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        // 워밍업 구간: 아직 IDLE, 걸음 0 → '측정 중…' 잠정 표시
        fake.state = WalkingStepDetectorLogic.State.IDLE
        fake.steps = 0
        fake.elapsed = 3
        vm.poll()

        assertFalse(vm.uiState.confirmed)
        assertFalse(vm.uiState.walking)
        assertEquals(0, vm.uiState.steps)
        assertEquals(3, vm.uiState.elapsedSec)
    }

    @Test
    fun poll_after_walking_confirmed_exposes_steps() {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        // 보행 확정: WALKING + 소급 카운트 10
        fake.state = WalkingStepDetectorLogic.State.WALKING
        fake.steps = 10
        fake.elapsed = 8
        vm.poll()

        assertTrue(vm.uiState.confirmed)
        assertTrue(vm.uiState.walking)
        assertEquals(10, vm.uiState.steps)
        assertEquals(8, vm.uiState.elapsedSec)
    }

    @Test
    fun poll_ignored_when_not_measuring() {
        val fake = FakeController().apply { steps = 99 }
        val vm = WalkingSessionViewModel(fake)

        vm.poll() // READY 상태 → 무시

        assertEquals(0, vm.uiState.steps)
        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
    }

    @Test
    fun finish_moves_to_done_with_snapshot() {
        val fake = FakeController().apply {
            snapshot = WalkingSnapshot(steps = 42, durationSec = 300)
        }
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        vm.finish()

        assertEquals(WalkingSessionViewModel.Phase.DONE, vm.uiState.phase)
        assertEquals(1, fake.stopCount)
        assertEquals(42, vm.uiState.steps)
        assertEquals(300, vm.uiState.elapsedSec)
        assertEquals(WalkingSnapshot(42, 300), vm.uiState.result)
    }

    @Test
    fun finish_ignored_when_not_measuring() {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)

        vm.finish() // READY 상태 → 무시

        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
        assertEquals(0, fake.stopCount)
        assertNull(vm.uiState.result)
    }

    @Test
    fun can_measure_again_after_done() {
        // DONE 이후 다시 측정 시작이 가능해야 한다(세션 재시작 안전성).
        val fake = FakeController().apply { snapshot = WalkingSnapshot(5, 10) }
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()
        vm.finish()
        assertEquals(WalkingSessionViewModel.Phase.DONE, vm.uiState.phase)

        vm.startMeasuring()

        assertEquals(WalkingSessionViewModel.Phase.MEASURING, vm.uiState.phase)
        assertEquals(2, fake.startCount)
        assertNull(vm.uiState.result) // 새 세션은 이전 결과를 비운다
    }

    @Test
    fun lifecycle_pause_resume_delegates_to_session_when_measuring() {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        vm.onPause()
        vm.onResume()

        assertEquals(1, fake.pauseCount)
        assertEquals(1, fake.resumeCount)
    }

    @Test
    fun lifecycle_pause_ignored_when_not_measuring() {
        // onResume 과 대칭: 측정 중이 아닐 땐 센서를 건드리지 않는다.
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)

        vm.onPause()
        vm.onResume()

        assertEquals(0, fake.pauseCount)
        assertEquals(0, fake.resumeCount)
    }
}
