package com.aihealthcare.ah0404.mission

import android.util.Log
import com.aihealthcare.ah0404.network.ExerciseDetail
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogUpdateRequest
import com.aihealthcare.ah0404.network.retrofit

/**
 * ============================================================================
 *  ExerciseFlowUseCase : "운동 미션" 완료를 서버에 올리는 부품(#234)
 * ============================================================================
 *
 *  배경:
 *   걷기([WalkingFlowUseCase])·식사(ProteinChallengeViewModel)는 완료를 서버에 보내는데 운동만
 *   exercise_detail 전송 코드가 없어, 여러 번 성공해도 '오늘의 미션' 집계에서 빠졌다
 *   ("4개 성공 → 1개 완료"의 주원인, #234). 서버는 이미 완비돼 있다:
 *   운동 성공 판정 = 당일 누적 '분' >= 목표(분), 목표는 하루 10분(여러 번 나눠 합산, #168/migration 0010).
 *   → 앱은 세션별 '수행한 분'만 실어 보내면 서버가 당일 합산한다. 클라 누적 로직은 필요 없다.
 *
 *  흐름(걷기와 동일하되 센서 단계 없음):
 *   ② POST /mission-logs      status="in_progress", mission_type="exercise",
 *                             safety_notice_confirmed(운동은 안전 고지 필요 — 아래 주의), created_on_device_at(#158 자연 키)
 *   ④ PATCH /mission-logs/{id} status="completed", success=true, exercise_detail=ExerciseDetail(durationMin)
 *
 *  ⚠️ 안전 고지(safety_notice_confirmed): 서버는 requires_safety_notice=true 인 미션(=운동)에서 이 값이
 *   true 가 아니면 시작 POST 를 400 으로 막는다(services/mission.py). 이는 "사용자가 안전 안내를 확인했다"는
 *   사실을 나타내는 안전 계약이므로 부품이 임의로 참을 만들면 안 된다(리뷰 P1-C). 그래서 이 값을
 *   **호출부에서 실제 확인 결과로 넘겨받는다**. 확인 게이트 UI 는 운동 진입 화면(#254)이 책임진다.
 *
 *  호출부(UI 배선, #254 이후):
 *   - 스트리밍(근력·서서): FullscreenLandscapeVideo.onWatched(durationMin)
 *   - 루틴(몸풀기·마무리): RoutinePlayerScreen.onComplete
 *
 *  걷기와 같은 이유로 **이미 로그인된 토큰**만 쓰고 guestLogin 을 부르지 않는다(전역 토큰 덮어쓰기 사고 #160).
 * ============================================================================
 */
class ExerciseFlowUseCase(
    private val api: MissionApi = retrofit.create(MissionApi::class.java),
) {

    data class Result(
        val missionLogId: Int,
        val durationMin: Float,
        val finalStatus: String,
        val success: Boolean,
        val countedForDaily: Boolean,
        val dailyTotalMin: Float?,
    )

    /**
     * 운동 세션 한 건의 완료를 서버에 올린다.
     *
     * @param missionTemplateId 대상 운동 미션 템플릿 id (GET /missions 로 확인한 값)
     * @param durationMin 이번 세션에 실제 수행/시청한 분(> 0). 서버가 당일 누적에 합산한다.
     * @param safetyNoticeConfirmed 사용자가 안전 안내를 **실제로 확인**했는지. 운동은 서버가 이 값을
     *   요구하므로(true 아니면 400) 반드시 확인 게이트를 거친 실제 값이어야 한다 — 부품이 참을 만들지 않는다.
     * @param createdOnDeviceAt 세션 시작 시각(ISO-8601). 재전송 시 **같은 값**을 넘겨야 #158 자연 키로
     *   중복 집계가 막힌다 → 호출부(VM)가 세션 시작 시 한 번 잡아 고정한 값을 넘긴다.
     */
    suspend fun submitExerciseSession(
        missionTemplateId: Int,
        durationMin: Float,
        safetyNoticeConfirmed: Boolean,
        createdOnDeviceAt: String? = null,
    ): Result {
        // 서버 ExerciseDetail.duration_min 은 gt=0. 0/음수는 당일 누적 되돌리기 방어로 거부되므로,
        //   즉시 이탈 등 0분 세션은 아예 보내지 않도록 호출 전에 여기서 먼저 막는다.
        require(durationMin > 0f) { "durationMin must be > 0 (was $durationMin)" }
        // 안전 고지 미확인이면 서버가 어차피 400 으로 막는다. 조작된 참을 보내지 않도록 호출 전에 거른다(P1-C).
        require(safetyNoticeConfirmed) { "safetyNoticeConfirmed must be true (안전 고지 확인 게이트를 거쳐야 함)" }

        // ② 운동 시작(in_progress) — 실제 안전 고지 확인 결과 + 자연 키 동봉
        val started = api.createMissionLog(
            MissionLogCreateRequest(
                missionTemplateId = missionTemplateId,
                missionType = "exercise",
                status = "in_progress",
                safetyNoticeConfirmed = safetyNoticeConfirmed,
                createdOnDeviceAt = createdOnDeviceAt,
            )
        )
        val logId = started.missionLogId
        Log.i(TAG, "② 운동 시작 OK → mission_log_id=$logId, status=${started.status}, deduplicated=${started.deduplicated}")

        // ★ 재전송 조기 종료(걷기 #172 와 동일): PATCH 커밋 뒤 응답만 유실돼 재시도가 오면, ②가 자연 키로
        //   이미 completed 된 로그를 돌려준다. 이때 ④PATCH 를 다시 하면 "이미 완료" 로 실패해 저장은 됐는데
        //   UI 는 계속 실패로 남는다. 이미 completed 면 여기서 성공으로 끝낸다.
        if (started.status == "completed") {
            Log.i(TAG, "② 재전송 감지 — 이미 완료된 운동 기록이라 완료 단계를 건너뛴다(mission_log_id=$logId)")
            return Result(
                missionLogId = logId,
                durationMin = durationMin,
                finalStatus = started.status,
                success = started.success,
                countedForDaily = started.countedForDaily,
                dailyTotalMin = null, // create 응답엔 누적값이 없다. 홈은 진입 시 재조회로 반영.
            )
        }

        // ④ 운동 완료(completed + success=true + exercise_detail). 걷기와 달리 센서 단계는 없다.
        val completed = api.completeMissionLog(
            missionLogId = logId,
            body = MissionLogUpdateRequest(
                success = true,   // ⚠️ 누락 시 서버가 실패로 처리할 수 있음
                status = "completed",
                exerciseDetail = ExerciseDetail(durationMin = durationMin),
            ),
        )
        Log.i(
            TAG,
            "④ 운동 완료 OK → status=${completed.status}, success=${completed.success}, " +
                "counted=${completed.countedForDaily}, daily_result=${completed.dailyResult}, " +
                "daily_total_min=${completed.dailyTotalMin}",
        )

        return Result(
            missionLogId = logId,
            durationMin = durationMin,
            finalStatus = completed.status,
            success = completed.success,
            countedForDaily = completed.countedForDaily,
            dailyTotalMin = completed.dailyTotalMin,
        )
    }

    private companion object {
        const val TAG = "ExerciseFlow"
    }
}
