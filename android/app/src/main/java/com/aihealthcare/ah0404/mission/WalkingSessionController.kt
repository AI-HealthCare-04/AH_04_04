package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic

/**
 * 걷기 측정 세션의 제어 표면(seam). WalkingSession 이 실제 가속도계로 구현하고,
 * WalkingSessionViewModel 은 이 인터페이스에만 의존한다.
 *
 * 목적: 센서/서버 없이 ViewModel 의 세션 상태 머신(#90)을 순수 JVM 테스트로 검증하기 위함
 *   — 실기기 센서 정확도(#89)와 화면 상태 전이를 분리한다.
 */
interface WalkingSessionController {
    /** 이 기기가 가속도계를 지원하는가. */
    val isSensorAvailable: Boolean

    /** 지금까지 누적된 걸음 수. */
    val steps: Int

    /** 현재 보행 상태(IDLE/WALKING). */
    val state: WalkingStepDetectorLogic.State

    /** 측정 시작. @return 센서 등록 성공 여부(미지원 시 false). */
    fun start(): Boolean

    /** 화면을 벗어날 때: 센서만 해제(누적값 유지). */
    fun pause()

    /** 화면에 돌아올 때: 측정 중이면 센서 재등록. */
    fun resume()

    /** 현재까지 경과 시간(초). */
    fun elapsedSec(): Int

    /** 측정 종료 → 실제 걸음/시간으로 서버 흐름을 태우고 결과를 반환. */
    suspend fun stopAndSubmit(missionTemplateId: Int): WalkingFlowUseCase.Result
}
