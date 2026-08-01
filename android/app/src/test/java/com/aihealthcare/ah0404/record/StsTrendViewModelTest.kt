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
import com.aihealthcare.ah0404.network.SessionResponse
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
        var sessionCalls = 0
        override suspend fun createSession(body: SessionCreateRequest): SessionResponse {
            sessionCalls++
            return SessionResponse(sessionId = 42, status = "started")
        }
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
        assertEquals(42, req.sessionId) // 멱등 세션 경유 제출(리뷰 #355 P1 — 세션당 1건 유니크 #180)
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
        // 멱등(리뷰 #355 P1): 재시도는 같은 세션 id 를 재사용한다 — 세션 생성은 1회뿐.
        assertEquals(1, failing.sessionCalls)
        assertEquals(failing.requests[0].sessionId, failing.requests[1].sessionId)
    }

    @Test
    fun `이력 조회 실패 - 기존 목록 유지 + loadError 구분, 재조회로 복구`() = runTest(dispatcher) {
        var fail = false
        val recordApi = object : FakeRecordApi() {
            override suspend fun getStsHistory(limit: Int): StsHistoryResponse {
                if (fail) throw RuntimeException("down")
                return StsHistoryResponse(assessments = listOf(item(1, 10.8)))
            }
        }
        val vm = StsTrendViewModel(recordApi, FakeAssessmentApi())

        vm.load(); advanceUntilIdle()
        assertEquals(1, vm.history.size)
        assertFalse(vm.loadError)

        fail = true
        vm.load(); advanceUntilIdle()
        assertEquals(1, vm.history.size) // 기존 목록 유지(리뷰 #355 P2) — '측정 전'으로 위장하지 않는다
        assertTrue(vm.loadError)

        fail = false
        vm.load(); advanceUntilIdle() // 카드의 '다시 불러오기'
        assertFalse(vm.loadError)
        assertEquals(1, vm.history.size)
    }

    @Test
    fun `최초 조회 실패 - 빈 목록 + loadError(미측정과 구분)`() = runTest(dispatcher) {
        val recordApi = object : FakeRecordApi() {
            override suspend fun getStsHistory(limit: Int): StsHistoryResponse = throw RuntimeException("down")
        }
        val vm = StsTrendViewModel(recordApi, FakeAssessmentApi())
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.loaded)
        assertTrue(vm.loadError) // 화면은 '기록 없음'이 아니라 '불러오지 못함 + 다시 불러오기'를 그린다
        assertEquals(0, vm.history.size)
    }

    @Test
    fun `미해결 제출 중 새 측정값은 받지 않고 보관 값을 재시도한다`() = runTest(dispatcher) {
        val failing = FakeAssessmentApi(fail = true)
        val vm = StsTrendViewModel(FakeRecordApi(), failing)

        vm.submit(12.5) // 실패 → pending(12.5, 세션 42) 보관
        advanceUntilIdle()
        assertTrue(vm.saveError)

        vm.submit(9.9) // UI 방어를 뚫고 들어와도(리뷰 #355 3차) 새 값을 받지 않는다
        advanceUntilIdle()
        assertEquals(12.5, failing.requests.last().chairStand5TimeSec) // 보관 값 재시도
        assertEquals(1, failing.sessionCalls) // 세션도 그대로 — 완료 불명확 세션에 새 payload 금지
    }
}
