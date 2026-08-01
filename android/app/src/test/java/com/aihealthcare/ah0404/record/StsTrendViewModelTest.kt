package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.ActivityProfile
import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.PhysicalAssessmentResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.StsAssessmentItem
import com.aihealthcare.ah0404.network.StsHistoryResponse
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 5STS 재측정 저장·이력(#353): reassessment 독립 제출(session_id=null), 성공 시 이력 재조회,
 * 실패 시 측정값 보관 후 저장만 재시도(재측정 강요 금지)를 검증.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StsTrendViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private open class FakeRecordApi(var items: List<StsAssessmentItem> = emptyList()) : RecordApi {
        var historyCalls = 0
        override suspend fun getStsHistory(limit: Int): StsHistoryResponse {
            historyCalls++
            return StsHistoryResponse(assessments = items)
        }
        override suspend fun getRiskHistory(limit: Int) = TODO()
        override suspend fun getMissionLogs(date: String?, from: String?, to: String?) = TODO()
        override suspend fun getPredictionInputs() = TODO()
        override suspend fun getWalkingDaily(days: Int) = TODO()
        override suspend fun getChallengeTotals() = TODO()
        override suspend fun getStamps(month: String) = TODO()
        override suspend fun getLatestPrediction() = TODO()
        override suspend fun getScoreSimulation() = TODO()
        override suspend fun getCohortDistribution() = TODO()
        override suspend fun reassessRiskPrediction(body: com.aihealthcare.ah0404.network.RiskReassessRequest) = TODO()
    }

    private class FakeAssessmentApi(private val fail: Boolean = false) : OnboardingApi {
        val requests = mutableListOf<PhysicalAssessmentRequest>()
        override suspend fun createPhysicalAssessment(body: PhysicalAssessmentRequest): PhysicalAssessmentResponse {
            requests += body
            if (fail) throw RuntimeException("network down")
            return PhysicalAssessmentResponse(
                physicalAssessmentId = 1,
                usedForLevelSetting = false,
                activityProfile = ActivityProfile(currentLevel = "easy", levelReason = "sts_norm"),
            )
        }
        override suspend fun guestLogin() = TODO()
        override suspend fun loginGoogle(body: SocialLoginRequest) = TODO()
        override suspend fun loginKakao(body: SocialLoginRequest) = TODO()
        override suspend fun getTerms() = TODO()
        override suspend fun agreeTerms(body: AgreementsRequest) = TODO()
        override suspend fun createSession(body: SessionCreateRequest) = TODO()
        override suspend fun skipHealthCheck(sessionId: Int) = TODO()
        override suspend fun createHealthProfile(body: HealthProfileRequest) = TODO()
        override suspend fun createRiskPrediction(body: RiskPredictionRequest) = TODO()
        override suspend fun getHome() = TODO()
    }

    private fun item(id: Int, sec: Double) = StsAssessmentItem(
        physicalAssessmentId = id,
        assessmentType = "reassessment",
        chairStand5TimeSec = sec,
        createdAt = "2026-07-31T10:00:00+09:00",
    )

    @Test
    fun `저장 성공 - reassessment·독립 제출로 보내고 이력을 재조회한다`() = runTest(dispatcher) {
        val recordApi = FakeRecordApi()
        val assessmentApi = FakeAssessmentApi()
        val vm = StsTrendViewModel(recordApi, assessmentApi)

        recordApi.items = listOf(item(1, 10.8))
        vm.submit(10.8)
        advanceUntilIdle()

        val req = assessmentApi.requests.single()
        assertEquals("reassessment", req.assessmentType)
        assertEquals(null, req.sessionId) // 온보딩 세션 없는 독립 제출(#180 허용 경로)
        assertEquals(10.8, req.chairStand5TimeSec)
        assertEquals(1, recordApi.historyCalls) // 성공 → 이력 재조회
        assertEquals(1, vm.history.size)
        assertFalse(vm.saveError)
    }

    @Test
    fun `저장 실패 - 측정값 보관 후 재시도만으로 같은 값이 다시 간다`() = runTest(dispatcher) {
        val recordApi = FakeRecordApi()
        val failing = FakeAssessmentApi(fail = true)
        val vm = StsTrendViewModel(recordApi, failing)

        vm.submit(12.5)
        advanceUntilIdle()
        assertTrue(vm.saveError)
        assertEquals(0, recordApi.historyCalls) // 실패면 재조회 없음

        vm.retrySubmit()
        advanceUntilIdle()
        assertEquals(2, failing.requests.size)
        assertEquals(12.5, failing.requests[1].chairStand5TimeSec) // 재측정 없이 같은 값 재전송
    }

    @Test
    fun `이력 조회 실패는 빈 목록으로 흡수한다`() = runTest(dispatcher) {
        val recordApi = object : FakeRecordApi() {
            override suspend fun getStsHistory(limit: Int): StsHistoryResponse = throw RuntimeException("down")
        }
        val vm = StsTrendViewModel(recordApi, FakeAssessmentApi())
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.loaded)
        assertEquals(0, vm.history.size)
    }
}
