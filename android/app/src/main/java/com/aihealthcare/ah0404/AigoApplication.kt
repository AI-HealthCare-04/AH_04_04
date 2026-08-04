package com.aihealthcare.ah0404

import android.app.Application
import com.aihealthcare.ah0404.feedback.AppFeedback
import com.kakao.sdk.common.KakaoSdk

class AigoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        }
        // 음성·진동 공용 컴포넌트는 앱이 소유한다(화면에서 release 금지 — AppFeedback 문서 참고).
        //   TTS 초기화가 비동기라 여기서 미리 시작해두면, 화면 진입 직후의 안내도 놓치지 않는다.
        AppFeedback.init(this)
    }
}
