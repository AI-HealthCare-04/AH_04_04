package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogCreateResponse
import com.aihealthcare.ah0404.network.Mission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [#225 3차 리뷰] Activity 범위 VM 의 저장 상태 수명 회귀 방어.
 *  - `저장 완료 → 시스템 뒤로가기 → 재진입` 시 이전 Saved/Error 오버레이가 재노출되면 안 된다
 *    → onScreenEntered() 가 saveState 를 Idle 로 되돌리는지 고정.
 *  - `저장 중 뒤로가기`는 proteinBackAllowed 가 막는다(Saving 중 이탈 금지).
 */
class ProteinChallengeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun fakeApi(
        result: () -> MissionLogCreateResponse,
    ): MissionApi = object : MissionApi by unsupported() {
        override suspend fun createMissionLog(body: MissionLogCreateRequest): MissionLogCreateResponse = result()
    }

    private val mission = Mission(
        missionTemplateId = 1,
        missionType = "meal",
        title = "단백질 식사 기록하기",
        level = "easy",
        targetValue = 1,
        targetUnit = "times",
        requiresSafetyNotice = false,
        rewardPoints = 5,
    )

    private fun savedResponse(counted: Boolean) = MissionLogCreateResponse(
        missionLogId = 1,
        status = "completed",
        success = counted,
        countedForDaily = counted,
        earnedPoints = if (counted) 5 else 0,
        dailyResult = "none",
    )

    @Test
    fun `저장 완료 후 재진입하면 이전 결과 오버레이 상태가 남지 않는다`() = runTest {
        val vm = ProteinChallengeViewModel(fakeApi { savedResponse(counted = true) })
        vm.save(mission, listOf("meat"))
        advanceUntilIdle()
        assertTrue(vm.saveState.value is ProteinSaveState.Saved)

        vm.onScreenEntered() // 시스템 뒤로가기로 이탈 → 재진입
        assertEquals(ProteinSaveState.Idle, vm.saveState.value)
    }

    @Test
    fun `오류 상태도 재진입 시 초기화된다`() = runTest {
        val vm = ProteinChallengeViewModel(fakeApi { throw RuntimeException("network") })
        vm.save(mission, listOf("meat"))
        advanceUntilIdle()
        assertTrue(vm.saveState.value is ProteinSaveState.Error)

        vm.onScreenEntered()
        assertEquals(ProteinSaveState.Idle, vm.saveState.value)
    }

    @Test
    fun `재진입 리셋 후 첫 저장은 fresh today_log 기준으로 최초 달성을 판정한다`() = runTest {
        val vm = ProteinChallengeViewModel(fakeApi { savedResponse(counted = true) })
        vm.save(mission, listOf("meat"))
        advanceUntilIdle()
        // 같은 방문의 재저장은 신규 지급이 아니다
        vm.save(mission, listOf("meat", "egg"))
        advanceUntilIdle()
        assertFalse((vm.saveState.value as ProteinSaveState.Saved).newlyCounted)

        // 재진입(리셋) 후에는 mission.todayLog(여기선 null=미달성) 기준으로 다시 판정 → 최초 달성
        vm.onScreenEntered()
        vm.save(mission, listOf("meat"))
        advanceUntilIdle()
        assertTrue((vm.saveState.value as ProteinSaveState.Saved).newlyCounted)
    }

    @Test
    fun `저장 중에는 이탈이 허용되지 않는다`() {
        assertFalse(proteinBackAllowed(ProteinSaveState.Saving))
        assertTrue(proteinBackAllowed(ProteinSaveState.Idle))
        assertTrue(proteinBackAllowed(ProteinSaveState.Saved(true, 5, newlyCounted = true, savedCount = 1)))
        assertTrue(proteinBackAllowed(ProteinSaveState.Error("x")))
    }
}

/** MissionApi 의 나머지 메서드는 이 테스트에서 호출되지 않는다 — 호출되면 즉시 실패시켜 오사용을 드러낸다. */
private fun unsupported(): MissionApi =
    java.lang.reflect.Proxy.newProxyInstance(
        MissionApi::class.java.classLoader,
        arrayOf(MissionApi::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("테스트에서 호출되면 안 됨: ${method.name}") } as MissionApi
