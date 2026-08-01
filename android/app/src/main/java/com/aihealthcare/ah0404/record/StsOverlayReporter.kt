package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.AnalyticsApi
import com.aihealthcare.ah0404.network.StsOverlayShownRequest
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 안전망 카드(§3.4) 발화율 관측 전송기(#366) — fire-and-forget(서버 라우터 주석의 요구사항).
 *
 * 전송 실패(네트워크 오류·422)는 카드 표시나 화면 동작에 영향을 주면 안 되므로 결과를 무시한다.
 * 화면 코루틴 스코프가 아니라 자체 스코프에서 보내, 카드 노출 직후 화면을 떠나도 전송이
 * 중간에 취소되지 않게 한다.
 */
internal object StsOverlayReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api: AnalyticsApi by lazy { retrofit.create(AnalyticsApi::class.java) }

    // 테스트 대체 지점: Robolectric 없이 순수 JVM 테스트에서 가짜 전송 함수로 바꿔 끼운다.
    internal var send: suspend (StsOverlayShownRequest) -> Unit = { api.recordStsOverlayShown(it) }

    fun report(tier: String, stsSec: Double?, bmi: Double?, scoreBand: String?): Job =
        scope.launch {
            runCatching {
                send(StsOverlayShownRequest(tier = tier, stsSec = stsSec, bmi = bmi, scoreBand = scoreBand))
            }
        }
}
