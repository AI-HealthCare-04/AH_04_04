package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.MealTodayLog
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogCreateResponse
import com.aihealthcare.ah0404.network.MissionLogUpdateRequest
import com.aihealthcare.ah0404.network.SensorSessionCreateRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 미니게임 완료 기록(#348 리뷰 P2): 완주 시 game 완료 로그를 1회만 기록하고, 성공 시에만 목록 갱신
 * 콜백이 불리며, 실패는 조용히 흡수(자동 복귀를 막지 않음)하는지 검증.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniGameViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val gameMission = Mission(
        missionTemplateId = 7,
        missionType = "game",
        title = "게임",
        description = null,
        level = "easy",
        targetValue = 1,
        targetUnit = "reps",
        requiresSafetyNotice = false,
        rewardPoints = 10,
    )

    private class FakeApi(private val fail: Boolean = false) : MissionApi {
        val requests = mutableListOf<MissionLogCreateRequest>()
        override suspend fun guestLogin() = TODO()
        override suspend fun getMissions(status: String) = TODO()
        override suspend fun createMissionLog(body: MissionLogCreateRequest): MissionLogCreateResponse {
            requests += body
            if (fail) throw RuntimeException("network down")
            return MissionLogCreateResponse(
                missionLogId = 1,
                status = "completed",
                success = true,
                countedForDaily = true,
                earnedPoints = 10,
                dailyResult = "success",
            )
        }
        override suspend fun createSensorSession(body: SensorSessionCreateRequest) = TODO()
        override suspend fun completeMissionLog(missionLogId: Int, body: MissionLogUpdateRequest) = TODO()
    }

    @Test
    fun `완주 기록 성공 - game 완료 로그 1건 + 콜백 1회`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = MiniGameViewModel(api)
        var recorded = 0

        vm.recordCompletion(gameMission, onRecorded = { recorded++ })
        advanceUntilIdle()

        assertEquals(1, api.requests.size)
        assertEquals("game", api.requests[0].missionType)
        assertEquals("completed", api.requests[0].status)
        assertEquals(7, api.requests[0].missionTemplateId)
        assertEquals(1, recorded)
    }

    @Test
    fun `실패는 조용히 흡수 - 콜백 미호출, 예외 전파 없음`() = runTest(dispatcher) {
        val vm = MiniGameViewModel(FakeApi(fail = true))
        var recorded = 0

        vm.recordCompletion(gameMission, onRecorded = { recorded++ })
        advanceUntilIdle()

        assertEquals(0, recorded) // 실패 시 목록 재조회를 트리거하지 않는다(변화 없음)
    }

    @Test
    fun `전송 중 중복 완주 신호는 무시된다`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = MiniGameViewModel(api)

        vm.recordCompletion(gameMission, onRecorded = {})
        vm.recordCompletion(gameMission, onRecorded = {}) // 첫 요청이 아직 in-flight
        advanceUntilIdle()

        assertEquals(1, api.requests.size)
    }
}
