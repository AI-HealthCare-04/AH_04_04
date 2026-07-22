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

    /** 화면을 벗어날 때: 센서만 해제(누적값 유지). */
    fun pause()

    /** 화면에 돌아올 때: 측정 중이면 센서 재등록. */
    fun resume()

    /** 현재까지 경과 시간(초). */
    fun elapsedSec(): Int

    /** 측정 종료 → 센서 해제 + 걸음/경과를 고정한 불변 스냅샷 반환. **서버 제출은 하지 않는다(#91).** */
    fun stop(): WalkingSnapshot
}
