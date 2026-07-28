package com.aihealthcare.ah0404.mission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic

/**
 * ============================================================================
 *  AdaptiveWalkingSessionController : 걸음 소스를 실측 근거로 고르는 컨트롤러
 * ============================================================================
 *
 *  왜(실기기 비교 결론):
 *   하드웨어 만보기(TYPE_STEP_COUNTER)가 자체 가속도계 검출보다 정확했다(SM-F766N 실측).
 *    - 정상보행: 만보기 −2~5%(방향 일정) vs 가속도계 −22~24%(부호까지 뒤집힘, 긴 산책).
 *    - 앉기 직후 오탐(#131): 만보기는 하드웨어가 스스로 걸러 게이트 불필요.
 *    - 발끌기(느린/끄는 보행): 만보기·가속도계 둘 다 0. 알고리즘으로 안 쫓고 **알려진 한계로 수용**한다
 *      (안내로 정상 보행 유도). 하드웨어 만보기도 못 잡는 신호라 가속도계 갭필은 정확도 대비 오탐
 *      리스크가 커 채택하지 않는다.
 *   그래서 **가능하면 만보기, 아니면 가속도계** 로 자동 선택한다.
 *
 *  선택 규칙:
 *   - 만보기 하드웨어 지원 && ACTIVITY_RECOGNITION 허용 → StepCounterWalkingSession(만보기 단일 소스).
 *   - 미지원 or 권한 거부 → ServiceWalkingSessionController(가속도계, 권한 불필요 → 항상 측정 가능).
 *
 *  ⚠️ 소스 결정은 **start() 시점**에 확정한다. 화면 진입에서 권한을 요청하므로, start 를 누를 때쯤엔
 *     허용/거부가 정해져 있어 결정이 확실하다(생성 시점에 정하면 권한 응답 전이라 어긋난다).
 *  드롭인: VM/상태 머신(#90)은 이 컨트롤러가 WalkingSessionController 계약을 그대로 만족하므로 무변경.
 * ============================================================================
 */
class AdaptiveWalkingSessionController(
    private val appContext: Context,
    // 소스 구현은 주입 가능하게 둔다(테스트 대체용). 기본은 실제 구현.
    private val stepCounterFactory: () -> WalkingSessionController = { StepCounterWalkingSession(appContext) },
    private val accelerometerFactory: () -> WalkingSessionController = { ServiceWalkingSessionController(appContext) },
) : WalkingSessionController {

    /** start() 에서 확정되는 실제 위임 대상. 확정 전에는 null. */
    private var active: WalkingSessionController? = null
    private var usingStepCounter = false
    private var triedAccelFallback = false

    /** 측정 시작 전에는 "둘 중 하나라도 가능한가"로 보고한다(READY 화면 버튼 노출용). */
    override val isSensorAvailable: Boolean
        get() = active?.isSensorAvailable
            ?: (StepCounterWalkingSession.isSupported(appContext) || accelerometerPresent())

    override val steps: Int get() = active?.steps ?: 0

    override val state: WalkingStepDetectorLogic.State
        get() = active?.state ?: WalkingStepDetectorLogic.State.IDLE

    override fun start(): Boolean {
        if (active == null) {
            usingStepCounter = shouldUseStepCounter(
                stepCounterSupported = StepCounterWalkingSession.isSupported(appContext),
                activityRecognitionGranted = activityRecognitionGranted(),
            )
            active = if (usingStepCounter) stepCounterFactory() else accelerometerFactory()
            Log.i(TAG, "걸음 소스 선택 → ${if (usingStepCounter) "만보기(TYPE_STEP_COUNTER)" else "가속도계"}")
        }
        if (active!!.start()) return true

        // 만보기로 골랐는데 시작 실패(등록 실패 등) → 가속도계로 1회 폴백해 측정이 죽지 않게 한다.
        if (usingStepCounter && !triedAccelFallback) {
            triedAccelFallback = true
            active!!.cancel()
            active = accelerometerFactory()
            usingStepCounter = false
            Log.w(TAG, "만보기 시작 실패 → 가속도계로 폴백")
            return active!!.start()
        }
        return false
    }

    override fun pause() { active?.pause() }

    override fun resume(): Boolean = active?.resume() ?: false

    override fun cancel() {
        active?.cancel()
        // 다음 진입에서 권한 상태가 바뀌었을 수 있으므로 소스 결정을 리셋한다.
        active = null
        usingStepCounter = false
        triedAccelFallback = false
    }

    override fun elapsedSec(): Int = active?.elapsedSec() ?: 0

    override fun stop(): WalkingSnapshot =
        active?.stop() ?: WalkingSnapshot(steps = 0, durationSec = 0)

    private fun activityRecognitionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun accelerometerPresent(): Boolean {
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    companion object {
        private const val TAG = "AdaptiveWalkingCtrl"

        /**
         * 만보기를 쓸지 결정하는 순수 규칙 — 하드웨어 지원 && 권한 허용일 때만.
         * (안드로이드 의존 없는 순수 함수라 JVM 단위테스트로 검증한다.)
         */
        fun shouldUseStepCounter(
            stepCounterSupported: Boolean,
            activityRecognitionGranted: Boolean,
        ): Boolean = stepCounterSupported && activityRecognitionGranted
    }
}
