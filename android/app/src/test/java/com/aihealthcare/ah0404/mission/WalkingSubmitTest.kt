package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * 걷기 세션 서버 제출(#91, A안) 검증 — 파이프라인 호출·제출 상태·재전송 자연 키 고정.
 *
 *  실제 네트워크 없이, 제출 파이프라인(uploader)을 기록용 fake 로 주입해 다음을 고정한다.
 *   - 종료 후 submitWalking → 파이프라인이 스냅샷 값으로 호출되고 상태가 Success
 *   - 파이프라인 실패 → Failed (화면이 재시도 버튼 노출)
 *   - 재시도해도 created_on_device_at 이 **바뀌지 않는다**(#158 자연 키 — 중복 집계 방지의 핵심)
 *   - 전송 중 재호출은 무시(중복 제출 방지)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkingSubmitTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** 스냅샷만 돌려주면 되는 최소 컨트롤러(세션 상태 전이는 WalkingSessionViewModelTest 소관). */
    private class StubController(private val snap: WalkingSnapshot) : WalkingSessionController {
        override val isSensorAvailable = true
        override val steps = snap.steps
        override val state = WalkingStepDetectorLogic.State.WALKING
        override fun start() = true
        override fun pause() {}
        override fun resume() = true
        override fun cancel() {}
        override fun elapsedSec() = snap.durationSec
        override fun stop() = snap
    }

    private class RecordingUploader(var fail: Boolean = false) {
        data class Call(val templateId: Int, val steps: Int, val durationSec: Int, val createdOnDeviceAt: String?)
        val calls = mutableListOf<Call>()
        val uploader: WalkingUploader = { tid, steps, dur, created ->
            calls += Call(tid, steps, dur, created)
            if (fail) throw IOException("network down")
        }
    }

    private fun vmWith(uploader: WalkingUploader, snap: WalkingSnapshot): WalkingSessionViewModel =
        WalkingSessionViewModel(StubController(snap), uploader).apply {
            startMeasuring() // 여기서 created_on_device_at 을 잡는다
            finish() // DONE + 스냅샷 확정
        }

    @Test
    fun `종료 후 제출하면 파이프라인이 스냅샷 값으로 호출되고 Success`() = runTest(dispatcher) {
        val rec = RecordingUploader()
        val vm = vmWith(rec.uploader, WalkingSnapshot(steps = 1200, durationSec = 1260))

        vm.submitWalking(missionTemplateId = 7)
        advanceUntilIdle()

        assertEquals(1, rec.calls.size)
        val call = rec.calls.single()
        assertEquals(7, call.templateId)
        assertEquals(1200, call.steps)
        assertEquals(1260, call.durationSec)
        assertNotNull("측정 시작 시각이 자연 키로 전송돼야 한다", call.createdOnDeviceAt)
        assertEquals(WalkingSessionViewModel.SubmitState.Success, vm.submitState)
    }

    @Test
    fun `파이프라인이 실패하면 Failed 로 재시도 버튼을 노출한다`() = runTest(dispatcher) {
        val rec = RecordingUploader(fail = true)
        val vm = vmWith(rec.uploader, WalkingSnapshot(steps = 100, durationSec = 60))

        vm.submitWalking(missionTemplateId = 7)
        advanceUntilIdle()

        assertEquals(WalkingSessionViewModel.SubmitState.Failed, vm.submitState)
    }

    @Test
    fun `재시도해도 created_on_device_at 이 바뀌지 않는다 - 중복 집계 방지의 핵심`() = runTest(dispatcher) {
        val rec = RecordingUploader(fail = true) // 첫 시도 실패시켜 재시도 유도
        val vm = vmWith(rec.uploader, WalkingSnapshot(steps = 500, durationSec = 600))

        vm.submitWalking(missionTemplateId = 7)
        advanceUntilIdle() // 1차 실패 → Failed

        rec.fail = false // 재시도는 성공
        vm.submitWalking(missionTemplateId = 7)
        advanceUntilIdle() // 2차 성공

        assertEquals(2, rec.calls.size)
        assertEquals(
            "재전송은 같은 자연 키여야 서버가 중복으로 안 센다(#158)",
            rec.calls[0].createdOnDeviceAt,
            rec.calls[1].createdOnDeviceAt,
        )
        assertEquals(WalkingSessionViewModel.SubmitState.Success, vm.submitState)
    }

    @Test
    fun `성공 후 재호출은 무시된다 - 완료 화면 재구성 시 재제출 방지`() = runTest(dispatcher) {
        // 리뷰 #172 P1: 완료 화면이 구성 변경(글꼴·다크모드)으로 재구성되면 LaunchedEffect 가 다시 실행돼
        //   submitWalking 이 또 불릴 수 있다. Success 상태에서는 재제출하지 않아야 한다.
        val rec = RecordingUploader()
        val vm = vmWith(rec.uploader, WalkingSnapshot(steps = 800, durationSec = 900))

        vm.submitWalking(missionTemplateId = 7)
        advanceUntilIdle() // Success

        vm.submitWalking(missionTemplateId = 7) // 재구성으로 재실행됐다고 가정
        advanceUntilIdle()

        assertEquals("성공 후에는 다시 제출하지 않는다", 1, rec.calls.size)
        assertEquals(WalkingSessionViewModel.SubmitState.Success, vm.submitState)
    }

    @Test
    fun `전송 중 재호출은 무시된다 - 중복 제출 방지`() = runTest(dispatcher) {
        val rec = RecordingUploader()
        val vm = vmWith(rec.uploader, WalkingSnapshot(steps = 300, durationSec = 300))

        vm.submitWalking(missionTemplateId = 7) // Submitting 진입(아직 완료 전)
        vm.submitWalking(missionTemplateId = 7) // 전송 중 재호출 → 무시돼야
        advanceUntilIdle()

        assertEquals(1, rec.calls.size)
    }

    @Test
    fun `종료하지 않았으면(스냅샷 없음) 제출하지 않는다`() = runTest(dispatcher) {
        val rec = RecordingUploader()
        // finish 를 부르지 않아 result 가 없는 VM
        val vm = WalkingSessionViewModel(StubController(WalkingSnapshot(0, 0)), rec.uploader).apply { startMeasuring() }

        vm.submitWalking(missionTemplateId = 7)
        advanceUntilIdle()

        assertEquals(0, rec.calls.size)
        assertEquals(WalkingSessionViewModel.SubmitState.Idle, vm.submitState)
    }
}
