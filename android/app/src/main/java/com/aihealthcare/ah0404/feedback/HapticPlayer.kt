package com.aihealthcare.ah0404.feedback

/**
 * 진동 패턴 — **의미** 단위로 정의한다.
 *
 *  기능별 타입(걷기 신호·운동 단계 등)을 여기에 넣지 않는다. 그러면 기능이 늘 때마다
 *  공통 실행기를 고쳐야 한다. 각 기능은 "무슨 상황인지"를 아래 의미 중 하나로 번역해 요청한다.
 *
 *  예) 걷기: 측정 확정 → [CONFIRMATION], 목표 달성 → [SUCCESS]
 *      스트레칭: 다음 동작 → [TRANSITION], 안전 경고 → [WARNING], 전체 완료 → [SUCCESS]
 */
enum class HapticPattern {
    /** 가벼운 반응. 짧은 단발. */
    LIGHT,

    /** 무언가 시작·인정됨. 단발이지만 [LIGHT] 보다 확실하게. */
    CONFIRMATION,

    /** 단계 전환. 짧게 두 번. */
    TRANSITION,

    /** 목표·완료 달성. 길게 두 번으로 성취감을 준다. */
    SUCCESS,

    /** 주의 필요. 강하게 세 번. */
    WARNING,
}

/**
 * ============================================================================
 *  HapticPlayer : 진동을 재생하는 공통 실행기
 * ============================================================================
 *
 *  [TtsSpeaker] 와 달리 "엔진"이 아니라 **얇은 실행기**다. `Vibrator` 는 상태가 없고
 *  초기화·해제가 필요 없어서, 무겁게 감쌀 이유가 없다. 공통화의 목적은 두 가지뿐이다.
 *
 *   1. 안드로이드 버전별 분기(`VibratorManager` / `VibrationEffect` / 레거시)를 한 곳에 모은다.
 *   2. 진동 패턴의 **의미**를 공유해, 같은 상황이 앱 전체에서 같은 느낌으로 전달되게 한다.
 *
 *  화면을 보지 않는 상황(주머니 속 걷기 등)의 신호이므로 Compose 의 `LocalHapticFeedback`
 *  대신 `Vibrator` 기반이다 — 전자는 버튼 터치 반응용이라 패턴 제어가 되지 않는다.
 *
 *  수명주기: 해제할 자원이 없어 화면마다 만들어도 무방하다. 다만 사용자 설정을 한 곳에서
 *  반영하려면 [AppFeedback] 의 공용 인스턴스를 쓰는 편이 낫다.
 * ============================================================================
 */
interface HapticPlayer {

    /** 진동 사용 여부. `false` 면 [play] 가 무시된다. 사용자 설정을 여기에 연결한다. */
    var isEnabled: Boolean

    /** [pattern] 을 재생한다. 진동 장치가 없으면 조용히 무시한다. */
    fun play(pattern: HapticPattern)

    /** 진행 중인 진동을 멈춘다. 중복 호출 안전. */
    fun cancel()
}

/** 아무 진동도 내지 않는 구현. 프리뷰·테스트 기본값. */
object NoOpHapticPlayer : HapticPlayer {
    override var isEnabled: Boolean = false
    override fun play(pattern: HapticPattern) = Unit
    override fun cancel() = Unit
}
