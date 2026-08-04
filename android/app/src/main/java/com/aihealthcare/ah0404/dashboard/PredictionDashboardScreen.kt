package com.aihealthcare.ah0404.dashboard

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.aihealthcare.ah0404.settings.AppSettings
import kotlin.math.roundToInt

/**
 * 대시보드에 초기값으로 주입할 사용자 데이터(#193). 모두 선택 — 없으면 HTML 기본값으로 열린다.
 *  - sex: 1=남, 2=여
 *  - walkDays/muscDays: 주당 일수(0~5). 향후 챌린지 기록에서 산출해 주입 예정.
 * (5STS 는 현재 대시보드 모델의 입력이 아니라 여기에 없다 — 앱 care_stage·전후 비교용으로 별도 활용.)
 */
data class DashboardPrefill(
    val sex: Int? = null,
    val age: Int? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null,
    val waistCm: Int? = null,
    val walkDays: Int? = null,
    val muscDays: Int? = null,
)

/**
 * 근감소증 위험 예측 대시보드(#193, 심사·평가용).
 *
 * 자체 완결형 HTML(`assets/sarcopenia_predictor_screen.html`)을 WebView 로 띄운다. HTML 은 CSS·JS·
 * 모델 계수가 전부 인라인이라 **오프라인·인터넷 권한 없이** 동작한다(로컬 asset 로드).
 * [prefill] 이 있으면 페이지 로드 후 입력값을 주입해 "본인 데이터로 열리게" 한다(HTML 은 수정하지 않음).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PredictionDashboardScreen(
    modifier: Modifier = Modifier,
    prefill: DashboardPrefill? = null,
) {
    // 페이지 로드와 prefill(비동기 fetch) 도착 순서가 어느 쪽이든, 둘 다 준비되면 **정확히 한 번** 주입한다.
    //   (이후 사용자가 슬라이더를 만졌을 때 재주입으로 되돌리지 않도록 1회로 제한. 모든 경로가 update 를 거침.)
    var pageLoaded by remember { mutableStateOf(false) }
    var injected by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // 앱 글자 크기 설정(AppSettings.fontScale)을 WebView 에도 반영(#193). WebView 는 Compose
                //   fontScale 을 자동으로 안 따라오므로 textZoom(%)으로 연결 — 설정에서 키워도 대시보드가
                //   안 커지던 문제(버그처럼 보임) 해결.
                settings.textZoom = (AppSettings.fontScale * 100).roundToInt()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        pageLoaded = true // recompose → update 에서 주입 판단(최신 prefill 참조)
                    }
                }
                loadUrl("file:///android_asset/sarcopenia_predictor_screen.html")
            }
        },
        update = { webView ->
            // fontScale 변경(설정) 재적용 — AppSettings.fontScale 는 Compose 관찰 상태.
            webView.settings.textZoom = (AppSettings.fontScale * 100).roundToInt()
            if (pageLoaded && !injected && prefill != null) {
                webView.evaluateJavascript(buildPrefillJs(prefill), null)
                injected = true
            }
        },
    )
}

/**
 * 로드 완료된 대시보드 DOM 에 사용자 값을 주입하는 JS.
 * 성별 버튼 클릭이 키·몸무게를 기본값으로 덮으므로 **성별을 먼저** 처리한 뒤 나머지를 세팅하고 render() 로 재계산한다.
 */
private fun buildPrefillJs(p: DashboardPrefill): String {
    val sb = StringBuilder("(function(){try{")
    p.sex?.let { sb.append("var b=document.querySelector('#sex button[data-v=\"$it\"]');if(b)b.click();") }
    fun setVal(id: String, v: Int?) {
        v?.let { sb.append("var e=document.getElementById('$id');if(e)e.value=$it;") }
    }
    setVal("age", p.age)
    setVal("ht", p.heightCm)
    setVal("wt", p.weightKg)
    setVal("wa", p.waistCm)   // 허리둘레(선택) — null 이면 미주입 → 허리 제외형 모델 유지
    setVal("walk", p.walkDays)
    setVal("musc", p.muscDays)
    sb.append("if(typeof render==='function')render();")
    sb.append("}catch(e){}})();")
    return sb.toString()
}
