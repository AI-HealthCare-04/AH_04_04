package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.HealthProfileApi
import com.aihealthcare.ah0404.network.HealthProfileLatest
import com.aihealthcare.ah0404.network.HealthProfilePatchRequest
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogUpdateRequest
import com.aihealthcare.ah0404.network.MissionsResponse
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 미션 목록의 단백질 숨김 사유 안내(#304 요청 4) — meal 미션이 없을 때만 최신 프로필로 사유를 만들고,
 *  프로필 조회 실패는 조용히 안내 없이(목록 표시는 그대로) 넘어가는지 검증.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MissionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun mission(type: String) = Mission(
        missionTemplateId = 1,
        missionType = type,
        title = "m",
        description = null,
        level = "easy",
        targetValue = 1,
        targetUnit = "reps",
        requiresSafetyNotice = false,
        rewardPoints = 10,
    )

    private class FakeMissionApi(private val missions: List<Mission>) : MissionApi {
        override suspend fun guestLogin() = TODO()
        override suspend fun getMissions(status: String) = MissionsResponse(missions)
        override suspend fun createMissionLog(body: MissionLogCreateRequest) = TODO()
        override suspend fun createSensorSession(body: SensorSessionCreateRequest) = TODO()
        override suspend fun completeMissionLog(missionLogId: Int, body: MissionLogUpdateRequest) = TODO()
    }

    private class FakeProfileApi(
        private val kidney: String = "none",
        private val protein: String = "none",
        private val fail: Boolean = false,
    ) : HealthProfileApi {
        var latestCalls = 0; private set
        override suspend fun getLatest(): HealthProfileLatest {
            latestCalls++
            if (fail) throw RuntimeException("profile down")
            return HealthProfileLatest(kidneyStatus = kidney, proteinRestrictionStatus = protein)
        }
        override suspend fun updateProfile(body: HealthProfilePatchRequest) = TODO()
    }

    private fun successState(vm: MissionViewModel) = vm.uiState.value as MissionUiState.Success

    @Test
    fun `meal 미션이 있으면 프로필 조회 없이 안내도 없다`() = runTest(dispatcher) {
        val profileApi = FakeProfileApi(kidney = "kidney_disease")
        val vm = MissionViewModel(FakeMissionApi(listOf(mission("walking"), mission("meal"))), profileApi)
        advanceUntilIdle()
        assertNull(successState(vm).proteinHiddenNotice)
        assertEquals("meal 이 보이면 프로필을 조회하지 않는다", 0, profileApi.latestCalls)
    }

    @Test
    fun `meal 미션이 없고 신장질환이면 사유 안내를 만든다`() = runTest(dispatcher) {
        val vm = MissionViewModel(
            FakeMissionApi(listOf(mission("walking"))),
            FakeProfileApi(kidney = "kidney_disease"),
        )
        advanceUntilIdle()
        assertNotNull(successState(vm).proteinHiddenNotice)
    }

    @Test
    fun `프로필 조회 실패는 조용히 안내 없이 목록만 보여준다`() = runTest(dispatcher) {
        val vm = MissionViewModel(FakeMissionApi(listOf(mission("walking"))), FakeProfileApi(fail = true))
        advanceUntilIdle()
        val s = successState(vm)
        assertEquals(1, s.missions.size)
        assertNull(s.proteinHiddenNotice)
    }

    @Test
    fun `상태가 모두 none 인데 meal 이 없으면 추측 안내를 하지 않는다`() = runTest(dispatcher) {
        val vm = MissionViewModel(FakeMissionApi(listOf(mission("walking"))), FakeProfileApi())
        advanceUntilIdle()
        assertNull(successState(vm).proteinHiddenNotice)
    }

    @Test
    fun `내정보 변경 후 재조회하면 meal 복귀·사유 카드 소멸이 반영된다`() = runTest(dispatcher) {
        // 전환 시나리오(리뷰 #322): 신장질환으로 meal 미숨김 → 내정보에서 없음으로 저장 → 복귀 시
        //   loadMissions() 재조회(MainActivity 배선) → 서버가 meal 을 다시 내려주고 카드는 사라져야 한다.
        var missions = listOf(mission("walking"))
        val api = object : MissionApi by FakeMissionApi(emptyList()) {
            override suspend fun getMissions(status: String) = MissionsResponse(missions)
        }
        val vm = MissionViewModel(api, FakeProfileApi(kidney = "kidney_disease"))
        advanceUntilIdle()
        assertNotNull("변경 전: 숨김 사유 카드", successState(vm).proteinHiddenNotice)

        missions = listOf(mission("walking"), mission("meal")) // 신장 '없음' 저장 후 서버 게이트 해제
        vm.loadMissions()
        advanceUntilIdle()
        val s = successState(vm)
        assertEquals("meal 미션 복귀", 2, s.missions.size)
        assertNull("사유 카드 소멸", s.proteinHiddenNotice)
    }
}
