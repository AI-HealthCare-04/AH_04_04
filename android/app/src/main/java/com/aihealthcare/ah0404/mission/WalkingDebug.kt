package com.aihealthcare.ah0404.mission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * DEBUG 전용 걷기 저장 테스트 훅 — #91 1-B '전송 실패 → [다시 저장]' 재시도 버튼을 **실기기로 확인**하기 위한 것.
 *
 * 실기기에서 실제 전송 실패를 재현하기 어렵다: 오프라인으로 만들면 OFFLINE 게이트가 미션 진입 자체를
 * 먼저 막고(#188), 서버 5xx 를 손으로 만들기도 어렵다. 그래서 설정(디버그)의 토글로 **"다음 저장 1회 실패"**
 * 를 무장하면, 다음 걷기 저장이 **서버 호출 전에** 실패해 완료 화면에 '기록 저장 실패 + [다시 저장]' 이 뜬다.
 * [다시 저장](2번째 시도)은 무장이 해제돼 정상 저장된다 — 같은 created_on_device_at 로 재전송하므로 중복
 * 집계도 안 된다(#158). 릴리스에선 호출부가 `BuildConfig.DEBUG` 로 가드돼 무효, 토글도 노출되지 않는다.
 */
object WalkingDebug {
    /** 설정 화면이 토글로 켜고 끄는 '다음 저장 1회 실패' 무장 상태(Compose 관찰용). */
    var failNextSaveArmed by mutableStateOf(false)

    /** 저장 시점 호출: 무장돼 있으면 true 를 돌려주고 **즉시 해제**한다(1회성 → 재시도는 성공). */
    fun consumeFailOnce(): Boolean {
        if (failNextSaveArmed) {
            failNextSaveArmed = false
            return true
        }
        return false
    }
}
