package com.aihealthcare.ah0404.mission

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic
import kotlin.math.max

/**
 * ============================================================================
 *  StepCounterWalkingSession : 하드웨어 만보기(TYPE_STEP_COUNTER) 기반 세션 (단일 소스)
 * ============================================================================
 *
 *  왜(실기기 비교 결론):
 *   가속도계 자체 검출(WalkingSession)은 긴 자연보행에서 오차가 −22~24%로 조건에 따라 부호까지 뒤집혀
 *   단일 보정계수로 못 잡는다(SM-F766N 실측). 하드웨어 만보기는 제조사가 튜닝한 걸음 센서라 정상보행에서
 *   −2~5%로 안정적이고 방향도 일정했다(3분20초 244/250). 그래서 지원 기기는 이 소스를 쓴다.
 *   (소스 선택·폴백은 AdaptiveWalkingSessionController 가 담당)
 *
 *  단일 진실 소스:
 *   VM(WalkingSessionViewModel)은 화면 실시간 표시(poll)와 최종 총계(stop) 둘 다 `steps` 하나만
 *   읽는다. 컨트롤러만 이걸로 바꾸면 **화면 숫자 = 결과 숫자**가 구조적으로 보장된다(어긋남 없음).
 *
 *  TYPE_STEP_COUNTER 특성 활용:
 *   - 값은 "부팅 후 누적 걸음 수"(단조 증가). → 시작 시점 값을 base 로 잡고 steps = latest - base.
 *   - 누적이라 잠깐 센서를 해제(pause)해도 다음 이벤트가 절대값을 줘 **걸음 손실이 없다**
 *     (재등록 후 latest - base 그대로 유효).
 *   - ⚠️ 대가: 배터리 절약을 위해 하드웨어가 몇 걸음 모아 배칭 전달 → 초반 몇 걸음이 늦게 뜨고
 *     실시간 카운트가 뚝뚝 올라간다(정확도와 맞바꾼 부분, 화면=결과 일치는 유지).
 *
 *  ⚠️ API 29+ 에선 ACTIVITY_RECOGNITION 런타임 권한이 있어야 이벤트가 전달된다(호출부가 요청).
 *     권한이 없으면 registerListener 는 성공해도 이벤트가 오지 않아 steps 가 0 에 머문다.
 * ============================================================================
 */
class StepCounterWalkingSession(
    context: Context,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : WalkingSessionController {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var running = false
    private var registered = false

    // 부팅 후 누적 걸음(절대값). base 는 이번 세션 시작 시점 값, latest 는 최신 값. base<0 = 아직 첫 이벤트 전.
    private var baseCount = -1f
    private var latestCount = -1f

    // 마지막 걸음 이벤트 시각(elapsedRealtime 기준) — 보행중/멈춤 배지 판정용.
    private var lastStepEventMs = 0L

    private val activeTime = ActiveTimeAccumulator(nowMs)

    /** 이 기기가 하드웨어 만보기를 지원하는가. */
    override val isSensorAvailable: Boolean get() = stepSensor != null

    /** 이번 세션 걸음 수 = 누적 최신값 - 시작값. 첫 이벤트 전에는 0. */
    override val steps: Int
        get() = if (baseCount < 0f || latestCount < 0f) 0 else max(0, (latestCount - baseCount).toInt())

    /** 최근 걸음 이벤트가 있었으면 WALKING, 잠잠하면 IDLE(배지·잠정표시 판정). */
    override val state: WalkingStepDetectorLogic.State
        get() = if (running && registered && baseCount >= 0f &&
            nowMs() - lastStepEventMs < WALKING_IDLE_MS
        ) {
            WalkingStepDetectorLogic.State.WALKING
        } else {
            WalkingStepDetectorLogic.State.IDLE
        }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val v = event.values[0]
            if (baseCount < 0f) baseCount = v // 이번 세션 시작 기준점(첫 이벤트에서 확정)
            latestCount = v
            lastStepEventMs = nowMs()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start(): Boolean {
        if (running) return registered
        baseCount = -1f
        latestCount = -1f
        lastStepEventMs = 0L
        activeTime.start()
        running = true
        registerSensor()
        if (!registered) {
            running = false
            activeTime.stop()
            Log.w(TAG, "만보기 등록 실패 → 세션 롤백")
        } else {
            Log.i(TAG, "만보기 세션 시작")
        }
        return registered
    }

    override fun pause() {
        // #199 정합: 만보기는 누적 카운터라 백그라운드(화면 꺼짐·주머니)에서도 걸음이 계속 쌓여 복귀 시
        //   반영된다. 여기서 시계를 동결하면 '걸음은 느는데 시간만 멈춰' steps↔durationSec 짝이 왜곡된다
        //   (리뷰 #223: 주머니 10분 보행 → 걸음은 전체·시간은 몇 초). 그래서 no-op 로 둔다 — 센서·시계를
        //   모두 유지해 '걸음이 쌓이는 동안 시간도 흐른다'를 보장한다(가속도계 FGS 경로의 pause no-op 와 동일 의미).
    }

    override fun resume(): Boolean =
        // 백그라운드에서도 세션이 계속 유효했으므로(no-op pause) 재등록/시계 재개가 필요 없다. 지원 여부만 보고.
        running && isSensorAvailable

    override fun cancel() {
        running = false
        unregisterSensor()
        activeTime.stop()
        Log.i(TAG, "만보기 세션 취소(화면 이탈)")
    }

    override fun elapsedSec(): Int = (activeTime.elapsedMs() / 1000L).toInt()

    override fun stop(): WalkingSnapshot {
        running = false
        unregisterSensor()
        activeTime.stop()
        val snapshot = WalkingSnapshot(steps = steps, durationSec = elapsedSec())
        Log.i(TAG, "만보기 세션 종료 → steps=${snapshot.steps}, durationSec=${snapshot.durationSec}")
        return snapshot
    }

    private fun registerSensor() {
        if (registered || stepSensor == null) return
        registered = sensorManager.registerListener(
            listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL,
        )
    }

    private fun unregisterSensor() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }

    companion object {
        private const val TAG = "StepCounterSession"

        /** 최근 이 시간(ms) 안에 걸음 이벤트가 있으면 '걷는 중'으로 본다(만보기 배칭 여유 포함). */
        private const val WALKING_IDLE_MS = 3000L

        /** 이 기기가 하드웨어 만보기를 지원하는가(컨트롤러 선택용, 인스턴스 없이 확인). */
        fun isSupported(context: Context): Boolean {
            val sm = context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        }
    }
}
