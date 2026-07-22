package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WalkingSessionViewModel 세션 상태 머신 테스트(#90) — 센서/서버 없이 fake controller 로 전이 검증.
 *
 *  ⚠️ 걸음 감지 정확도(피크·게이팅)는 WalkingStepDetectorLogicTest 소관. 여기서는 세션 상태 전이만.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkingSessionViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** WalkingSessionController 를 흉내내는 fake — 걸음/상태/센서지원/결과를 테스트에서 조작. */
    private class FakeController : WalkingSessionController {
        override var isSensorAvailable = true
        override var steps = 0
        override var state = WalkingStepDetectorLogic.State.IDLE
        var elapsed = 0
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var submittedWith: Int? = null
        var submitError: Exception? = null
        var submitResult: WalkingFlowUseCase.Result = WalkingFlowUseCase.Result(
            missionLogId = 1, steps = 0, durationMin = 0f, distanceKm = 0f,
            finalStatus = "completed", success = true, dailyTotalMin = null,
        )

        override fun start(): Boolean { startCount++; return isSensorAvailable }
        override fun pause() { pauseCount++ }
        override fun resume() { resumeCount++ }
        override fun elapsedSec() = elapsed
        override suspend fun stopAndSubmit(missionTemplateId: Int): WalkingFlowUseCase.Result {
            submittedWith = missionTemplateId
            submitError?.let { throw it }
            return submitResult
        }
    }

    @Test
    fun start_moves_to_measuring_and_registers_sensor() = runTest {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)

        vm.startMeasuring()

        assertEquals(WalkingSessionViewModel.Phase.MEASURING, vm.uiState.phase)
        assertTrue(vm.uiState.sensorAvailable)
        assertEquals(1, fake.startCount)
    }

    @Test
    fun start_without_sensor_stays_ready() = runTest {
        val fake = FakeController().apply { isSensorAvailable = false }
        val vm = WalkingSessionViewModel(fake)

        vm.startMeasuring()

        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
        assertFalse(vm.uiState.sensorAvailable)
        assertEquals(0, fake.startCount) // 센서 없으면 세션을 시작하지 않는다
    }

    @Test
    fun poll_before_walking_is_provisional_not_confirmed() = runTest {
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
    fun poll_after_walking_confirmed_exposes_steps() = runTest {
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
    fun poll_ignored_when_not_measuring() = runTest {
        val fake = FakeController().apply { steps = 99 }
        val vm = WalkingSessionViewModel(fake)

        vm.poll() // READY 상태 → 무시

        assertEquals(0, vm.uiState.steps)
        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
    }

    @Test
    fun finish_success_submits_and_moves_to_done() = runTest {
        val fake = FakeController().apply {
            steps = 42
            submitResult = submitResult.copy(steps = 42, durationMin = 5f, distanceKm = 0.03f)
        }
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        vm.finish(missionTemplateId = 7)
        assertEquals(WalkingSessionViewModel.Phase.SUBMITTING, vm.uiState.phase) // 즉시 제출중
        advanceUntilIdle()

        assertEquals(WalkingSessionViewModel.Phase.DONE, vm.uiState.phase)
        assertEquals(7, fake.submittedWith)
        assertEquals(42, vm.uiState.steps)
        assertEquals(42, vm.uiState.result?.steps)
    }

    @Test
    fun finish_failure_moves_to_error_with_message() = runTest {
        val fake = FakeController().apply { submitError = RuntimeException("network down") }
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        vm.finish(missionTemplateId = 3)
        advanceUntilIdle()

        assertEquals(WalkingSessionViewModel.Phase.ERROR, vm.uiState.phase)
        assertEquals("network down", vm.uiState.errorMessage)
    }

    @Test
    fun retry_after_error_resubmits_and_can_succeed() = runTest {
        val fake = FakeController().apply {
            steps = 20
            submitError = RuntimeException("timeout")
        }
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()
        vm.finish(missionTemplateId = 5)
        advanceUntilIdle()
        assertEquals(WalkingSessionViewModel.Phase.ERROR, vm.uiState.phase)

        // 네트워크 회복 후 재시도 → 같은 걸음 수로 재전송, 성공
        fake.submitError = null
        fake.submitResult = fake.submitResult.copy(steps = 20)
        vm.retrySubmit(missionTemplateId = 5)
        assertEquals(WalkingSessionViewModel.Phase.SUBMITTING, vm.uiState.phase)
        advanceUntilIdle()

        assertEquals(WalkingSessionViewModel.Phase.DONE, vm.uiState.phase)
        assertNull(vm.uiState.errorMessage)
        assertEquals(20, vm.uiState.result?.steps)
    }

    @Test
    fun finish_ignored_when_not_measuring() = runTest {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)

        vm.finish(missionTemplateId = 1) // READY 상태 → 무시
        advanceUntilIdle()

        assertEquals(WalkingSessionViewModel.Phase.READY, vm.uiState.phase)
        assertNull(fake.submittedWith)
    }

    @Test
    fun lifecycle_pause_resume_delegates_to_session() = runTest {
        val fake = FakeController()
        val vm = WalkingSessionViewModel(fake)
        vm.startMeasuring()

        vm.onPause()
        vm.onResume()

        assertEquals(1, fake.pauseCount)
        assertEquals(1, fake.resumeCount)
    }
}
