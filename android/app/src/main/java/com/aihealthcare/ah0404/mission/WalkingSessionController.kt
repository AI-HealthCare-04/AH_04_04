package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic

/**
 * 측정 종료 시 확정되는 **불변 세션 스냅샷**(걸음 수 + 경과 초).
 * 실제 서버 업로드·거리 계산·멱등 처리는 #91(#105 이후) 소관 — 이 화면은 값만 확정한다.
 */
data class WalkingSnapshot(val steps: Int, val durationSec: Int)

/**
 * 걷기 측정 세션의 제어 표면(seam). WalkingSession 이 실제 가속도계로 구현하고,
 * WalkingSessionViewModel 은 이 인터페이스에만 의존한다.
 *
 * 목적: 센서 없이 ViewModel 의 세션 상태 머신(#90)을 순수 JVM 테스트로 검증하기 위함
 *   — 실기기 센서 정확도(#89)와 화면 상태 전이를 분리한다.
 */
interface WalkingSessionController {
    /** 이 기기가 가속도계를 지원하는가. */
    val isSensorAvailable: Boolean

    /** 지금까지 누적된 걸음 수. */
    val steps: Int

    /** 현재 보행 상태(IDLE/WALKING). */
    val state: WalkingStepDetectorLogic.State

    /** 측정 시작. @return 센서 등록 성공 여부(미지원이거나 registerListener 실패 시 false). */
    fun start(): Boolean

    /** 화면을 벗어날 때(onPause·백그라운드): 센서만 해제(누적값·측정중 상태 유지 → resume 로 재개). */
    fun pause()

    /**
     * 화면에 돌아올 때(onResume): 측정 중이면 센서 재등록 + 경과 시계 재개.
     * @return 측정이 정상 재개됐는가. 센서 재등록에 실패하면 시계를 재개하지 않고 false 를 돌려주며
     *   (경과만 흐르는 드리프트 방지), 호출부는 READY/재시도 안내로 수렴시킨다.
     */
    fun resume(): Boolean

    /**
     * 화면을 완전히 떠날 때(뒤로/완료 후 확인): 측정을 **중단**한다 — 센서 해제 + 활성 시계 종료 +
     * 내부 running 플래그 해제. pause() 와 달리 이후 같은 세션에서 start() 로 **재시작이 가능**하다.
     * (pause 는 running 을 유지해 재시작 불가 → 화면 이탈엔 반드시 cancel 을 쓴다.)
     */
    fun cancel()

    /** 현재까지 경과 시간(초). */
    fun elapsedSec(): Int

    /** 측정 종료 → 센서 해제 + 걸음/경과를 고정한 불변 스냅샷 반환. **서버 제출은 하지 않는다(#91).** */
    fun stop(): WalkingSnapshot
}
