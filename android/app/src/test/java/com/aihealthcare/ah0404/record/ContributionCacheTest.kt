package com.aihealthcare.ah0404.record

import android.content.Context
import com.aihealthcare.ah0404.network.AuthSession
import com.aihealthcare.ah0404.network.ChallengeTotalsResponse
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.ContributionItemDto
import com.aihealthcare.ah0404.network.MissionLogListResponse
import com.aihealthcare.ah0404.network.PredictionFeedbackRequest
import com.aihealthcare.ah0404.network.PredictionInputsResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryResponse
import com.aihealthcare.ah0404.network.RiskLatestResponse
import com.aihealthcare.ah0404.network.RiskReassessRequest
import com.aihealthcare.ah0404.network.RiskReassessResponse
import com.aihealthcare.ah0404.network.ScoreSimulationResponse
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.network.StampsResponse
import com.aihealthcare.ah0404.network.StsHistoryResponse
import com.aihealthcare.ah0404.network.WalkingDailyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 기여도 로컬 캐시(#406)의 계약 회귀 테스트 — 리뷰 P1.
 *
 *  이 캐시가 필요한 이유 자체가 계약이다: 서버는 기여도를 **저장하지 않으므로**(#411) 조회 응답
 *  (`GET /me/latest`)에는 항상 빈 목록이 오고, 기여도는 새로 계산하는 응답(온보딩 create·재평가)
 *  에만 실린다. 따라서 화면이 조회 응답에서 읽으면 카드가 **한 번도** 뜨지 않는다.
 *
 *  가장 실수하기 쉬운 자리는 하루 1회 정책(#388)이다: 같은 날 재평가를 다시 부르면 반드시
 *  `recalculated=false` + 빈 목록이 오는데, 그걸로 캐시를 덮으면 사용자는 오늘 하루 카드를 잃는다.
 *  아래 [빈 목록 저장은 기존 캐시를 지우지 않는다] 와 화면 경로 테스트가 그 지점을 고정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ContributionCacheTest {

    // refreshScore() 는 viewModelScope 를 쓰므로 Main 을 테스트 디스패처로 교체한다.
    private val dispatcher = StandardTestDispatcher()

    // 어댑터 내부 저장 위치 — 손상/키 제거를 화이트박스로 확인하려 구현과 같은 값을 참조한다(바뀌면 회귀로 잡히게).
    private val prefsName = "contribution_cache"
    private fun cacheKey(userId: Int) = "user_${userId}_contributions"

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // persistentUserId 를 null 로 되돌려 테스트 간 스코프 누수를 막는다.
        SessionStore.clearAuthentication(context)
        SharedPrefsContributionCache.clearAll(context)
    }

    /** 완료된 소셜 계정으로 로그인 → persistentUserId=userId(영속 대상). */
    private fun loginAs(userId: Int) = SessionStore.applyLogin(
        context,
        AuthSession(accessToken = "t", isGuest = false, onboardingCompleted = true, userId = userId),
    )

    /** 게스트 로그인 → persistentUserId=null(비영속). */
    private fun loginAsGuest() = SessionStore.applyLogin(
        context,
        AuthSession(accessToken = "g", isGuest = true, onboardingCompleted = true),
    )

    private fun items(vararg pairs: Pair<String, Double>) =
        pairs.map { (feature, effect) -> ContributionItemDto(feature = feature, effectOnScoreLogOdds = effect) }

    private fun rawPrefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // ── 어댑터 계약 ────────────────────────────────────────────────────────────

    @Test
    fun `저장한 기여도를 새 인스턴스에서 prediction_id 로 복원한다`() {
        loginAs(1)
        SharedPrefsContributionCache(context).save(7, items("waist_cm" to -0.83, "musc_days" to 0.41))

        // 새 인스턴스 로드 = 앱 재시작 모사(정적 상태 없이 디스크에서만 복원).
        val restored = SharedPrefsContributionCache(context).load(7)

        assertEquals(listOf("waist_cm", "musc_days"), restored.map { it.feature })
        assertEquals(listOf(-0.83, 0.41), restored.map { it.effectOnScoreLogOdds })
    }

    @Test
    fun `빈 목록 저장은 기존 캐시를 지우지 않는다`() {
        // 하루 1회 정책(#388): 같은 날 재평가를 다시 부르면 recalculated=false + 빈 목록이 온다.
        //   그걸로 덮어쓰면 사용자는 오늘 하루 기여도 카드를 잃는다 — 리뷰가 지목한 가장 위험한 자리.
        loginAs(1)
        val cache = SharedPrefsContributionCache(context)
        cache.save(7, items("musc_days" to 0.41))

        cache.save(7, emptyList())

        assertEquals("빈 목록은 저장 자체를 하지 않는다", listOf("musc_days"), cache.load(7).map { it.feature })
    }

    @Test
    fun `다른 prediction_id 의 기여도가 새 예측에 붙지 않는다`() {
        // 재계산으로 새 예측이 생겼는데 기여도가 없으면(구버전 서버 등) 옛 예측 값을 보여주면 안 된다.
        loginAs(1)
        val cache = SharedPrefsContributionCache(context)
        cache.save(7, items("musc_days" to 0.41))

        assertTrue("캐시에 없는 예측은 빈 목록 = 카드 미표시", cache.load(8).isEmpty())
        assertEquals(listOf("musc_days"), cache.load(7).map { it.feature })
    }

    @Test
    fun `사용자별로 분리 저장돼 다른 계정 기여도를 침범하지 않는다`() {
        loginAs(1); SharedPrefsContributionCache(context).save(7, items("musc_days" to 0.41))
        loginAs(2); SharedPrefsContributionCache(context).save(7, items("waist_cm" to -0.9))

        loginAs(2)
        assertEquals(listOf("waist_cm"), SharedPrefsContributionCache(context).load(7).map { it.feature })
        loginAs(1)
        assertEquals(
            "같은 prediction_id 라도 1 번 사용자 저장분은 2 번이 덮지 않는다",
            listOf("musc_days"),
            SharedPrefsContributionCache(context).load(7).map { it.feature },
        )
    }

    @Test
    fun `게스트·비로그인은 저장하지 않고 항상 빈 목록을 반환한다`() {
        loginAsGuest() // persistentUserId=null
        val cache = SharedPrefsContributionCache(context)
        cache.save(7, items("musc_days" to 0.41)) // 무시돼야 한다

        assertTrue("게스트 저장은 무시", cache.load(7).isEmpty())
        loginAs(9)
        assertTrue("게스트 기여도가 다음 사용자에게 오배분되지 않는다", SharedPrefsContributionCache(context).load(7).isEmpty())
    }

    @Test
    fun `로그아웃·탈퇴 시 clearAll 이 모든 계정의 캐시를 지운다`() {
        loginAs(1); SharedPrefsContributionCache(context).save(7, items("musc_days" to 0.41))
        loginAs(2); SharedPrefsContributionCache(context).save(9, items("waist_cm" to -0.9))

        SharedPrefsContributionCache.clearAll(context)

        assertFalse("1 번 키 제거", rawPrefs().contains(cacheKey(1)))
        assertFalse("2 번 키 제거", rawPrefs().contains(cacheKey(2)))
        loginAs(1)
        assertTrue(SharedPrefsContributionCache(context).load(7).isEmpty())
    }

    @Test
    fun `손상된 JSON 은 예외 없이 폐기하고 빈 목록으로 폴백한다`() {
        loginAs(1)
        rawPrefs().edit().putString(cacheKey(1), "{ 이건 깨진 json 이라 파싱 실패").apply()

        assertTrue("손상 값은 크래시 없이 빈 목록", SharedPrefsContributionCache(context).load(7).isEmpty())
        assertFalse("손상된 키는 폐기돼 다음 로드가 반복 실패하지 않는다", rawPrefs().contains(cacheKey(1)))
    }

    @Test
    fun `상한 5 를 넘기면 최신 5건만 남기고 오래된 예측부터 버린다`() {
        loginAs(1)
        val cache = SharedPrefsContributionCache(context)
        (1..7).forEach { cache.save(it, items("musc_days" to it.toDouble())) }

        assertTrue("가장 오래된 예측 1·2 는 버려진다", cache.load(1).isEmpty() && cache.load(2).isEmpty())
        assertEquals(3.0, cache.load(3).single().effectOnScoreLogOdds, 0.0)
        assertEquals(7.0, cache.load(7).single().effectOnScoreLogOdds, 0.0)
    }

    // ── 화면 경로(RecordViewModel) ─────────────────────────────────────────────

    /** 기여도 경로에 필요한 것만 지정하는 RecordApi fake. 나머지는 기본 응답. */
    private class FakeRecordApi(
        var latestPredictionId: Int,
        var reassess: () -> RiskReassessResponse = { error("이 테스트는 재평가를 부르지 않는다") },
    ) : RecordApi {
        override suspend fun getLatestPrediction() = RiskLatestResponse(
            predictionId = latestPredictionId,
            muscleScore = 72,
            scoreBand = "good",
        )
        override suspend fun reassessRiskPrediction(body: RiskReassessRequest) = reassess()
        override suspend fun getRiskHistory(limit: Int) = RiskHistoryResponse()
        override suspend fun getMissionLogs(date: String?, from: String?, to: String?) = MissionLogListResponse()
        override suspend fun getPredictionInputs() = PredictionInputsResponse()
        override suspend fun getWalkingDaily(days: Int) = WalkingDailyResponse()
        override suspend fun getChallengeTotals() = ChallengeTotalsResponse()
        override suspend fun getStamps(month: String) = StampsResponse(month = month)
        override suspend fun getStsHistory(limit: Int) = StsHistoryResponse()
        override suspend fun getScoreSimulation() = ScoreSimulationResponse()
        override suspend fun getCohortDistribution(): CohortDistributionResponse = error("이 테스트는 또래 분포를 부르지 않는다")
        override suspend fun submitPredictionFeedback(predictionId: Int, body: PredictionFeedbackRequest) = Unit
    }

    @Test
    fun `조회 응답만으로는 카드가 뜨지 않는다`() = runBlocking {
        // 리뷰 P1 의 증상 그 자체: /me/latest 는 기여도를 싣지 않으므로 캐시가 비면 표시할 행이 없다.
        loginAs(1)
        val vm = RecordViewModel(FakeRecordApi(latestPredictionId = 7), SharedPrefsContributionCache(context))

        vm.refresh()

        assertTrue(contributionRows(vm.muscleScore?.contributions.orEmpty()).isEmpty())
    }

    @Test
    fun `재계산 응답의 기여도가 그대로 화면 행이 된다`() = runTest(dispatcher) {
        loginAs(1)
        // 재평가가 새 예측 8 을 만들며 기여도를 함께 내려준다 — 이후 조회도 8 을 최신으로 준다.
        val api = FakeRecordApi(latestPredictionId = 8) {
            RiskReassessResponse(
                predictionId = 8,
                muscleScore = 72,
                recalculated = true,
                contributions = items("waist_cm" to -0.83, "musc_days" to 0.41),
            )
        }
        val vm = RecordViewModel(api, SharedPrefsContributionCache(context))

        vm.refreshScore() // APPLIED → 내부에서 refresh() 까지 이어진다
        advanceUntilIdle()

        assertEquals(ScoreRefreshState.APPLIED, vm.scoreRefresh)
        assertEquals(
            listOf("허리둘레", "근력 운동"), // |영향| 큰 순
            contributionRows(vm.muscleScore?.contributions.orEmpty()).map { it.label },
        )
    }

    @Test
    fun `같은 날 재평가로 빈 목록이 와도 카드가 유지된다`() = runTest(dispatcher) {
        loginAs(1)
        val cache = SharedPrefsContributionCache(context)
        cache.save(8, items("waist_cm" to -0.83)) // 어제 계산된 예측 8 의 기여도
        // 하루 1회 정책(#388): 오늘 이미 계산했으므로 기존 예측(8) + recalculated=false + 빈 기여도.
        val api = FakeRecordApi(latestPredictionId = 8) {
            RiskReassessResponse(predictionId = 8, muscleScore = 72, recalculated = false, contributions = emptyList())
        }
        val vm = RecordViewModel(api, cache)

        vm.refreshScore() // ALREADY_TODAY — 빈 목록이 캐시를 덮으면 안 된다
        advanceUntilIdle()
        vm.refresh()

        assertEquals(ScoreRefreshState.ALREADY_TODAY, vm.scoreRefresh)
        assertEquals(
            "빈 목록이 기존 기여도를 덮으면 오늘 하루 카드가 사라진다",
            listOf("허리둘레"),
            contributionRows(vm.muscleScore?.contributions.orEmpty()).map { it.label },
        )
    }
}
