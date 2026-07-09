package com.aihealthcare.ah0404.mission

import android.util.Log
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.MissionLogCreateRequest
import com.aihealthcare.ah0404.network.MissionLogUpdateRequest
import com.aihealthcare.ah0404.network.SensorSessionCreateRequest
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.network.WalkingDetail
import com.aihealthcare.ah0404.network.retrofit
import kotlin.math.roundToInt

/**
 * ============================================================================
 *  WalkingFlowUseCase : "걷기 미션" 백엔드 흐름을 화면 없이 태우는 부품
 * ============================================================================
 *
 *  목적:
 *   - 실제 센서/화면을 붙이기 전에, 걷기 미션의 서버 흐름이 계약대로 도는지
 *     "가짜 데이터"로 먼저 검증한다. (API 흐름 검증 ↔ 센서 정확도 검증을 분리)
 *
 *  흐름(명세 §미션 수행 - 걷기):
 *   ① 게스트 로그인          → access_token 확보(TokenHolder)
 *   ② POST /mission-logs     → status="in_progress" 로 걷기 시작, mission_log_id 확보
 *   ③ POST /sensor-sessions  → sensor_type="accelerometer" + 걸음/시간 저장
 *   ④ PATCH /mission-logs/{id} → status="completed", success=true, walking_detail 로 완료
 *
 *  request/response 본문은 NetworkClient 의 HttpLoggingInterceptor(BODY) 가
 *  Logcat 에 자동으로 찍는다. 아래 Log 는 단계 구분용 마커.
 *
 *  나중에 ④ 단계에서 fakeSteps/fakeDurationSec 자리에 WalkingStepDetectorLogic 이
 *  누적한 실제 값만 꽂으면 실제 센서 연동으로 넘어간다.
 * ============================================================================
 */
class WalkingFlowUseCase(
    private val api: MissionApi = retrofit.create(MissionApi::class.java),
    // 보폭(m). 거리(km) = 걸음수 × strideMeters / 1000. 성인 평균 ~0.7m.
    private val strideMeters: Float = 0.7f,
) {

    data class Result(
        val missionLogId: Int,
        val steps: Int,
        val durationMin: Float,
        val distanceKm: Float,
        val finalStatus: String,
        val success: Boolean,
        val dailyTotalMin: Float?,
    )

    /**
     * 가짜 데이터로 걷기 전체 흐름을 1회 태운다.
     * @param missionTemplateId 대상 걷기 미션 템플릿 id (GET /missions 로 확인한 값)
     */
    suspend fun runFakeWalkingFlow(
        missionTemplateId: Int,
        fakeSteps: Int = 1000,
        fakeDurationSec: Int = 600,
    ): Result {
        // ① 게스트 로그인 → 토큰
        val login = api.guestLogin()
        TokenHolder.token = login.accessToken
        Log.i(TAG, "① 게스트 로그인 OK (token ${login.accessToken.take(12)}…)")

        // ② 걷기 시작 (in_progress)
        val started = api.createMissionLog(
            MissionLogCreateRequest(
                missionTemplateId = missionTemplateId,
                missionType = "walking",
                status = "in_progress",
            )
        )
        val logId = started.missionLogId
        Log.i(TAG, "② 걷기 시작 OK → mission_log_id=$logId, status=${started.status}")

        // ③ 센서 결과 저장 (accelerometer, 가짜 걸음/시간)
        val sensor = api.createSensorSession(
            SensorSessionCreateRequest(
                missionLogId = logId,
                sensorType = "accelerometer",
                recognitionStatus = "success",
                detectedCount = fakeSteps,
                durationSec = fakeDurationSec,
            )
        )
        Log.i(TAG, "③ 센서 저장 OK → sensor_session_id=${sensor.sensorSessionId}, status=${sensor.recognitionStatus}")

        // ④ 걷기 완료 (completed + success=true + walking_detail)
        val durationMin = fakeDurationSec / 60f
        val distanceKm = (fakeSteps * strideMeters / 1000f).let { (it * 100).roundToInt() / 100f }
        val completed = api.completeMissionLog(
            missionLogId = logId,
            body = MissionLogUpdateRequest(
                success = true,   // ⚠️ 누락 시 서버가 실패로 처리할 수 있음
                status = "completed",
                walkingDetail = WalkingDetail(
                    durationMin = durationMin,
                    distanceKm = distanceKm,
                    steps = fakeSteps,
                ),
            ),
        )
        Log.i(
            TAG,
            "④ 걷기 완료 OK → status=${completed.status}, success=${completed.success}, " +
                "daily_result=${completed.dailyResult}, daily_total_min=${completed.dailyTotalMin}",
        )

        return Result(
            missionLogId = logId,
            steps = fakeSteps,
            durationMin = durationMin,
            distanceKm = distanceKm,
            finalStatus = completed.status,
            success = completed.success,
            dailyTotalMin = completed.dailyTotalMin,
        )
    }

    companion object {
        const val TAG = "WalkingFlow"
    }
}
