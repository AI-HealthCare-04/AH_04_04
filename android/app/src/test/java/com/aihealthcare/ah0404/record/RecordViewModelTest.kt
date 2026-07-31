package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.ChallengeTotalsResponse
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.MissionLogItem
import com.aihealthcare.ah0404.network.MissionLogListResponse
import com.aihealthcare.ah0404.network.RiskLatestResponse
import com.aihealthcare.ah0404.network.RiskReassessRequest
import com.aihealthcare.ah0404.network.RiskReassessResponse
import com.aihealthcare.ah0404.network.ScoreSimPointDto
import com.aihealthcare.ah0404.network.ScoreSimulationResponse
import com.aihealthcare.ah0404.network.PredictionInputsResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.network.RiskHistoryResponse
import com.aihealthcare.ah0404.network.StampsResponse
import com.aihealthcare.ah0404.network.WalkingDailyResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecordViewModel 단위 테스트 — 리뷰 #68(지영) 두 블로킹 지적에 대한 회귀 방지.
 *   1. 재진입 시 최신 기록 재조회(refresh 재호출 → API 재호출 + 상태 갱신).
 *   2. 두 소스 독립 성공/실패 — 한쪽 실패해도 다른 쪽 결과가 반영되고, 낡은 값이 섞이지 않음.
 *
 *  refresh() 를 runBlocking 으로 직접 호출해 Main 디스패처(viewModelScope) 의존 없이 검증한다.
 */
class RecordViewModelTest {

    private class FakeRecordApi(
        var history: () -> RiskHistoryResponse,
        var logs: () -> MissionLogListResponse,
    ) : RecordApi {
        var historyCalls = 0
        var logsCalls = 0
        override suspend fun getRiskHistory(limit: Int): RiskHistoryResponse {
            historyCalls++; return history()
        }
        override suspend fun getMissionLogs(date: String?, from: String?, to: String?): MissionLogListResponse {
            logsCalls++; return logs()
        }
        override suspend fun getPredictionInputs() = PredictionInputsResponse()
        override suspend fun getWalkingDaily(days: Int) = WalkingDailyResponse()
        override suspend fun getChallengeTotals() = ChallengeTotalsResponse()
        override suspend fun getStamps(month: String) = StampsResponse(month = month)
        override suspend fun getLatestPrediction() = RiskLatestResponse()
        override suspend fun getScoreSimulation() = ScoreSimulationResponse()
        override suspend fun getCohortDistribution(): CohortDistributionResponse = error("이 테스트는 또래 분포를 부르지 않는다")
        override suspend fun reassessRiskPrediction(body: RiskReassessRequest): RiskReassessResponse =
            error("이 테스트는 재평가를 부르지 않는다")
    }

    private fun risk(vararg stages: String) =
        RiskHistoryResponse(stages.map { RiskHistoryItem(createdAt = "2026-07-14T09:00:00+09:00", careStage = it) })

    private fun log(success: Boolean, points: Int) =
        MissionLogItem(missionLogId = 1, missionType = "walking", success = success, countedForDaily = success, earnedPoints = points)

    @Test
    fun loads_both_sources_and_aggregates_activity() = runBlocking {
        val api = FakeRecordApi(
            history = { risk("good", "maintain") },
            logs = { MissionLogListResponse(listOf(log(true, 10), log(true, 5), log(false, 0))) },
        )
        val vm = RecordViewModel(api)

        vm.refresh()

        assertEquals(2, vm.history.size)
        assertEquals(2, vm.completedMissions) // counted_for_daily=true 2건
        assertEquals(15, vm.totalPoints)      // 10 + 5 + 0
        assertFalse(vm.historyError)
        assertFalse(vm.activityError)
        assertTrue(vm.loaded)
    }

    @Test
    fun reentry_refetches_and_updates_state() = runBlocking {
        val api = FakeRecordApi(
            history = { risk("good") },
            logs = { MissionLogListResponse(listOf(log(true, 10))) },
        )
        val vm = RecordViewModel(api)

        vm.refresh()
        assertEquals(1, vm.history.size)
        assertEquals(10, vm.totalPoints)

        // 서버 응답이 바뀐 뒤 재진입(재조회) → 최신 값으로 갱신되어야 한다.
        api.history = { risk("good", "maintain", "action_needed") }
        api.logs = { MissionLogListResponse(listOf(log(true, 10), log(true, 20))) }
        vm.refresh()

        assertEquals(2, api.historyCalls) // 재호출됨
        assertEquals(2, api.logsCalls)
        assertEquals(3, vm.history.size)
        assertEquals(2, vm.completedMissions)
        assertEquals(30, vm.totalPoints)
    }

    /**
     * "완료한 미션 수"는 success 로그 개수가 아니라 실제 집계(counted_for_daily) 건수여야 한다(#234).
     *  운동(누적 10분) 미션을 하루에 여러 번 하면 10분 넘긴 뒤 세션도 success=true 로 남지만
     *  counted_for_daily 는 첫 달성 1건만 true → 한 미션(운동)이 "4개 완료"로 부풀던 실단말 버그의 회귀 방지.
     */
    @Test
    fun counts_completed_missions_by_daily_count_not_success_logs() = runBlocking {
        val api = FakeRecordApi(
            history = { risk("good") },
            logs = {
                MissionLogListResponse(
                    listOf(
                        MissionLogItem(79, "exercise", success = true, countedForDaily = true, earnedPoints = 10),
                        MissionLogItem(80, "exercise", success = true, countedForDaily = false, earnedPoints = 0),
                        MissionLogItem(81, "exercise", success = true, countedForDaily = false, earnedPoints = 0),
                        MissionLogItem(82, "exercise", success = true, countedForDaily = false, earnedPoints = 0),
                    )
                )
            },
        )
        val vm = RecordViewModel(api)

        vm.refresh()

        assertEquals(1, vm.completedMissions) // success 4건이 아니라 실제 집계 1건
        assertEquals(10, vm.totalPoints)      // 보상도 1회분만
    }

    @Test
    fun history_failure_still_reflects_mission_logs() = runBlocking {
        val api = FakeRecordApi(
            history = { throw RuntimeException("boom") },
            logs = { MissionLogListResponse(listOf(log(true, 7))) },
        )
        val vm = RecordViewModel(api)

        vm.refresh()

        assertTrue(vm.historyError)          // 이력 섹션만 오류
        assertFalse(vm.activityError)
        assertEquals(1, vm.completedMissions) // mission-logs 는 정상 반영
        assertEquals(7, vm.totalPoints)
        assertEquals(1, api.logsCalls)        // 이력 실패가 로그 호출을 막지 않음
    }

    /**
     * 겹친 재조회 경쟁(리뷰 #68 지적 3): 느린 첫 조회가 진행 중일 때 두 번째(즉시) 조회가 겹쳐도,
     * 최종 상태는 가장 최근(두 번째) 응답으로 유지되어야 한다. 늦게 끝난 첫 응답이 덮지 않는다.
     * gate 로 첫 조회를 잡아 결정적으로 재현(타이밍 의존 없음).
     */
    private class GatedFake(private val gate: CompletableDeferred<Unit>) : RecordApi {
        var historyCalls = 0
        var logsCalls = 0
        override suspend fun getRiskHistory(limit: Int): RiskHistoryResponse {
            historyCalls++
            return if (historyCalls == 1) {
                gate.await(); RiskHistoryResponse(listOf(RiskHistoryItem("2026-07-14T09:00:00+09:00", "good")))
            } else {
                RiskHistoryResponse(listOf(RiskHistoryItem("2026-07-15T09:00:00+09:00", "action_needed")))
            }
        }
        override suspend fun getMissionLogs(date: String?, from: String?, to: String?): MissionLogListResponse {
            logsCalls++
            return if (logsCalls == 1) {
                gate.await()
                MissionLogListResponse(listOf(MissionLogItem(missionLogId = 1, missionType = "walking", success = true, countedForDaily = true, earnedPoints = 7)))
            } else {
                MissionLogListResponse(listOf(MissionLogItem(missionLogId = 1, missionType = "walking", success = true, countedForDaily = true, earnedPoints = 14)))
            }
        }
        override suspend fun getPredictionInputs() = PredictionInputsResponse()
        override suspend fun getWalkingDaily(days: Int) = WalkingDailyResponse()
        override suspend fun getChallengeTotals() = ChallengeTotalsResponse()
        override suspend fun getStamps(month: String) = StampsResponse(month = month)
        override suspend fun getLatestPrediction() = RiskLatestResponse()
        override suspend fun getScoreSimulation() = ScoreSimulationResponse()
        override suspend fun getCohortDistribution(): CohortDistributionResponse = error("이 테스트는 또래 분포를 부르지 않는다")
        override suspend fun reassessRiskPrediction(body: RiskReassessRequest): RiskReassessResponse =
            error("이 테스트는 재평가를 부르지 않는다")
    }

    @Test
    fun overlapping_refresh_keeps_latest_response() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = GatedFake(gate)
        val vm = RecordViewModel(fake)

        // 첫 refresh(느림) 시작 → 두 API 모두 gate 에서 대기하도록 진행시킨다.
        val first = launch { vm.refresh() }
        while (fake.historyCalls < 1 || fake.logsCalls < 1) yield()

        // 겹쳐서 두 번째 refresh(즉시) 실행 → 최신 값(action_needed/14P) commit.
        vm.refresh()
        assertEquals("action_needed", vm.history.single().careStage)
        assertEquals(14, vm.totalPoints)

        // 이제 느린 첫 조회를 완료시킨다 → 낡은 세대라 상태를 덮지 않아야 한다.
        gate.complete(Unit)
        first.join()

        assertEquals("action_needed", vm.history.single().careStage)
        assertEquals(1, vm.completedMissions)
        assertEquals(14, vm.totalPoints)
        assertFalse(vm.loading)
    }

    @Test
    fun mission_logs_failure_still_reflects_history() = runBlocking {
        val api = FakeRecordApi(
            history = { risk("good", "maintain") },
            logs = { throw RuntimeException("boom") },
        )
        val vm = RecordViewModel(api)

        vm.refresh()

        assertTrue(vm.activityError)   // 활동 섹션만 오류
        assertFalse(vm.historyError)
        assertEquals(2, vm.history.size) // 이력은 정상 반영
    }

    /**
     * 리뷰 #275-①·② 회귀 방지: 걷기/근력 시뮬레이션 응답이 **둘 다** UI 상태(MuscleScoreUi)까지 전달되고,
     * 이력의 점수·비교 상태가 경계 보존 추이(buildScoreTrend)로 매핑된다.
     */
    @Test
    fun sim_and_score_reach_muscle_ui_state() = runBlocking {
        val api = object : RecordApi {
            override suspend fun getRiskHistory(limit: Int) = RiskHistoryResponse(
                listOf(
                    RiskHistoryItem(
                        createdAt = "2026-07-22T09:00:00+09:00", careStage = "maintain",
                        muscleScore = 70, cohortVersion = "v1", comparisonStatus = "baseline",
                    ),
                    RiskHistoryItem(
                        createdAt = "2026-07-29T09:00:00+09:00", careStage = "maintain",
                        muscleScore = 74, cohortVersion = "v1", comparisonStatus = "comparable",
                    ),
                ),
            )
            override suspend fun getMissionLogs(date: String?, from: String?, to: String?) = MissionLogListResponse()
            override suspend fun getPredictionInputs() = PredictionInputsResponse()
            override suspend fun getWalkingDaily(days: Int) = WalkingDailyResponse()
            override suspend fun getChallengeTotals() = ChallengeTotalsResponse()
            override suspend fun getStamps(month: String) = StampsResponse(month = month)
            override suspend fun getLatestPrediction() = RiskLatestResponse(muscleScore = 74, scoreBand = "maintain")
            override suspend fun getScoreSimulation() = ScoreSimulationResponse(
                walk = listOf(ScoreSimPointDto(0, 74), ScoreSimPointDto(7, 76)),
                musc = listOf(ScoreSimPointDto(0, 74), ScoreSimPointDto(3, 82)),
            )
            override suspend fun getCohortDistribution() = CohortDistributionResponse(
                probability = 0.18f, sex = "male", ageLabel = "73–79세", n = 600, lowerCount = 72,
            )
            override suspend fun reassessRiskPrediction(body: RiskReassessRequest): RiskReassessResponse =
                error("이 테스트는 재평가를 부르지 않는다")
        }
        val vm = RecordViewModel(api)

        vm.refresh()

        val ui = vm.muscleScore ?: error("muscleScore UI 상태가 구성되지 않았다")
        assertEquals(74, ui.score)
        assertEquals("maintain", ui.band)
        assertEquals(listOf(0, 7), ui.walkSim.map { it.days }) // 걷기 시뮬도 UI 까지 전달(리뷰 #275-①)
        assertEquals(listOf(0, 3), ui.muscSim.map { it.days })
        assertEquals(listOf(70, 74), ui.trend.map { it.score })
        assertEquals(listOf(false, false), ui.trend.map { it.newBaseline }) // 같은 코호트 → 경계 없음
        assertEquals(72, ui.cohort?.lowerCount) // 또래 분포도 UI 상태까지 전달(#193 기록탭 이관)
    }
}
