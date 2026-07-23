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
 *  steps/durationSec 에는 WalkingSession 이 실제 가속도계로 측정한 값을 넘긴다.
 *  (헤드리스 테스트는 고정값을 넘겨 흐름만 검증)
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
     * 걷기 전체 흐름을 1회 태운다. steps/durationSec 에 실제 센서 측정값(또는
     * 헤드리스 테스트용 고정값)을 넘긴다.
     * @param missionTemplateId 대상 걷기 미션 템플릿 id (GET /missions 로 확인한 값)
     * @param steps 측정된 걸음 수 (WalkingStepDetectorLogic.count)
     * @param durationSec 세션 경과 시간(초)
     */
    suspend fun runWalkingFlow(
        missionTemplateId: Int,
        steps: Int = 1000,
        durationSec: Int = 600,
    ): Result {
        // ① 게스트 로그인 → 토큰. (헤드리스 데모 전용 — 실경로는 이미 로그인된 토큰을 쓰므로 submitWalkingSession 사용)
        val login = api.guestLogin()
        TokenHolder.token = login.accessToken
        Log.i(TAG, "① 게스트 로그인 OK (token ${login.accessToken.take(12)}…)")
        // 데모는 재전송 검증 대상이 아니라 자연 키를 보내지 않는다(null → 서버 유니크 제외).
        return submitWalkingSession(missionTemplateId, steps, durationSec, createdOnDeviceAt = null)
    }

    /**
     * 실경로 제출 파이프라인 — **이미 로그인된 토큰**으로 걷기 세션 한 건을 서버에 올린다(#91).
     *
     *  게스트 로그인(①)을 하지 않는다. 실경로는 소셜/게스트로 이미 인증된 상태이고, 여기서 다시
     *  guestLogin 을 부르면 전역 토큰을 덮어써 계정이 갈린다(#160 과 같은 사고).
     *
     *  ② POST in_progress → ③ sensor-sessions → ④ PATCH completed(walking_detail).
     *  measurement 종료 시 한 번에 태우는 A안(측정 중 로컬만, 종료 시 완료 이벤트) 구현이다.
     *
     *  재시도(네트워크 실패 후 사용자가 다시 누름)와 자동 재전송(post-v1 outbox)이 **같은 함수**를
     *  호출하도록 순수 제출 로직만 담는다 — 호출부(UI/큐)만 달라진다.
     *
     * @param createdOnDeviceAt 측정 시작 시각(ISO-8601). 재전송 때 **같은 값**을 넘겨야 #158 자연 키로
     *   중복 집계가 막힌다. 그래서 이 값은 호출부(VM)가 측정 시작 시 한 번 잡아 고정한 것을 넘긴다.
     */
    suspend fun submitWalkingSession(
        missionTemplateId: Int,
        steps: Int,
        durationSec: Int,
        createdOnDeviceAt: String?,
    ): Result {
        // ② 걷기 시작 (in_progress) — 자연 키(created_on_device_at) 동봉
        val started = api.createMissionLog(
            MissionLogCreateRequest(
                missionTemplateId = missionTemplateId,
                missionType = "walking",
                status = "in_progress",
                createdOnDeviceAt = createdOnDeviceAt,
            )
        )
        val logId = started.missionLogId
        Log.i(TAG, "② 걷기 시작 OK → mission_log_id=$logId, status=${started.status}")

        // ③ 센서 결과 저장 (accelerometer, 측정된 걸음/시간)
        val sensor = api.createSensorSession(
            SensorSessionCreateRequest(
                missionLogId = logId,
                sensorType = "accelerometer",
                recognitionStatus = "success",
                detectedCount = steps,
                durationSec = durationSec,
            )
        )
        Log.i(TAG, "③ 센서 저장 OK → sensor_session_id=${sensor.sensorSessionId}, status=${sensor.recognitionStatus}")

        // ④ 걷기 완료 (completed + success=true + walking_detail)
        val durationMin = durationSec / 60f
        val distanceKm = (steps * strideMeters / 1000f).let { (it * 100).roundToInt() / 100f }
        val completed = api.completeMissionLog(
            missionLogId = logId,
            body = MissionLogUpdateRequest(
                success = true,   // ⚠️ 누락 시 서버가 실패로 처리할 수 있음
                status = "completed",
                walkingDetail = WalkingDetail(
                    durationMin = durationMin,
                    distanceKm = distanceKm,
                    steps = steps,
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
            steps = steps,
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
