package com.aihealthcare.ah0404.exercise

import com.aihealthcare.ah0404.mission.ExerciseFlowUseCase
import com.aihealthcare.ah0404.network.ExerciseVideoApi
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.network.ExerciseVideosResponse
import com.aihealthcare.ah0404.network.LoginResponse
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogCreateResponse
import com.aihealthcare.ah0404.network.MissionLogUpdateRequest
import com.aihealthcare.ah0404.network.MissionLogUpdateResponse
import com.aihealthcare.ah0404.network.MissionsResponse
import com.aihealthcare.ah0404.network.SensorSessionCreateRequest
import com.aihealthcare.ah0404.network.SensorSessionCreateResponse
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ExerciseVideosViewModel 테스트 — GET /exercise-videos(#72) 로드/정렬/오류 + 운동 완료 전송(#234).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseVideosViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(val result: () -> ExerciseVideosResponse) : ExerciseVideoApi {
        override suspend fun getExerciseVideos() = result()
    }

    /**
     * MissionApi fake — getMissions 로 넘겨줄 미션 목록을 지정하고, 완료 전송(createMissionLog→completeMissionLog)
     * 호출을 기록한다. VM 의 missionApi(템플릿 해석)와 exerciseFlow(전송) 둘 다 같은 fake 로 물려 한 눈에 검증한다.
     */
    private class FakeMissionApi(private val missions: List<Mission>) : MissionApi {
        var createdTemplateId: Int? = null
        var createdType: String? = null
        var createdSafetyConfirmed: Boolean? = null
        var completeCalls = 0
        var lastDurationMin: Float? = null

        override suspend fun guestLogin(): LoginResponse = error("unused")

        override suspend fun getMissions(status: String): MissionsResponse = MissionsResponse(missions)

        override suspend fun createMissionLog(body: MissionLogCreateRequest): MissionLogCreateResponse {
            createdTemplateId = body.missionTemplateId
            createdType = body.missionType
            createdSafetyConfirmed = body.safetyNoticeConfirmed
            return MissionLogCreateResponse(
                missionLogId = 100,
                status = "in_progress",
                success = true,
                countedForDaily = false,
                earnedPoints = 0,
                dailyResult = "none",
            )
        }

        override suspend fun completeMissionLog(
            missionLogId: Int,
            body: MissionLogUpdateRequest,
        ): MissionLogUpdateResponse {
            completeCalls++
            lastDurationMin = body.exerciseDetail?.durationMin
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
    }

    private fun exerciseMission(templateId: Int) = Mission(
        missionTemplateId = templateId,
        missionType = "exercise",
        title = "영상 따라 운동하기",
        level = "easy",
        targetValue = 10,
        targetUnit = "minutes",
        requiresSafetyNotice = true,
        rewardPoints = 10,
    )

    private fun item(stage: String, order: Int, available: Boolean = false, url: String? = null) =
        ExerciseVideoItem(stage, stage, order, url, null, available)

    @Test
    fun loads_and_sorts_by_order() = runTest {
        val vm = ExerciseVideosViewModel(
            FakeApi {
                ExerciseVideosResponse(
                    listOf(
                        item("cooldown", 4),
                        item("warmup", 1),
                        item("standing", 3, available = true, url = "https://v/s.mp4"),
                        item("seated", 2),
                    ),
                )
            },
        )
        vm.load(); advanceUntilIdle()

        assertEquals(listOf("warmup", "seated", "standing", "cooldown"), vm.videos.map { it.stage })
        assertTrue(vm.videos.first { it.stage == "standing" }.available)
        assertFalse(vm.videos.first { it.stage == "warmup" }.available)
        assertFalse(vm.error)
        assertTrue(vm.loaded)
    }

    @Test
    fun load_failure_sets_error() = runTest {
        val vm = ExerciseVideosViewModel(FakeApi { throw RuntimeException("boom") })
        vm.load(); advanceUntilIdle()
        assertTrue(vm.error)
        assertTrue(vm.videos.isEmpty())
        assertTrue(vm.loaded)
    }

    private fun vmWith(missionApi: FakeMissionApi) = ExerciseVideosViewModel(
        api = FakeApi { ExerciseVideosResponse(emptyList()) },
        missionApi = missionApi,
        exerciseFlow = ExerciseFlowUseCase(missionApi),
    )

    @Test
    fun `submitExercise 는 단일 운동 미션 템플릿으로 exercise_detail 을 실어 완료한다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        vmWith(fake).submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertEquals("GET /missions 로 해석한 운동 템플릿 id 로 시작", 7, fake.createdTemplateId)
        assertEquals("exercise", fake.createdType)
        assertEquals("확인 게이트 결과를 그대로 서버에 싣는다", true, fake.createdSafetyConfirmed)
        assertEquals(1, fake.completeCalls)
        assertEquals("세션 분을 그대로 실어 보낸다", 4f, fake.lastDurationMin)
    }

    @Test
    fun `submitExercise 는 0분 이하면 아무것도 보내지 않는다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        val vm = vmWith(fake)
        vm.submitExercise(0f, safetyNoticeConfirmed = true)
        vm.submitExercise(-1f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertNull("서버 gt=0 을 치기 전에 막는다 — 시작조차 안 함", fake.createdTemplateId)
        assertEquals(0, fake.completeCalls)
    }

    @Test
    fun `submitExercise 는 안전 고지 미확인이면 아무것도 보내지 않는다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        vmWith(fake).submitExercise(4f, safetyNoticeConfirmed = false)
        advanceUntilIdle()

        assertNull("확인 게이트를 통과하지 않으면 시작조차 안 한다(서버 400 방어)", fake.createdTemplateId)
        assertEquals(0, fake.completeCalls)
    }

    @Test
    fun `submitExercise 는 운동 미션이 없으면 전송을 생략한다`() = runTest {
        val fake = FakeMissionApi(emptyList()) // 걷기/식사만 있고 운동 템플릿 부재(또는 비로그인)
        vmWith(fake).submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertNull("보낼 대상이 없어 시작하지 않는다", fake.createdTemplateId)
        assertEquals(0, fake.completeCalls)
    }
}
