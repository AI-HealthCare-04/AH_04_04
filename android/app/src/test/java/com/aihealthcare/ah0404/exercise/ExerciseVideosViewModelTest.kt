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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        // 매 POST 의 자연 키(created_on_device_at)를 순서대로 기록 — 재시도가 같은 키를 쓰는지 검증용(#234-1).
        val createdKeys = mutableListOf<String?>()
        // >0 이면 그만큼 createMissionLog 를 예외로 실패시킨다(전송 실패→재시도 경로 검증용, #234-2).
        var failCreateTimes = 0
        // 완료 응답의 서버 판정값(#235 누적시간 표시 검증용). 기본=목표 달성(성공·당일 10분).
        var completeSuccess = true
        var completeDailyTotal: Float? = 10f
        // 세션 분(exercise_detail.duration_min)별 완료 응답 (당일 누적, 목표달성) — 병렬 전송의 응답을 개별 지정(리뷰 #280).
        var completeRespByDuration: Map<Float, Pair<Float?, Boolean>> = emptyMap()
        // 세션 분별 응답 지연(ms) — delay 로 응답 '도착 순서'를 뒤집어 역순 수렴을 검증한다.
        var completeDelayByDuration: Map<Float, Long> = emptyMap()

        override suspend fun guestLogin(): LoginResponse = error("unused")

        override suspend fun getMissions(status: String): MissionsResponse = MissionsResponse(missions)

        override suspend fun createMissionLog(body: MissionLogCreateRequest): MissionLogCreateResponse {
            createdKeys.add(body.createdOnDeviceAt)
            createdTemplateId = body.missionTemplateId
            createdType = body.missionType
            createdSafetyConfirmed = body.safetyNoticeConfirmed
            if (failCreateTimes > 0) { failCreateTimes--; throw RuntimeException("network down") }
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
            val dur = body.exerciseDetail?.durationMin
            lastDurationMin = dur
            completeDelayByDuration[dur]?.let { delay(it) } // 응답 도착 순서를 인위적으로 뒤집기 위한 지연
            val (total, ok) = completeRespByDuration[dur] ?: (completeDailyTotal to completeSuccess)
            return MissionLogUpdateResponse(
                missionLogId = missionLogId,
                status = "completed",
                success = ok,
                countedForDaily = ok,
                dailyResult = if (ok) "success" else "none",
                syncStatus = "synced",
                dailyTotalMin = total,
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

    /** 인메모리 영속 outbox fake(#271) — 저장된 스냅샷을 그대로 보관해 영속/재시작 flush 계약을 검증한다. */
    private class FakeOutbox(initial: List<PendingExercise> = emptyList()) : ExerciseOutbox {
        var stored: List<PendingExercise> = initial; private set
        override fun load(): List<PendingExercise> = stored
        override fun save(sessions: List<PendingExercise>) { stored = sessions }
    }

    private fun vmWith(missionApi: FakeMissionApi, outbox: ExerciseOutbox = NoOpExerciseOutbox()) = ExerciseVideosViewModel(
        api = FakeApi { ExerciseVideosResponse(emptyList()) },
        missionApi = missionApi,
        exerciseFlow = ExerciseFlowUseCase(missionApi),
        outbox = outbox,
    )

    @Test
    fun `submitExercise 는 단일 운동 미션 템플릿으로 exercise_detail 을 실어 완료한다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        val vm = vmWith(fake)
        vm.submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertEquals("GET /missions 로 해석한 운동 템플릿 id 로 시작", 7, fake.createdTemplateId)
        assertEquals("exercise", fake.createdType)
        assertEquals("확인 게이트 결과를 그대로 서버에 싣는다", true, fake.createdSafetyConfirmed)
        assertEquals(1, fake.completeCalls)
        assertEquals("세션 분을 그대로 실어 보낸다", 4f, fake.lastDurationMin)
        assertTrue("성공하면 재시도 대기가 남지 않는다", vm.pendingResends.isEmpty())
    }

    @Test
    fun `전송 실패 후 재시도는 같은 자연 키로 보내 중복 집계를 막는다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        fake.failCreateTimes = 1 // 첫 POST 는 네트워크 실패
        val vm = vmWith(fake)

        vm.beginExerciseSession() // 재생 시작 시 세션 키 확정
        vm.submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertEquals("첫 시도는 POST 에서 실패", 1, fake.createdKeys.size)
        assertEquals("실패했으니 완료 단계까지 못 감", 0, fake.completeCalls)
        assertEquals("실패한 세션을 분과 함께 보존", 4f, vm.pendingResends.single().durationMin)

        vm.retryPending()
        advanceUntilIdle()

        assertEquals("재시도로 두 번째 POST", 2, fake.createdKeys.size)
        assertEquals("재시도는 시작 때 잡은 것과 같은 자연 키여야 한다", fake.createdKeys[0], fake.createdKeys[1])
        assertNotNull("자연 키가 실제로 채워져 있어야 dedup 이 성립", fake.createdKeys[0])
        assertEquals("재시도로 완료 전송 성공", 1, fake.completeCalls)
        assertTrue("성공했으니 재시도 대기 해제", vm.pendingResends.isEmpty())
    }

    @Test
    fun `A 실패 후 B 성공해도 A 는 남고 A 만 재시도로 저장된다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        fake.failCreateTimes = 1 // 첫 세션(A) POST 만 실패, 이후(B·재시도) 성공
        val vm = vmWith(fake)

        // 세션 A — 실패
        vm.beginExerciseSession()
        vm.submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()
        val keyA = fake.createdKeys[0]
        assertEquals("A 는 실패해 보존", listOf(4f), vm.pendingResends.map { it.durationMin })

        // 세션 B — 성공. 단일 슬롯이면 이 성공 경로가 A 까지 지웠을 것.
        vm.beginExerciseSession()
        vm.submitExercise(5f, safetyNoticeConfirmed = true)
        advanceUntilIdle()
        assertEquals("B 성공 후에도 A 는 그대로 남아 있어야 한다", listOf(4f), vm.pendingResends.map { it.durationMin })
        assertNotEquals("A·B 는 서로 다른 자연 키", keyA, fake.createdKeys.last())
        assertEquals("여기까지 완료된 건 B 하나", 1, fake.completeCalls)

        // A 재시도 — 성공
        vm.retryPending()
        advanceUntilIdle()
        assertTrue("A 까지 저장돼 남은 대기 없음", vm.pendingResends.isEmpty())
        assertEquals("A·B 둘 다 완료", 2, fake.completeCalls)
        assertEquals("A 재시도는 A 의 원래 키를 그대로 사용", keyA, fake.createdKeys.last())
    }

    @Test
    fun `템플릿 미해석이어도 세션을 버리지 않고 재시도 대기로 보존한다`() = runTest {
        val fake = FakeMissionApi(emptyList()) // 로그인 전/운동 미션 부재로 템플릿 미해석
        val vm = vmWith(fake)
        vm.beginExerciseSession()
        vm.submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertEquals("보낼 대상이 없어 시작조차 안 함", 0, fake.completeCalls)
        assertEquals("잃지 않게 보존해 이후(로그인) 재시도에 맡긴다", 4f, vm.pendingResends.single().durationMin)
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

    @Test
    fun `미전송 세션을 영속 outbox 에 저장하고 성공하면 지운다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        fake.failCreateTimes = 1 // 첫 POST 실패 → pending 보존
        val outbox = FakeOutbox()
        val vm = vmWith(fake, outbox)

        vm.beginExerciseSession()
        vm.submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()
        assertEquals("실패한 세션이 영속 저장돼 재시작에도 남는다", listOf(4f), outbox.stored.map { it.durationMin })

        vm.retryPending()
        advanceUntilIdle()
        assertEquals("재시도로 완료 전송", 1, fake.completeCalls)
        assertTrue("성공하면 영속 저장소에서도 사라진다(성공 키만 제거)", outbox.stored.isEmpty())
    }

    /**
     * "앱 재시작 flush"(#271): 이전 실행에서 전송 못 하고 종료된 세션이 outbox 에 남아 있으면, VM 생성(init)에서
     *  되살려 **같은 자연 키**로 자동 전송한다 — 사용자가 완료했는데 서버 집계에서 누락되던 경로를 복구.
     */
    @Test
    fun `앱 재시작 시 저장된 미전송 세션을 되살려 같은 키로 전송한다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        val savedKey = "2026-07-30T10:00:00.000+09:00"
        val outbox = FakeOutbox(listOf(PendingExercise(6f, savedKey, safetyNoticeConfirmed = true)))

        val vm = vmWith(fake, outbox) // init 에서 되살려 자동 flush
        advanceUntilIdle()

        assertEquals("되살린 세션을 완료까지 전송", 1, fake.completeCalls)
        assertEquals("저장돼 있던 그 자연 키 그대로 전송(중복 집계 방지)", savedKey, fake.createdKeys.single())
        assertEquals("전송 분도 저장값 그대로", 6f, fake.lastDurationMin)
        assertTrue("성공했으니 영속 대기 해제", outbox.stored.isEmpty())
        assertTrue("관찰 상태도 비어야 한다", vm.pendingResends.isEmpty())
    }

    // -----------------------------------------------------------------------------------
    // #235: 누적 운동시간 노출 + 이어보기 위치 보관
    // -----------------------------------------------------------------------------------

    @Test
    fun `운동 완료 후 서버가 합산한 당일 누적분과 목표달성을 노출한다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7))) // 기본=당일 10분·성공
        val vm = vmWith(fake)
        assertNull("완료 전엔 누적시간을 표시하지 않는다", vm.todayExerciseMin)
        assertFalse("완료 전엔 목표 미달성", vm.todayGoalReached)

        vm.submitExercise(4f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertEquals("서버 당일 누적값(sum_exercise_minutes_today)을 그대로 노출", 10f, vm.todayExerciseMin)
        assertTrue("서버 success=목표(하루 10분) 달성 → 완료 안내", vm.todayGoalReached)
    }

    @Test
    fun `병렬 완료 전송을 직렬화해 마지막 전송값으로 수렴한다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        // A(6분/미달) 를 먼저 전송하지만 응답이 더 오래 걸리게 지연을 준다. 직렬화가 없으면(예전 병렬 대입) B(11분/달성)가
        //   먼저 적용된 뒤 늦은 A 가 6분/미달로 되돌려 최종이 틀어진다. 직렬화되면 A→B 순서로만 적용돼 최종은 B.
        fake.completeRespByDuration = mapOf(6f to (6f to false), 11f to (11f to true))
        fake.completeDelayByDuration = mapOf(6f to 100L, 11f to 10L)
        val vm = vmWith(fake)

        vm.beginExerciseSession(); vm.submitExercise(6f, safetyNoticeConfirmed = true)  // A: 먼저 전송(응답 늦음)
        vm.beginExerciseSession(); vm.submitExercise(11f, safetyNoticeConfirmed = true) // B: 나중 전송(응답 빠름)
        advanceUntilIdle()

        assertEquals("두 세션 모두 완료 전송", 2, fake.completeCalls)
        assertEquals("직렬 적용의 마지막 = 나중 전송 B(11분), 늦은 A 가 끼어들지 못한다", 11f, vm.todayExerciseMin)
        assertTrue("마지막 전송이 달성이므로 달성 표시", vm.todayGoalReached)
    }

    /**
     * 날짜 경계 회귀 방지(리뷰 #280): 최댓값·스티키 방식이면 자정을 넘겨도 전날 11분·달성이 남지만, 직렬 last-wins 는
     *  다음 날 첫 세션의 더 작은 누적/미달을 그대로 반영해 정상 초기화한다(서버 daily_total 이 새 날엔 다시 작아지므로).
     */
    @Test
    fun `다음 날 첫 운동의 더 작은 누적·미달로 표시가 초기화된다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        val vm = vmWith(fake)

        // 전날: 11분·달성
        fake.completeRespByDuration = mapOf(11f to (11f to true))
        vm.beginExerciseSession(); vm.submitExercise(11f, safetyNoticeConfirmed = true)
        advanceUntilIdle()
        assertEquals(11f, vm.todayExerciseMin)
        assertTrue(vm.todayGoalReached)

        // 자정 넘긴 다음 날 첫 운동: 서버 당일 누적이 2분·미달로 리셋됨.
        fake.completeRespByDuration = mapOf(2f to (2f to false))
        vm.beginExerciseSession(); vm.submitExercise(2f, safetyNoticeConfirmed = true)
        advanceUntilIdle()
        assertEquals("전날 값이 남지 않고 새 날 누적으로 초기화", 2f, vm.todayExerciseMin)
        assertFalse("전날 달성 상태가 스티키하게 남지 않는다", vm.todayGoalReached)
    }

    @Test
    fun `목표 미달이면 누적분은 갱신하되 목표달성은 false 로 둔다`() = runTest {
        val fake = FakeMissionApi(listOf(exerciseMission(templateId = 7)))
        fake.completeDailyTotal = 3f
        fake.completeSuccess = false // 아직 하루 목표(10분) 미달
        val vm = vmWith(fake)

        vm.submitExercise(3f, safetyNoticeConfirmed = true)
        advanceUntilIdle()

        assertEquals("진행 중이어도 현재 누적분은 보여준다", 3f, vm.todayExerciseMin)
        assertFalse("목표 미달이면 달성 안내는 아직 아니다", vm.todayGoalReached)
    }

    @Test
    fun `이어보기 위치는 저장 전 0, 저장하면 그 값, 0 저장이면 처음부터로 되돌린다`() {
        val vm = vmWith(FakeMissionApi(emptyList()))
        val url = "https://v/seated.mp4"
        assertEquals("저장 전엔 처음부터", 0L, vm.resumePositionFor(url))

        vm.saveResumePosition(url, 12_000L)
        assertEquals("이탈 위치를 보관해 다시 열면 이어재생", 12_000L, vm.resumePositionFor(url))

        // 완주(끝까지 시청) 시 StreamingVideoPlayer 가 0 을 넘긴다 → 다음 진입은 처음부터.
        vm.saveResumePosition(url, 0L)
        assertEquals("완주 후엔 처음부터로 리셋", 0L, vm.resumePositionFor(url))
    }
}
