package com.aihealthcare.ah0404.mission

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic

/**
 * ============================================================================
 *  WalkingSession : 실제 가속도계 → 걷기 흐름 연결 부품
 * ============================================================================
 *
 *  하는 일:
 *   1) TYPE_ACCELEROMETER 센서를 등록/해제한다(생명주기 안전).
 *   2) 각 샘플을 WalkingStepDetectorLogic.processSample() 에 흘려 걸음 수를 누적.
 *   3) 시작~종료 경과 시간을 SystemClock.elapsedRealtime() 기준으로 측정.
 *   4) 종료 시 실제 걸음/시간을 WalkingFlowUseCase 에 넘겨 서버 흐름을 태운다.
 *
 *  ⚠️ TYPE_STEP_COUNTER 가 아니라 가속도계 기반이다(ACTIVITY_RECOGNITION 권한 불필요).
 *
 *  생명주기 연동(호출부에서 이어줄 것):
 *   - onResume → resume(), onPause → pause() : 화면 벗어나면 센서 해제(누수 방지)
 *   - 측정 종료 → stopAndSubmit() : 센서 해제 + 흐름 실행
 *
 *  UI 관찰(다음 단계): steps / state / elapsedSec() getter 로 현재값을 읽을 수 있다.
 * ============================================================================
 */
class WalkingSession(
    context: Context,
    private val detector: WalkingStepDetectorLogic = WalkingStepDetectorLogic(),
    private val useCase: WalkingFlowUseCase = WalkingFlowUseCase(),
) {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var running = false
    private var registered = false
    private var startElapsedMs = 0L
    private var endElapsedMs = 0L

    /** 이 기기가 가속도계를 지원하는가. */
    val isSensorAvailable: Boolean get() = accelSensor != null

    /** 지금까지 누적된 걸음 수. */
    val steps: Int get() = detector.count

    /** 현재 보행 상태(IDLE/WALKING). */
    val state: WalkingStepDetectorLogic.State get() = detector.state

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            // 센서 이벤트마다 걸음 감지 로직에 흘려보낸다. 시각은 단조 증가하는 elapsedRealtime(ms).
            detector.processSample(
                event.values[0], event.values[1], event.values[2],
                SystemClock.elapsedRealtime(),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * 측정 시작. 걸음 수/상태를 초기화하고 센서를 등록한다.
     * @return 센서 등록 성공 여부(가속도계 미지원 시 false)
     */
    fun start(): Boolean {
        if (running) return registered
        detector.reset()
        startElapsedMs = SystemClock.elapsedRealtime()
        endElapsedMs = 0L
        running = true
        registerSensor()
        Log.i(WalkingFlowUseCase.TAG, "세션 시작 (센서 등록=$registered)")
        return registered
    }

    /** 화면을 벗어날 때(onPause): 센서만 해제하고 누적값/시작시각은 유지. */
    fun pause() {
        unregisterSensor()
    }

    /** 화면에 돌아올 때(onResume): 측정 중이면 센서 재등록. */
    fun resume() {
        if (running) registerSensor()
    }

    /** 현재까지의 경과 시간(초). 종료 후에는 종료 시점 기준으로 고정된다. */
    fun elapsedSec(): Int {
        if (startElapsedMs == 0L) return 0
        val end = if (endElapsedMs != 0L) endElapsedMs else SystemClock.elapsedRealtime()
        return ((end - startElapsedMs) / 1000L).toInt()
    }

    /**
     * 측정 종료 → 실제 걸음/경과시간으로 걷기 흐름을 태운다.
     * (센서 해제 후 WalkingFlowUseCase.runWalkingFlow 실행)
     * @param missionTemplateId 대상 걷기 미션 템플릿 id
     */
    suspend fun stopAndSubmit(missionTemplateId: Int): WalkingFlowUseCase.Result {
        endElapsedMs = SystemClock.elapsedRealtime()
        running = false
        unregisterSensor()

        val measuredSteps = detector.count
        val measuredDurationSec = elapsedSec()
        Log.i(
            WalkingFlowUseCase.TAG,
            "세션 종료 → 실제 steps=$measuredSteps, durationSec=$measuredDurationSec 로 흐름 실행",
        )
        return useCase.runWalkingFlow(
            missionTemplateId = missionTemplateId,
            steps = measuredSteps,
            durationSec = measuredDurationSec,
        )
    }

    private fun registerSensor() {
        if (registered || accelSensor == null) return
        registered = sensorManager.registerListener(
            listener, accelSensor, SensorManager.SENSOR_DELAY_GAME,
        )
    }

    private fun unregisterSensor() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }
}
