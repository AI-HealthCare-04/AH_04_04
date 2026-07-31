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
import com.aihealthcare.ah0404.profile.HealthInfoViewModel.ScoreRefreshState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 신체 정보 편집(#기록탭 §2) 검증 — 입력 가드 + 저장/재평가 **상태 분리**(리뷰 #294 P1).
 *
 *  계약: PATCH 성공 즉시 저장을 확정(saving 해제·onSaved·"저장했어요")하고, 재평가는 scoreRefresh
 *  로 따로 흐른다. 판정 경계 — 점수 존재=APPLIED / 422·점수 미제공=NOT_ELIGIBLE(재시도 무의미) /
 *  네트워크·5xx=FAILED(재시도 제공). 저장 실패 시 재평가는 아예 부르지 않는다.
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

    /** reassess 동작을 시나리오별로 지정하는 RecordApi fake. 나머지 메서드는 이 테스트에서 안 쓴다. */
    private class FakeRecordApi : RecordApi {
        var reassessCalls = 0
        var lastWindowDays: Int? = null

        /** null 이면 즉시 [response] 반환, 지정 시 완료될 때까지 대기(지연 경계 테스트용). */
        var gate: CompletableDeferred<Unit>? = null

        /** 호출 시 던질 예외(1회성 아님). null 이면 [response] 반환. */
        var throwOnReassess: Exception? = null
        var response = RiskReassessResponse(predictionId = 1, muscleScore = 72, scoreBand = "good")

        override suspend fun reassessRiskPrediction(body: RiskReassessRequest): RiskReassessResponse {
            reassessCalls++
            lastWindowDays = body.activityWindowDays
            gate?.await()
            throwOnReassess?.let { throw it }
            return response
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

    private fun http422() = HttpException(Response.error<Any>(422, "{}".toResponseBody()))

    @Test
    fun rejects_nonpositive_height_or_weight_before_network() {
        val vm = HealthInfoViewModel(FakeApi(), FakeRecordApi())

        vm.save("0", "60", "", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)

        vm.save("170", "", "", "none") {}
        assertEquals("키·몸무게를 0보다 큰 값으로 입력해 주세요.", vm.saveError)
    }

    @Test
    fun slow_reassess_does_not_block_save_completion() = runTest {
        // 리뷰 #294 P1 핵심 경계: 재평가가 아무리 늦어도(타임아웃 직전까지) 저장은 이미 확정돼 있어야 한다.
        val record = FakeRecordApi().apply { gate = CompletableDeferred() }
        val vm = HealthInfoViewModel(FakeApi(), record)
        var savedCallback = false

        vm.save("170", "65", "", "none") { savedCallback = true }
        advanceUntilIdle() // PATCH 완료·재평가는 gate 에서 대기 중

        assertFalse("재평가 대기 중에도 '저장 중…'에 갇히지 않는다", vm.saving)
        assertTrue("편집 종료 콜백은 저장 확정 시점에 호출", savedCallback)
        assertEquals("저장했어요.", vm.savedMessage)
        assertEquals("재평가는 별도 상태로 진행 중", ScoreRefreshState.IN_PROGRESS, vm.scoreRefresh)

        record.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("점수가 오면 즉시 반영으로 확정", ScoreRefreshState.APPLIED, vm.scoreRefresh)
        assertEquals("최근 일주일 활동 창(서버 계약 7|14)", 7, record.lastWindowDays)
        assertEquals(1, record.reassessCalls)
    }

    @Test
    fun save_failure_does_not_reassess() = runTest {
        val record = FakeRecordApi()
        val vm = HealthInfoViewModel(FakeApi(failSave = true), record)
        var savedCallback = false

        vm.save("170", "65", "", "none") { savedCallback = true }
        advanceUntilIdle()

        assertEquals("저장 실패면 재평가하지 않는다(옛 프로필로 새 예측 생성 방지)", 0, record.reassessCalls)
        assertFalse(savedCallback)
        assertNull("재평가 상태도 시작되지 않는다", vm.scoreRefresh)
    }

    @Test
    fun http422_is_not_eligible_not_failed() = runTest {
        // 65세 미만 등 — 재시도해도 결과가 같으므로 FAILED(재시도 버튼)가 아니라 NOT_ELIGIBLE.
        val record = FakeRecordApi().apply { throwOnReassess = http422() }
        val vm = HealthInfoViewModel(FakeApi(), record)

        vm.save("170", "65", "", "none") {}
        advanceUntilIdle()

        assertEquals(ScoreRefreshState.NOT_ELIGIBLE, vm.scoreRefresh)
        assertEquals("저장 자체는 성공 안내", "저장했어요.", vm.savedMessage)
    }

    @Test
    fun success_without_score_is_not_eligible() = runTest {
        // 2xx 라도 muscle_score 가 없으면 '바로 반영'으로 확정하지 않는다(리뷰 #294).
        val record = FakeRecordApi().apply { response = RiskReassessResponse(predictionId = 2, muscleScore = null) }
        val vm = HealthInfoViewModel(FakeApi(), record)

        vm.save("170", "65", "", "none") {}
        advanceUntilIdle()

        assertEquals(ScoreRefreshState.NOT_ELIGIBLE, vm.scoreRefresh)
    }

    @Test
    fun network_failure_is_failed_and_retry_recovers() = runTest {
        val record = FakeRecordApi().apply { throwOnReassess = IOException("timeout") }
        val vm = HealthInfoViewModel(FakeApi(), record)

        vm.save("170", "65", "", "none") {}
        advanceUntilIdle()
        assertEquals("네트워크 실패는 재시도 가능한 FAILED", ScoreRefreshState.FAILED, vm.scoreRefresh)

        record.throwOnReassess = null // 네트워크 회복
        vm.retryScoreRefresh()
        advanceUntilIdle()
        assertEquals("재시도('점수 다시 계산')로 회복", ScoreRefreshState.APPLIED, vm.scoreRefresh)
        assertEquals(2, record.reassessCalls)
    }

    @Test
    fun footer_text_matches_each_state() {
        // 화면 하단 고정 안내(리뷰 #294 동반 수정) — 상태별 문구 회귀 방지. 종전
        // "다음 근육 건강 정보부터 반영됩니다"(이행 불가 안내)가 되살아나지 않는지도 겸검한다.
        assertEquals("저장하면 수정한 정보로 근육 건강 점수를 바로 다시 계산해요.", scoreRefreshFooterText(null))
        assertEquals("저장한 정보로 근육 건강 점수를 다시 계산하고 있어요…", scoreRefreshFooterText(ScoreRefreshState.IN_PROGRESS))
        assertEquals("근육 건강 정보에 바로 반영됐어요.", scoreRefreshFooterText(ScoreRefreshState.APPLIED))
        assertEquals("정보는 저장됐어요. 지금은 근육 점수 제공 대상이 아니에요.", scoreRefreshFooterText(ScoreRefreshState.NOT_ELIGIBLE))
        assertEquals(
            "정보는 저장됐어요. 점수 다시 계산에 실패했어요 — 아래 버튼으로 다시 시도해 주세요.",
            scoreRefreshFooterText(ScoreRefreshState.FAILED),
        )
    }
}
