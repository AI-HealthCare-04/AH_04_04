package com.aihealthcare.ah0404.feedback

import android.app.Application

/**
 * ============================================================================
 *  AppFeedback : 공용 피드백 컴포넌트의 **수명주기 소유자**
 * ============================================================================
 *
 *  누가 만들고 누가 해제하는지를 여기서 못 박는다. 전역 TTS 를 화면에서 종료해
 *  다른 화면의 음성까지 죽이는 사고가 흔한데, API 단계에서 막는 것이 목적이다.
 *
 *  **소유권**
 *   - 생성: [init] — `AigoApplication.onCreate()` 에서 한 번만.
 *   - 해제: [release] — Application 만. **화면·ViewModel 에서 호출 금지.**
 *   - 사용: [tts] / [haptic] 로 읽기만 한다.
 *
 *  화면이 할 수 있는 것은 [TtsSpeaker.stop] 까지다(현재 발화 중단). 엔진 자체는 살아 있다.
 *
 *  **초기화 전 접근도 안전하다.** [init] 전에는 무동작 구현이 반환돼 예외가 나지 않는다.
 *  테스트에서는 [override] 로 fake 를 주입한다.
 *
 *  DI 프레임워크를 쓰지 않는 앱이라 최소한의 홀더로 두었다. 화면에서 직접 참조하기보다는
 *  기능별 조합 클래스(예: 걷기의 WalkingFeedback)에 주입해서 쓰는 쪽이 테스트하기 쉽다.
 * ============================================================================
 */
object AppFeedback {

    @Volatile
    private var ttsSpeaker: TtsSpeaker = NoOpTtsSpeaker

    @Volatile
    private var hapticPlayer: HapticPlayer = NoOpHapticPlayer

    /** 앱 공용 음성 컴포넌트. [init] 전에는 무동작 구현. */
    val tts: TtsSpeaker get() = ttsSpeaker

    /** 앱 공용 진동 컴포넌트. [init] 전에는 무동작 구현. */
    val haptic: HapticPlayer get() = hapticPlayer

    /**
     * `AigoApplication.onCreate()` 에서 한 번 호출한다. 중복 호출은 무시한다.
     *
     *  진동은 아직 무동작 구현이다. 버전별 `Vibrator` 처리는 #148(걷기 피드백)에 이미 있어,
     *  그 코드를 `AndroidHapticPlayer` 로 옮겨오는 후속 작업에서 여기 연결한다.
     *  (`VIBRATE` 권한도 #148 이 추가하므로 이 PR 은 매니페스트를 건드리지 않는다.)
     */
    @Synchronized
    fun init(application: Application) {
        if (ttsSpeaker !== NoOpTtsSpeaker) return
        ttsSpeaker = AndroidTtsSpeaker(application)
    }

    /** 테스트에서 fake 를 주입한다. */
    @Synchronized
    fun override(tts: TtsSpeaker = NoOpTtsSpeaker, haptic: HapticPlayer = NoOpHapticPlayer) {
        ttsSpeaker = tts
        hapticPlayer = haptic
    }

    /** **Application 전용.** 화면에서 호출하면 앱 전체 음성이 죽는다. */
    @Synchronized
    fun release() {
        ttsSpeaker.release()
        hapticPlayer.cancel()
        ttsSpeaker = NoOpTtsSpeaker
        hapticPlayer = NoOpHapticPlayer
    }
}
