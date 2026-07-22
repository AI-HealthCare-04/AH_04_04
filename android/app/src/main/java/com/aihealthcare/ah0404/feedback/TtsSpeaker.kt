package com.aihealthcare.ah0404.feedback

/**
 * 발화 큐 정책 — 호출부가 고른다.
 *
 *  - [FLUSH] 진행 중인 발화를 끊고 즉시 말한다. 상태 변화 통지처럼 "지금 것이 더 중요한" 경우.
 *  - [ADD]   진행 중인 발화가 끝난 뒤 이어서 말한다. 순서가 의미를 갖는 연속 안내.
 */
enum class SpeechQueueMode {
    FLUSH,
    ADD,
}

/**
 * ============================================================================
 *  TtsSpeaker : 문장을 소리로 읽어주는 공통 컴포넌트
 * ============================================================================
 *
 *  **역할 경계** — 이 컴포넌트는 "주어진 문장을 안전하게 읽는" 것만 한다.
 *   *언제* 무슨 문장을 말할지는 각 기능(걷기·스트레칭 등)이 정한다.
 *   그래서 도메인 타입(예: 걷기 신호 enum, 운동 단계)을 인자로 받지 않고 [String] 만 받는다.
 *   기능이 늘어도 이 파일은 바뀌지 않는다.
 *
 *  **STT 와 무관** — 음성 *인식*(마이크 입력)이 아니라 음성 *합성*(스피커 출력)이다.
 *   마이크 권한(`RECORD_AUDIO`)을 쓰지 않고 서버로 아무것도 보내지 않는다.
 *
 *  **수명주기 — 앱 공용이다.**
 *   생성과 [release] 는 `Application` 이 소유한다([AppFeedback] 참고).
 *   화면·ViewModel 에서 [release] 를 부르면 **다른 화면의 음성까지 죽는다.** 절대 부르지 말 것.
 *   화면이 할 수 있는 건 [stop] 까지다(현재 발화만 중단).
 *
 *  **실패해도 앱은 정상 동작한다.** TTS 엔진 미탑재·한국어 미지원·초기화 실패 시
 *   [speak] 는 조용히 무시된다. 호출부는 성공 여부를 신경 쓰지 않아도 된다.
 * ============================================================================
 */
interface TtsSpeaker {

    /**
     * 음성 안내 사용 여부. `false` 면 [speak] 가 무시된다.
     * 사용자 설정(음성 안내 ON/OFF)을 여기에 연결한다.
     */
    var isEnabled: Boolean

    /** 말하기 속도. 1.0 이 기본, 낮을수록 느리다. 시니어 대상이라 느린 값이 유리할 수 있다. */
    fun setSpeechRate(rate: Float)

    /** 발화 음량(0.0~1.0). 사용자 설정 `sound_size` 를 여기에 연결한다. */
    fun setVolume(volume: Float)

    /**
     * [text] 를 읽는다. 초기화가 끝나기 전에 호출되면 **버리지 않고 보관했다가** 준비된 뒤 재생한다.
     * 엔진을 쓸 수 없는 상태면 조용히 무시한다(예외를 던지지 않는다).
     */
    fun speak(text: String, mode: SpeechQueueMode = SpeechQueueMode.FLUSH)

    /** 현재 발화와 대기 중인 발화를 중단한다. 화면 이탈 시 호출해도 안전하다. 중복 호출 안전. */
    fun stop()

    /**
     * 엔진 자원을 해제한다. **`Application` 만 호출한다.**
     * 중복 호출해도 안전하며, 이후 [speak] 는 무시된다.
     */
    fun release()
}

/**
 * 아무 소리도 내지 않는 구현.
 *
 *  - Compose 프리뷰·단위 테스트 기본값
 *  - [AppFeedback] 초기화 전 접근에 대한 안전한 대체값
 */
object NoOpTtsSpeaker : TtsSpeaker {
    override var isEnabled: Boolean = false
    override fun setSpeechRate(rate: Float) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun speak(text: String, mode: SpeechQueueMode) = Unit
    override fun stop() = Unit
    override fun release() = Unit
}
