package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.LoginResponse
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogCreateResponse
import com.aihealthcare.ah0404.network.MissionLogUpdateRequest
import com.aihealthcare.ah0404.network.MissionLogUpdateResponse
import com.aihealthcare.ah0404.network.MissionsResponse
import com.aihealthcare.ah0404.network.SensorSessionCreateRequest
import com.aihealthcare.ah0404.network.SensorSessionCreateResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WalkingFlowUseCase.submitWalkingSession 파이프라인 검증 — 특히 **재전송 조기 종료**(리뷰 #172 P1-2).
 *
 *  #158 멱등은 첫 POST 만 보장한다. PATCH 커밋 뒤 응답만 유실돼 재시도가 오면 ②POST 가 이미 completed 된
 *  로그를 돌려주는데, 그때 ③센서·④PATCH 를 다시 하면 센서가 중복되고 PATCH 가 "이미 완료" 409 로 실패한다.
 *  → ②응답이 completed 면 센서·완료를 건너뛰고 성공으로 끝내야 한다.
 */
class WalkingFlowUseCaseTest {

    /** createMissionLog 가 돌려줄 status 를 지정하고, 센서·완료 호출 횟수를 기록하는 fake. */
    private class FakeApi(private val createStatus: String) : MissionApi {
        var sensorCalls = 0
        var completeCalls = 0

        override suspend fun createMissionLog(body: MissionLogCreateRequest): MissionLogCreateResponse =
            MissionLogCreateResponse(
                missionLogId = 42,
                status = createStatus,
                success = true,
                countedForDaily = false,
                earnedPoints = 0,
                dailyResult = "none",
                deduplicated = createStatus == "completed",
            )

        override suspend fun createSensorSession(body: SensorSessionCreateRequest): SensorSessionCreateResponse {
            sensorCalls++
            return SensorSessionCreateResponse(sensorSessionId = 1, recognitionStatus = "success")
        }

        override suspend fun completeMissionLog(
            missionLogId: Int,
            body: MissionLogUpdateRequest,
        ): MissionLogUpdateResponse {
            completeCalls++
            return MissionLogUpdateResponse(
                missionLogId = missionLogId,
                status = "completed",
                success = true,
                countedForDaily = true,
                dailyResult = "success",
                syncStatus = "synced",
                dailyTotalMin = 20f,
            )
        }

        override suspend fun guestLogin(): LoginResponse = error("unused")
        override suspend fun getMissions(status: String): MissionsResponse = error("unused")
    }

    @Test
    fun `재전송이 이미 completed 로그를 돌려주면 센서·완료를 건너뛰고 성공한다`() = runTest {
        val api = FakeApi(createStatus = "completed") // PATCH 커밋 후 응답 유실 → 재시도 시 서버가 completed 반환
        val result = WalkingFlowUseCase(api).submitWalkingSession(
            missionTemplateId = 7,
            steps = 1000,
            durationSec = 1200,
            createdOnDeviceAt = "2026-07-23T10:00:00.000+09:00",
        )

        assertEquals("센서를 다시 만들지 않는다(중복 방지)", 0, api.sensorCalls)
        assertEquals("완료 PATCH 를 다시 하지 않는다(409 방지)", 0, api.completeCalls)
        assertTrue("이미 저장된 기록이므로 성공으로 끝낸다", result.success)
        assertEquals("completed", result.finalStatus)
    }

    @Test
    fun `신규 in_progress 응답이면 센서·완료를 정상 진행한다`() = runTest {
        val api = FakeApi(createStatus = "in_progress") // 최초 전송 — 정상 경로
        val result = WalkingFlowUseCase(api).submitWalkingSession(
            missionTemplateId = 7,
            steps = 1000,
            durationSec = 1200,
            createdOnDeviceAt = "2026-07-23T10:00:00.000+09:00",
        )

        assertEquals(1, api.sensorCalls)
        assertEquals(1, api.completeCalls)
        assertTrue(result.success)
        assertEquals("completed", result.finalStatus)
    }
}
