package com.aihealthcare.ah0404.dashboard

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

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
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        prefill?.let { view.evaluateJavascript(buildPrefillJs(it), null) }
                    }
                }
                loadUrl("file:///android_asset/sarcopenia_predictor_screen.html")
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
    setVal("walk", p.walkDays)
    setVal("musc", p.muscDays)
    sb.append("if(typeof render==='function')render();")
    sb.append("}catch(e){}})();")
    return sb.toString()
}
