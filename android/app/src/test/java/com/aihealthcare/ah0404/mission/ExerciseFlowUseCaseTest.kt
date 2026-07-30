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
 * ExerciseFlowUseCase.submitExerciseSession 파이프라인 검증(#234).
 *
 *  운동은 센서 단계가 없어 ②POST(in_progress) → ④PATCH(completed, exercise_detail) 2단계다.
 *  걷기와 같은 계약을 공유하므로 검증 관심사도 같다: (1) 재전송 조기 종료(#172), (2) 정상 경로에서
 *  exercise_detail(duration_min) 을 실어 완료 PATCH 를 한다, (3) 0/음수 분은 서버 gt=0 을 치기 전에 막는다.
 */
class ExerciseFlowUseCaseTest {

    /** createMissionLog 가 돌려줄 status 를 지정하고, 완료 호출 본문/횟수를 기록하는 fake. */
    private class FakeApi(private val createStatus: String) : MissionApi {
        var completeCalls = 0
        var lastUpdate: MissionLogUpdateRequest? = null
        var lastCreate: MissionLogCreateRequest? = null

        override suspend fun createMissionLog(body: MissionLogCreateRequest): MissionLogCreateResponse {
            lastCreate = body
            return MissionLogCreateResponse(
                missionLogId = 42,
                status = createStatus,
                success = true,
                countedForDaily = createStatus == "completed",
                earnedPoints = 0,
                dailyResult = "none",
                deduplicated = createStatus == "completed",
            )
        }

        override suspend fun completeMissionLog(
            missionLogId: Int,
            body: MissionLogUpdateRequest,
        ): MissionLogUpdateResponse {
            completeCalls++
            lastUpdate = body
            return MissionLogUpdateResponse(
                missionLogId = missionLogId,
                status = "completed",
                success = true,
                countedForDaily = true,
                dailyResult = "success",
                syncStatus = "synced",
                dailyTotalMin = 10f,
            )
        }

        override suspend fun createSensorSession(body: SensorSessionCreateRequest): SensorSessionCreateResponse =
            error("운동 흐름은 센서 세션을 만들지 않는다")

        override suspend fun guestLogin(): LoginResponse = error("unused")
        override suspend fun getMissions(status: String): MissionsResponse = error("unused")
    }

    @Test
    fun `신규 in_progress 응답이면 exercise_detail 로 완료 PATCH 를 한다`() = runTest {
        val api = FakeApi(createStatus = "in_progress")
        val result = ExerciseFlowUseCase(api).submitExerciseSession(
            missionTemplateId = 7,
            durationMin = 4f,
            safetyNoticeConfirmed = true,
            createdOnDeviceAt = "2026-07-29T10:00:00.000+09:00",
        )

        assertEquals(1, api.completeCalls)
        assertEquals("세션 분을 그대로 실어 보낸다", 4f, api.lastUpdate?.exerciseDetail?.durationMin)
        assertTrue("success 를 반드시 true 로 보낸다", api.lastUpdate?.success == true)
        assertTrue("호출부가 넘긴 실제 확인값을 그대로 전송한다", api.lastCreate?.safetyNoticeConfirmed == true)
        assertTrue(result.success)
        assertEquals("completed", result.finalStatus)
    }

    @Test
    fun `재전송이 이미 completed 로그를 돌려주면 완료 PATCH 를 건너뛰고 성공한다`() = runTest {
        val api = FakeApi(createStatus = "completed") // PATCH 커밋 후 응답 유실 → 재시도 시 서버가 completed 반환
        val result = ExerciseFlowUseCase(api).submitExerciseSession(
            missionTemplateId = 7,
            durationMin = 4f,
            safetyNoticeConfirmed = true,
            createdOnDeviceAt = "2026-07-29T10:00:00.000+09:00",
        )

        assertEquals("완료 PATCH 를 다시 하지 않는다(이미 완료 방지)", 0, api.completeCalls)
        assertTrue("이미 저장된 기록이므로 성공으로 끝낸다", result.success)
        assertEquals("completed", result.finalStatus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `0분 세션은 전송 전에 거부한다`() = runTest {
        // 서버 duration_min gt=0 을 치기 전에 즉시 이탈(0분) 세션을 막는다.
        ExerciseFlowUseCase(FakeApi(createStatus = "in_progress")).submitExerciseSession(
            missionTemplateId = 7,
            durationMin = 0f,
            safetyNoticeConfirmed = true,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `안전 고지 미확인이면 전송 전에 거부한다`() = runTest {
        // 서버는 requires_safety_notice=true 미션에서 확인 안 되면 400. 조작된 참을 만들지 않고 시작 전에 막는다(P1-C).
        ExerciseFlowUseCase(FakeApi(createStatus = "in_progress")).submitExerciseSession(
            missionTemplateId = 7,
            durationMin = 4f,
            safetyNoticeConfirmed = false,
        )
    }
}
