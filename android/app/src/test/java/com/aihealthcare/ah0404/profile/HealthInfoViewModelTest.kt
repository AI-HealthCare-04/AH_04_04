package com.aihealthcare.ah0404.profile

import com.aihealthcare.ah0404.network.ChallengeTotalsResponse
import com.aihealthcare.ah0404.network.HealthProfileApi
import com.aihealthcare.ah0404.network.HealthProfileLatest
import com.aihealthcare.ah0404.network.HealthProfilePatchRequest
import com.aihealthcare.ah0404.network.MissionLogListResponse
import com.aihealthcare.ah0404.network.PredictionInputsResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryResponse
import com.aihealthcare.ah0404.network.RiskLatestResponse
import com.aihealthcare.ah0404.network.RiskReassessRequest
import com.aihealthcare.ah0404.network.RiskReassessResponse
import com.aihealthcare.ah0404.network.ScoreSimulationResponse
import com.aihealthcare.ah0404.network.StampsResponse
import com.aihealthcare.ah0404.network.WalkingDailyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 신체 정보 편집(#기록탭 §2) 검증 — 입력 가드 + 저장 후 재평가 배선.
 *
 *  재평가 배선의 계약: 저장 성공 시에만 reassess 를 부른다(새 예측 생성 — 이게 없으면 프로필을
 *  고쳐도 점수가 영영 안 바뀐다). 재평가는 부가 동작이라 실패해도 저장 성공·편집 종료는 그대로다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthInfoViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeApi(private val failSave: Boolean = false) : HealthProfileApi {
        override suspend fun getLatest() = HealthProfileLatest()
        override suspend fun updateProfile(body: HealthProfilePatchRequest): HealthProfileLatest {
            if (failSave) error("save failed")
            return HealthProfileLatest()
        }
    }

    /** reassess 만 기록하고 나머지는 이 테스트에서 안 쓰는 RecordApi fake. */
    private class FakeRecordApi(private val failReassess: Boolean = false) : RecordApi {
        var reassessCalls = 0
        var lastWindowDays: Int? = null

        override suspend fun reassessRiskPrediction(body: RiskReassessRequest): RiskReassessResponse {
            reassessCalls++
            lastWindowDays = body.activityWindowDays
            if (failReassess) error("reassess failed")
            return RiskReassessResponse(predictionId = 1, muscleScore = 72, scoreBand = "good")
        }

        override suspend fun getRiskHistory(limit: Int): RiskHistoryResponse = error("unused")
        override suspend fun getMissionLogs(date: String?, from: String?, to: String?): MissionLogListResponse =
            error("unused")
        override suspend fun getPredictionInputs(): PredictionInputsResponse = error("unused")
        override suspend fun getWalkingDaily(days: Int): WalkingDailyResponse = error("unused")
        override suspend fun getChallengeTotals(): ChallengeTotalsResponse = error("unused")
        override suspend fun getStamps(month: String): StampsResponse = error("unused")
        override suspend fun getLatestPrediction(): RiskLatestResponse = error("unused")
        override suspend fun getScoreSimulation(): ScoreSimulationResponse = error("unused")
    }

    @Test
    fun rejects_nonpositive_height_or_weight_before_network() {
        val vm = HealthInfoViewModel(FakeApi(), FakeRecordApi())

        vm.save("0", "60", "", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)

        vm.save("170", "", "", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)
    }

    @Test
    fun save_success_triggers_reassess_and_immediate_message() = runTest {
        val record = FakeRecordApi()
        val vm = HealthInfoViewModel(FakeApi(), record)
        var savedCallback = false

        vm.save("170", "65", "", "none") { savedCallback = true }
        advanceUntilIdle()

        assertEquals("저장 성공이면 재평가를 정확히 1회 부른다(새 예측 생성)", 1, record.reassessCalls)
        assertEquals("최근 일주일 활동 창으로 재평가한다(서버 계약 7|14)", 7, record.lastWindowDays)
        assertEquals("저장했어요. 근육 건강 정보에 바로 반영됐어요.", vm.savedMessage)
        assertTrue("편집 종료 콜백 호출", savedCallback)
    }

    @Test
    fun save_failure_does_not_reassess() = runTest {
        val record = FakeRecordApi()
        val vm = HealthInfoViewModel(FakeApi(failSave = true), record)
        var savedCallback = false

        vm.save("170", "65", "", "none") { savedCallback = true }
        advanceUntilIdle()

        assertEquals("저장이 실패하면 재평가하지 않는다(옛 프로필로 새 예측을 만들면 안 됨)", 0, record.reassessCalls)
        assertEquals(false, savedCallback)
    }

    @Test
    fun reassess_failure_keeps_save_success_with_fallback_message() = runTest {
        val record = FakeRecordApi(failReassess = true)
        val vm = HealthInfoViewModel(FakeApi(), record)
        var savedCallback = false

        vm.save("170", "65", "", "none") { savedCallback = true }
        advanceUntilIdle()

        assertEquals(1, record.reassessCalls)
        assertEquals(
            "재평가 실패(65세 미만 422 등)여도 저장은 유효 — 종전 안내로 폴백",
            "저장했어요. 다음 근육 건강 정보부터 반영돼요.",
            vm.savedMessage,
        )
        assertTrue("재평가 실패가 편집 종료를 막지 않는다", savedCallback)
    }
}
