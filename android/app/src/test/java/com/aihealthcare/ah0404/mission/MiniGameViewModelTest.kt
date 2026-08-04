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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 미니게임 완료 기록(#348 리뷰 2차): 제한 재시도(3회) 후에도 실패면 pending 보관·재진입 복구,
 * 성공/실패와 무관하게 목록 재조회로 서버 권위값 조정, in-flight 중복 방어를 검증.
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

    private class FakeApi(var fail: Boolean = false) : MissionApi {
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
    fun `최종 실패 - 3회 재시도 후에도 재조회 콜백으로 조정하고 pending 보관`() = runTest(dispatcher) {
        val api = FakeApi(fail = true)
        val vm = MiniGameViewModel(api)
        var recorded = 0

        vm.recordCompletion(gameMission, onRecorded = { recorded++ })
        advanceUntilIdle()

        assertEquals(3, api.requests.size) // 백오프 재시도 3회
        assertEquals(1, recorded) // 실패여도 재조회로 조정(#348 2차) — 응답 유실 시 today_done 이 살아난다

        // 재진입 복구: 네트워크가 복구된 뒤 retryPendingIfAny 가 보관된 완주를 다시 기록한다.
        api.fail = false
        vm.retryPendingIfAny(onRecorded = { recorded++ })
        advanceUntilIdle()
        assertEquals(4, api.requests.size)
        assertEquals(2, recorded)

        // 성공 후엔 pending 이 비어 재호출은 무동작.
        vm.retryPendingIfAny(onRecorded = { recorded++ })
        advanceUntilIdle()
        assertEquals(4, api.requests.size)
        assertEquals(2, recorded)
    }

    @Test
    fun `재시도는 같은 자연 키를 보낸다 - 응답 유실 시 포인트 중복 적립 방지`() = runTest(dispatcher) {
        // #379 리뷰 P1: created_on_device_at 이 없거나 시도마다 달라지면, 첫 POST 가 저장되고 응답만
        //   유실된 재시도가 서버에서 '다른 수행'으로 저장돼 성공 로그·포인트가 중복된다.
        //   게임은 counted_for_daily = success 라 서버 상한이 따로 없어 그대로 이중 적립된다.
        val api = FakeApi(fail = true)
        val vm = MiniGameViewModel(api)

        vm.recordCompletion(gameMission, onRecorded = {})
        advanceUntilIdle()

        assertEquals(3, api.requests.size)
        val keys = api.requests.map { it.createdOnDeviceAt }
        assertTrue("자연 키가 전송되어야 서버가 재전송을 판별한다", keys.all { !it.isNullOrBlank() })
        assertEquals("재시도는 전부 같은 키여야 한다", 1, keys.toSet().size)

        // 화면 재진입 복구도 **같은 키**를 되쏘아야 한다 — 새 키를 만들면 중복 적립된다.
        api.fail = false
        vm.retryPendingIfAny(onRecorded = {})
        advanceUntilIdle()
        assertEquals(4, api.requests.size)
        assertEquals("pending 복구가 새 키를 만들면 안 된다", keys[0], api.requests[3].createdOnDeviceAt)
    }

    @Test
    fun `서로 다른 완주는 서로 다른 자연 키를 갖는다`() = runTest(dispatcher) {
        // 키를 고정하는 방향이 지나쳐 '같은 값 재사용'이 되면, 다음 날/다음 완주가 재전송으로 오인돼
        //   정상 적립이 사라진다. 완주 1회당 새 키가 맞다.
        val api = FakeApi()
        val vm = MiniGameViewModel(api)

        vm.recordCompletion(gameMission, onRecorded = {})
        advanceUntilIdle()
        Thread.sleep(2) // SimpleDateFormat 은 밀리초 단위 — 같은 ms 에 두 완주가 겹치지 않게 한다
        vm.recordCompletion(gameMission, onRecorded = {})
        advanceUntilIdle()

        assertEquals(2, api.requests.size)
        assertNotEquals(api.requests[0].createdOnDeviceAt, api.requests[1].createdOnDeviceAt)
    }

    @Test
    fun `전송 중 중복 완주 신호는 무시된다`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = MiniGameViewModel(api)

        vm.recordCompletion(gameMission, onRecorded = {})
        vm.recordCompletion(gameMission, onRecorded = {}) // 첫 요청이 아직 in-flight
        advanceUntilIdle()

        assertEquals(1, api.requests.size) // 성공이면 재시도 없이 1건
    }
}
