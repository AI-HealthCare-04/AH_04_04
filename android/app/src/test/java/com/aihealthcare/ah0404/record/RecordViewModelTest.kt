package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.MissionLogItem
import com.aihealthcare.ah0404.network.MissionLogListResponse
import com.aihealthcare.ah0404.network.RecordApi
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.network.RiskHistoryResponse
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
        override suspend fun getMissionLogs(date: String?): MissionLogListResponse {
            logsCalls++; return logs()
        }
    }

    private fun risk(vararg stages: String) =
        RiskHistoryResponse(stages.map { RiskHistoryItem(createdAt = "2026-07-14T09:00:00+09:00", careStage = it) })

    private fun log(success: Boolean, points: Int) =
        MissionLogItem(mission_logId(), "walking", success, countedForDaily = success, earnedPoints = points)

    // mission_log_id 는 값 의미 없음(합산/카운트만 검증) → 고정.
    private fun mission_logId() = 1

    @Test
    fun loads_both_sources_and_aggregates_activity() = runBlocking {
        val api = FakeRecordApi(
            history = { risk("good", "maintain") },
            logs = { MissionLogListResponse(listOf(log(true, 10), log(true, 5), log(false, 0))) },
        )
        val vm = RecordViewModel(api)

        vm.refresh()

        assertEquals(2, vm.history.size)
        assertEquals(2, vm.completedMissions) // success=true 2건
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
        override suspend fun getMissionLogs(date: String?): MissionLogListResponse {
            logsCalls++
            return if (logsCalls == 1) {
                gate.await(); MissionLogListResponse(listOf(MissionLogItem(1, "walking", true, true, 7)))
            } else {
                MissionLogListResponse(listOf(MissionLogItem(1, "walking", true, true, 14)))
            }
        }
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
}
