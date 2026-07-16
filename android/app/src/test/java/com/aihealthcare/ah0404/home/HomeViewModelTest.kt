package com.aihealthcare.ah0404.home

import com.aihealthcare.ah0404.network.HomeActivityProfile
import com.aihealthcare.ah0404.network.HomeApi
import com.aihealthcare.ah0404.network.HomeAvailableMissionSummary
import com.aihealthcare.ah0404.network.HomeLatestPrediction
import com.aihealthcare.ah0404.network.HomePointBalance
import com.aihealthcare.ah0404.network.HomeResponse
import com.aihealthcare.ah0404.network.HomeTodaySummary
import com.aihealthcare.ah0404.network.HomeTodayWalking
import com.aihealthcare.ah0404.network.HomeUser
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
 * HomeViewModel 테스트 — GET /home → HomeUi 매핑 + 비노출/오류 처리.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(val result: () -> HomeResponse) : HomeApi {
        override suspend fun getHome() = result()
    }

    private fun home(prediction: HomeLatestPrediction? = HomeLatestPrediction("maintain", "잘 유지 중이에요")) =
        HomeResponse(
            user = HomeUser("홍길동"),
            pointBalance = HomePointBalance(1250),
            activityProfile = HomeActivityProfile("normal"),
            latestPrediction = prediction,
            todaySummary = HomeTodaySummary(2),
            availableMissionSummary = HomeAvailableMissionSummary(meal = 2, exercise = 3, walking = 1, game = 1),
            todayWalking = HomeTodayWalking(dailyTotalMin = 22.0, dailyTotalSteps = 2350),
        )

    @Test
    fun maps_home_response_to_ui() = runTest {
        val vm = HomeViewModel(FakeApi { home() })
        vm.load(); advanceUntilIdle()

        val ui = vm.ui!!
        assertEquals("홍길동", ui.nickname)
        assertEquals(1250, ui.points)
        assertEquals("normal", ui.activityLevel)
        assertEquals("maintain", ui.careStage)
        assertEquals("잘 유지 중이에요", ui.predictionMessage)
        assertEquals(2, ui.completedToday)
        assertEquals(7, ui.availableTotal) // 2+3+1+1
        assertEquals(22, ui.todayWalkingMin.toInt())
        assertEquals(2350, ui.todayWalkingSteps)
        assertFalse(vm.error)
    }

    @Test
    fun null_prediction_maps_to_null_care_stage() = runTest {
        val vm = HomeViewModel(FakeApi { home(prediction = null) })
        vm.load(); advanceUntilIdle()
        assertNull(vm.ui!!.careStage)
        assertNull(vm.ui!!.predictionMessage)
    }

    @Test
    fun load_failure_sets_error_and_null_ui() = runTest {
        val vm = HomeViewModel(FakeApi { throw RuntimeException("boom") })
        vm.load(); advanceUntilIdle()
        assertTrue(vm.error)
        assertNull(vm.ui)
    }

    /** 리뷰 #79: 최초 성공 후 재조회 실패 시 error=true 이면서 ui 는 유지 → 화면이 배너+재시도를 노출. */
    @Test
    fun reentry_failure_sets_error_while_keeping_ui() = runTest {
        var fail = false
        val vm = HomeViewModel(FakeApi { if (fail) throw RuntimeException("boom") else home() })
        vm.load(); advanceUntilIdle()
        assertEquals("홍길동", vm.ui?.nickname)
        assertFalse(vm.error)

        fail = true
        vm.load(); advanceUntilIdle()
        assertTrue(vm.error)                      // 재조회 실패 노출
        assertEquals("홍길동", vm.ui?.nickname)   // 기존 ui 유지(콘텐츠 위 배너로 안내)
    }
}
