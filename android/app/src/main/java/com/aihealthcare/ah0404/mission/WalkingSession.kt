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
 *   4) 종료 시 걸음/경과를 **불변 스냅샷으로 고정**해 반환한다.
 *
 *  ⚠️ TYPE_STEP_COUNTER 가 아니라 가속도계 기반이다(ACTIVITY_RECOGNITION 권한 불필요).
 *  ⚠️ 서버 업로드는 하지 않는다. 실제 미션 로그 생성·멱등 처리·거리 계산은 #91(#105 이후) 소관.
 *     (기존 데모 하네스 WalkingFlowUseCase 는 guestLogin 으로 전역 토큰을 덮어써 실경로에 태우면
 *      로그인 세션이 게스트로 교체되므로, #91 에서 인증 유지 계약을 잡기 전까지 연결하지 않는다.)
 *
 *  생명주기 연동(호출부에서 이어줄 것):
 *   - onResume → resume(), onPause → pause() : 화면 벗어나면 센서 해제(누수 방지)
 *   - 측정 종료 → stop() : 센서 해제 + 스냅샷 확정
 * ============================================================================
 */
class WalkingSession(
    context: Context,
    private val detector: WalkingStepDetectorLogic = WalkingStepDetectorLogic(),
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : WalkingSessionController {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var running = false
    private var registered = false

    // 경과 시간은 "센서 등록돼 실제 측정 중이던 전경 시간"만 누적한다(백그라운드 드리프트 제거).
    private val activeTime = ActiveTimeAccumulator(nowMs)

    /** 이 기기가 가속도계를 지원하는가. */
    override val isSensorAvailable: Boolean get() = accelSensor != null

    /** 지금까지 누적된 걸음 수. */
    override val steps: Int get() = detector.count

    /** 현재 보행 상태(IDLE/WALKING). */
    override val state: WalkingStepDetectorLogic.State get() = detector.state

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
    override fun start(): Boolean {
        if (running) return registered
        detector.reset()
        activeTime.start()
        running = true
        registerSensor()
        if (!registered) {
            // 가속도계 객체는 있어도 registerListener 가 실패할 수 있다 → 세션을 시작 전으로 롤백.
            running = false
            activeTime.stop()
            Log.w(TAG, "가속도계 등록 실패 → 세션 롤백")
        } else {
            Log.i(TAG, "세션 시작")
        }
        return registered
    }

    /**
     * 화면을 벗어날 때(onPause)·백그라운드: 센서를 해제하고 경과 시계도 **동결**한다.
     * 걸음을 못 세는 구간은 시간에서도 빼서 걸음↔시간 정합을 지킨다(누적 걸음 수는 유지).
     */
    override fun pause() {
        unregisterSensor()
        activeTime.pause()
    }

    /**
     * 화면에 돌아올 때(onResume): 측정 중이면 센서 재등록 + 경과 시계 재개.
     * ⚠️ 센서 재등록 성공(registered=true) 시에만 시계를 재개한다. 실패했는데 시계만 흘리면
     *    걸음은 못 세면서 경과만 늘어 이 PR 이 없애려는 드리프트가 재발하므로, 실패 시엔 세션을
     *    정지 상태로 만들고 false 를 돌려 호출부가 READY/재시도로 수렴하게 한다.
     * @return 측정이 정상 재개됐는가.
     */
    override fun resume(): Boolean {
        if (!running) return false
        registerSensor()
        if (registered) {
            activeTime.resume()
            return true
        }
        // 복귀 시 센서 재등록 실패 → 시계 재개하지 않고 세션 중단.
        running = false
        activeTime.stop()
        Log.w(TAG, "복귀 시 센서 재등록 실패 → 측정 중단(경과 시계 동결)")
        return false
    }

    /**
     * 화면 완전 이탈용 중단. 센서 해제 + 활성 시계 종료 + running 해제까지 명시적으로 처리해,
     * 같은 세션 인스턴스에서 이후 start() 로 **재시작이 가능**하도록 한다(pause 는 running 유지라 불가).
     */
    override fun cancel() {
        running = false
        unregisterSensor()
        activeTime.stop()
        Log.i(TAG, "세션 취소(화면 이탈) → 재시작 가능 상태로 정리")
    }

    /** 현재까지 실제 측정 중이던 경과 시간(초). 종료·정지 후에는 그 시점 기준으로 고정된다. */
    override fun elapsedSec(): Int = (activeTime.elapsedMs() / 1000L).toInt()

    /**
     * 측정 종료 → 센서 해제 후 걸음/경과 시간을 **불변 스냅샷으로 고정**해 반환한다.
     * 서버 업로드는 하지 않는다(#91). 재호출해도 경과 시간은 최초 종료 기준으로 고정된다.
     */
    override fun stop(): WalkingSnapshot {
        running = false
        unregisterSensor()
        activeTime.stop()

        val snapshot = WalkingSnapshot(steps = detector.count, durationSec = elapsedSec())
        Log.i(TAG, "세션 종료 → steps=${snapshot.steps}, durationSec=${snapshot.durationSec} (제출은 #91)")
        return snapshot
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

    private companion object {
        const val TAG = "WalkingSession"
    }
}
