package com.aihealthcare.ah0404.mission

import android.content.Context
import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic

/**
 * ============================================================================
 *  ServiceWalkingSessionController : 백그라운드 지속 측정용 세션 컨트롤러 (#199, 증분 A)
 * ============================================================================
 *
 *  WalkingSessionViewModel 이 의존하는 WalkingSessionController 의 구현으로, 기존 WalkingSession
 *  (가속도계)을 **그대로** 소유하되 측정 중에는 WalkingMeasurementService(포그라운드)를 띄워
 *  화면이 꺼지거나 백그라운드로 가도 프로세스가 살아 측정이 이어지게 한다. VM/화면은 이 컨트롤러를
 *  주입만 바꾸면 되고 세션 상태 머신(#90)은 그대로다(drop-in).
 *
 *  기존 WalkingSession 대비 **핵심 동작 변화는 pause() 가 no-op** 이라는 점이다:
 *   - 기존: onPause 에 센서를 해제(+경과 시계 동결)해 화면을 벗어나면 측정이 멈췄다(#199 문제).
 *   - 여기: 백그라운드에서도 계속 세도록 센서를 놓지 않는다. 대신 화면을 '완전히' 떠날 때
 *     (leave()→reset()→cancel(), Activity 종료→onCleared→cancel())만 세션·서비스를 내린다.
 *     → 백그라운드/구성 변경에서만 지속되고, 실제 이탈에서는 확실히 정리돼 FGS 누수가 없다.
 *
 *  이중 계수 방지: 서비스는 센서를 직접 등록하지 않고, 이 컨트롤러가 소유한 단일 세션의 걸음 수를
 *  WalkingMeasurement 홀더의 공급자로 읽어 알림만 갱신한다.
 * ============================================================================
 */
class ServiceWalkingSessionController(
    private val appContext: Context,
    private val session: WalkingSession = WalkingSession(appContext),
) : WalkingSessionController {

    override val isSensorAvailable: Boolean get() = session.isSensorAvailable
    override val steps: Int get() = session.steps
    override val state: WalkingStepDetectorLogic.State get() = session.state

    override fun start(): Boolean {
        // 센서 등록은 동기로 즉시 확정된다(start() 의 Boolean 계약 유지). 성공한 경우에만 서비스를 띄운다.
        val ok = session.start()
        if (ok) {
            WalkingMeasurement.stepsProvider = { session.steps }
            WalkingMeasurementService.start(appContext)
        }
        return ok
    }

    override fun pause() {
        // #199: 백그라운드에서도 측정을 이어가기 위해 센서를 해제하지 않는다(no-op).
        //   FGS 가 프로세스를 살려 두어 센서 전달이 유지된다. 기존 WalkingSession.pause()(센서 해제·
        //   경과 시계 동결)를 부르지 않는 것이 이 컨트롤러의 핵심이다.
    }

    override fun resume(): Boolean =
        // 백그라운드에서도 세션이 계속 돌았으므로 재등록이 필요 없다. 센서 지원 여부만 그대로 보고한다.
        session.isSensorAvailable

    override fun cancel() {
        session.cancel()
        teardownService()
    }

    override fun elapsedSec(): Int = session.elapsedSec()

    override fun stop(): WalkingSnapshot {
        val snapshot = session.stop()
        teardownService()
        return snapshot
    }

    private fun teardownService() {
        WalkingMeasurement.stepsProvider = null
        WalkingMeasurementService.stop(appContext)
    }
}
