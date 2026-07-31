package com.aihealthcare.ah0404.mission

/**
 * 걷기 측정 포그라운드 서비스(#199)와 측정 세션 컨트롤러 사이의 **프로세스 공유 지점**.
 *
 * 서비스(WalkingMeasurementService)는 화면·ViewModel 수명과 무관하게 살아서 지속 알림을 갱신해야
 * 하는데, 걸음 수의 원천은 컨트롤러가 소유한 단일 세션이다. 두 곳이 각자 세션을 만들면 이중 계수가
 * 되므로, 컨트롤러가 자신의 세션 걸음 수를 이 홀더에 '공급자'로 등록하고 서비스는 그것만 읽는다
 * (서비스는 WalkingSession 구현 세부를 몰라도 된다).
 *
 * ⚠️ Activity/프로세스 종료 후 재부착(이어보기)은 증분 A 범위 밖(#90 프로세스 death 범위 밖과 동일).
 *    여기서는 '화면 꺼짐/백그라운드(프로세스 생존)' 동안의 측정 지속만 다룬다.
 */
object WalkingMeasurement {
    /** 현재 걸음 수 공급자. 컨트롤러가 측정 시작 시 { session.steps } 로 설정하고 종료 시 null 로 지운다. */
    @Volatile
    var stepsProvider: (() -> Int)? = null

    /** 알림 표시용 현재 걸음 수(공급자 없으면 0). */
    fun currentSteps(): Int = stepsProvider?.invoke() ?: 0

    /**
     * 알림 '중단' 액션(#312 요청 2)의 역방향 신호. VM(WalkingSessionViewModel)이 측정 시작 시
     * { finish() } 로 등록하고 종료/이탈 시 null 로 지운다. 서비스는 이 콜백만 부르고 세션 내부를 모른다
     * — finish() 경로라 걸음 스냅샷이 확정돼 복귀 시 완료 화면·제출로 이어진다(측정값 유실 없음).
     */
    @Volatile
    var stopRequestListener: (() -> Unit)? = null
}

/**
 * 지속 알림 본문 텍스트. 순수 함수라 단위 테스트로 고정한다(리뷰 대비).
 * 예: "걷기 측정 중 · 128보".
 */
internal fun walkingMeasurementNotificationText(steps: Int): String =
    "걷기 측정 중 · ${steps}보"
