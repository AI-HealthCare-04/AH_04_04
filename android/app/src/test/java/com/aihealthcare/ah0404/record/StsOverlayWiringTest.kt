package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.ChallengeTotalsResponse
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.MissionLogListResponse
import com.aihealthcare.ah0404.network.PredictionFeedbackRequest
import com.aihealthcare.ah0404.network.PredictionInputsResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryResponse
import com.aihealthcare.ah0404.network.RiskLatestResponse
import com.aihealthcare.ah0404.network.RiskReassessRequest
import com.aihealthcare.ah0404.network.RiskReassessResponse
import com.aihealthcare.ah0404.network.ScoreSimulationResponse
import com.aihealthcare.ah0404.network.StampsResponse
import com.aihealthcare.ah0404.network.StsAssessmentItem
import com.aihealthcare.ah0404.network.StsHistoryResponse
import com.aihealthcare.ah0404.network.WalkingDailyResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §3.4 근력 기능 안전망 오버레이(#406)의 **입력 배선** 회귀 테스트.
 *
 *  이 카드는 컴포넌트가 구현돼 있었는데도 화면에 **한 번도 뜨지 않았다** — `RecordViewModel` 이
 *  `stsSeconds = null`, `bmi = null` 을 하드코딩하고 있었기 때문이다("5STS 노출 API 없음" 주석이
 *  #353 으로 API 가 생긴 뒤에도 남아 있었다). 빌드·테스트가 전부 통과하면서 기능만 죽어 있는
 *  유형이라, 값이 화면 상태까지 실제로 도달하는지를 단언한다.
 *
 *  발화 규칙 자체(5STS >= 12초 AND band != caution, BMI >= 25 는 강조 티어)는 [StsSafetyCard] 에 있고
 *  여기서는 그 입력이 채워지는지만 본다.
 */
class StsOverlayWiringTest {

    private class FakeRecordApi(
        private val sts: List<StsAssessmentItem> = emptyList(),
        private val prefill: PredictionInputsResponse = PredictionInputsResponse(),
        private val stsFails: Boolean = false,
    ) : RecordApi {
        override suspend fun getStsHistory(limit: Int): StsHistoryResponse {
            if (stsFails) error("sts down")
            return StsHistoryResponse(sts)
        }
        override suspend fun getPredictionInputs() = prefill
        override suspend fun getLatestPrediction() = RiskLatestResponse(
            predictionId = 1,
            muscleScore = 90,
            scoreBand = "good",
        )
        override suspend fun getRiskHistory(limit: Int) = RiskHistoryResponse()
        override suspend fun getMissionLogs(date: String?, from: String?, to: String?) = MissionLogListResponse()
        override suspend fun getWalkingDaily(days: Int) = WalkingDailyResponse()
        override suspend fun getChallengeTotals() = ChallengeTotalsResponse()
        override suspend fun getStamps(month: String) = StampsResponse(month = month)
        override suspend fun getScoreSimulation() = ScoreSimulationResponse()
        override suspend fun getCohortDistribution(): CohortDistributionResponse = error("이 테스트는 또래 분포를 부르지 않는다")
        override suspend fun reassessRiskPrediction(body: RiskReassessRequest): RiskReassessResponse =
            error("이 테스트는 재평가를 부르지 않는다")
        override suspend fun submitPredictionFeedback(predictionId: Int, body: PredictionFeedbackRequest) = Unit
    }

    private fun sts(createdAt: String, sec: Double) = StsAssessmentItem(
        physicalAssessmentId = 1,
        chairStand5TimeSec = sec,
        createdAt = createdAt,
    )

    // ── 순수 함수 ─────────────────────────────────────────────────────────────

    @Test
    fun `최신 측정은 목록 순서가 아니라 측정 시각으로 고른다`() {
        // 서버는 최신순으로 주지만, 순서가 뒤집히면 옛 측정으로 카드가 뜨거나 안 뜬다.
        val items = listOf(
            sts("2026-07-01T09:00:00+09:00", 9.0),
            sts("2026-07-31T09:00:00+09:00", 13.0), // 가장 최근
            sts("2026-07-15T09:00:00+09:00", 11.0),
        )
        assertEquals(13.0, latestStsSeconds(items)!!, 0.0)
    }

    @Test
    fun `측정 이력이 없으면 null 이라 카드가 뜨지 않는다`() {
        assertNull(latestStsSeconds(emptyList()))
    }

    @Test
    fun `BMI 는 키·몸무게로 계산하고 값이 없거나 비정상이면 null 이다`() {
        // 이슈 #406 의 실측 예시(남 72세, 78kg·168cm) — 강조 티어(25 이상)에 해당한다.
        assertEquals(27.6, bmiOf(heightCm = 168.0, weightKg = 78.0)!!, 0.05)
        assertNull("키 미상", bmiOf(heightCm = null, weightKg = 78.0))
        assertNull("몸무게 미상", bmiOf(heightCm = 168.0, weightKg = null))
        // 0 이나 음수를 그대로 계산하면 무한대·음수 BMI 가 나와 강조 티어가 임의로 켜진다.
        assertNull("키 0", bmiOf(heightCm = 0.0, weightKg = 78.0))
        assertNull("몸무게 0", bmiOf(heightCm = 168.0, weightKg = 0.0))
    }

    @Test
    fun `BMI 는 반올림 전 원본으로 계산한다`() {
        // 원본 169.6cm·72.4kg 는 BMI 25.17 로 강조 대상(25 이상)이지만, 정수로 반올림한
        //   170cm·72kg 로 계산하면 24.91 이 되어 강조 티어가 뒤집힌다.
        assertEquals(25.17, bmiOf(heightCm = 169.6, weightKg = 72.4)!!, 0.01)
        assertEquals("반올림 값으로 계산하면 경계 아래로 떨어진다", 24.91, bmiOf(170.0, 72.0)!!, 0.01)
    }

    // ── 화면 상태까지의 배선 ───────────────────────────────────────────────────

    @Test
    fun `5STS 와 BMI 가 화면 상태로 전달된다`() = runBlocking {
        val api = FakeRecordApi(
            sts = listOf(sts("2026-07-31T09:00:00+09:00", 13.0)),
            prefill = PredictionInputsResponse(heightCm = 168.0, weightKg = 78.0),
        )
        val vm = RecordViewModel(api)

        vm.refresh()

        // 이 두 줄이 하드코딩 null 이던 것이 오버레이가 뜨지 않던 원인이다.
        assertEquals(13.0, vm.muscleScore?.stsSeconds!!, 0.0)
        assertEquals(27.6, vm.muscleScore?.bmi!!, 0.05)
    }

    @Test
    fun `5STS 조회가 실패해도 점수 섹션은 그대로 표시된다`() = runBlocking {
        // 다른 섹션과 독립(또래 분포와 같은 방침) — 오버레이만 빠지고 점수는 남아야 한다.
        val api = FakeRecordApi(stsFails = true, prefill = PredictionInputsResponse(heightCm = 168.0, weightKg = 78.0))
        val vm = RecordViewModel(api)

        vm.refresh()

        assertNull("측정값을 모르면 카드는 뜨지 않는다", vm.muscleScore?.stsSeconds)
        assertEquals("점수는 영향받지 않는다", 90, vm.muscleScore?.score)
    }
}
