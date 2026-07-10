package com.aihealthcare.ah0404.mission

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 걷기 미션 전체 흐름 headless 통합 테스트 (화면 없음).
 *
 *  ⚠️ 실행 전제: dev 백엔드가 켜져 있고 에뮬레이터에서 접근 가능해야 한다.
 *     - 백엔드: 로컬에서 8001 포트로 기동 (에뮬레이터는 10.0.2.2 로 호스트에 접근)
 *     - 실행:  ./gradlew :app:connectedDebugAndroidTest   또는 AS에서 이 테스트 실행
 *
 *  확인 포인트(명세): guest 로그인 → 걷기 시작(in_progress) → 센서 저장 → 완료(completed, success=true).
 *  request/response 원문은 Logcat 태그 "OkHttp"(HttpLoggingInterceptor) 및 "WalkingFlow" 로 확인.
 */
@RunWith(AndroidJUnit4::class)
class WalkingFlowInstrumentedTest {

    private val api = retrofit.create(MissionApi::class.java)

    @Test
    fun walkingFlow_completesWithSuccess() = runBlocking {
        // 로그인 후 걷기 미션 템플릿 id 를 실제 목록에서 찾는다(하드코딩 회피).
        TokenHolder.token = api.guestLogin().accessToken
        val walking = api.getMissions().missions.firstOrNull { it.missionType == "walking" }
        requireNotNull(walking) { "available 미션에 walking 타입이 없습니다. seed 확인 필요." }

        val result = WalkingFlowUseCase(api).runWalkingFlow(
            missionTemplateId = walking.missionTemplateId,
            steps = 1000,
            durationSec = 600,
        )

        assertEquals("completed", result.finalStatus)
        assertTrue("success 는 true 여야 한다", result.success)
        assertTrue("mission_log_id 가 발급되어야 한다", result.missionLogId > 0)
    }
}
