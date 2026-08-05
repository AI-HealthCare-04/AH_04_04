package com.aihealthcare.ah0404.network

import com.aihealthcare.ah0404.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.IOException

// API 주소는 debug/release 빌드 타입별 BuildConfig 값으로 주입한다.
object TokenHolder {
    /**
     * 현재 세션 토큰. **비어 있지 않은 값이 들어오면 인증 실패 래치를 함께 내린다.**
     *
     * [AuthFailureCoordinator.reportUnauthorized] 는 single-flight 라 한 번 서면
     * [AuthFailureCoordinator.onAuthenticated] 로만 내려간다. 그런데 그 호출이 `SessionStore`
     * 세 곳(applyLogin·markOnboarded·clearAuthentication)에만 있어서, **토큰을 여기에 직접 넣는
     * 경로들이 래치를 그대로 두고 지나갔다.**
     *
     *   · 체험하기 게스트 로그인(`OnboardingViewModel.start`)
     *   · 앱 시작 시 세션 복원(`SessionStore.restore`)
     *   · 걷기 헤드리스 데모(`WalkingFlowUseCase`)
     *
     * 래치가 선 채로 남으면 **이후의 모든 401 이 조용히 버려진다** — `reportUnauthorized()` 가
     * false 를 돌려주고 `failure` 가 갱신되지 않아 라우팅이 움직이지 않는다. 그러면 화면은 실패에
     * 고착되고, 홈처럼 전체 화면 오류를 쓰는 곳만 눈에 띄게 막힌다(다른 탭은 빈 상태로 열린다).
     *
     * 경로마다 챙기는 대신 **토큰이 갱신되는 지점 한 곳**에서 내린다. 새 토큰을 받았다는 것은
     * 인증이 새로 성립했다는 뜻이므로, 이전 인증 실패 상태를 유지할 이유가 없다. 앞으로 토큰
     * 발급 경로가 늘어도 자동으로 따라온다.
     */
    @Volatile
    var token: String = ""
        set(value) {
            field = value
            // 빈 값은 '토큰 제거'라 인증 성립이 아니다. 로그아웃 경로는 SessionStore 가 따로 정리한다.
            if (value.isNotBlank()) AuthFailureCoordinator.onAuthenticated()
        }
}

private val json = Json { ignoreUnknownKeys = true }

private val okHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val original = chain.request()
        val request = if (TokenHolder.token.isNotEmpty()) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer ${TokenHolder.token}")
                .build()
        } else {
            original
        }
        chain.proceed(request)
    }
    .addInterceptor { chain ->
        try {
            val response = chain.proceed(chain.request())
            when {
                // 401 은 **로그인 계열이 아닌 모든 요청**에서 세션 이상으로 본다.
                //   전에는 `Authorization 헤더가 붙었을 때`만 봤는데, 요청 인터셉터는 토큰이 비면
                //   헤더를 아예 안 붙인다. 그래서 '토큰이 없는데 인증 API 를 호출한' 상태에서는 401 이
                //   와도 아무도 보고하지 않았고, 라우팅이 그대로 남아 화면이 실패에 고착됐다
                //   (홈은 error 로 굳고 '다시 시도' 가 같은 요청을 반복할 뿐이라 탈출 경로가 없다).
                //   헤더 유무 대신 **경로**로 가른다 — 로그인·게스트는 자격 증명 오류로 401 을 주므로
                //   세션 만료로 취급하면 안 되고, 그 외는 헤더가 없다는 사실 자체가 이미 세션 이상이다.
                response.code == 401 && !chain.request().isCredentialEndpoint() -> {
                    AuthFailureCoordinator.reportUnauthorized()
                }
                response.code >= 500 -> AuthFailureCoordinator.reportServerFailure()
                // 2xx 인데 JSON 이 아니면 우리 서버가 준 응답이 아니다 — 캡티브 포털·중간 프록시가
                //   가로채 로그인 페이지(HTML)를 200 으로 돌려주는 경우가 대표적이다. 종전에는 이걸
                //   '성공'으로 보고했고, 뒤이어 파싱이 터져도 코디네이터에는 아무것도 안 갔다.
                //   그러면 라우팅이 안 바뀌어 화면이 실패에 고착된다(홈 제보의 유력 후보).
                //   연결 문제로 보고해 OFFLINE 으로 보낸다 — 거기엔 재시도 경로가 있다.
                //   #185 에서 NET_CAPABILITY_VALIDATED 하드 게이트를 뗀 뒤로, '연결은 됐지만 실제
                //   인터넷은 아닌' 상태를 걸러낼 책임이 이 요청 계층으로 넘어와 있다.
                response.isSuccessful && !response.looksLikeJson() ->
                    AuthFailureCoordinator.reportNetworkFailure()
                response.isSuccessful -> AuthFailureCoordinator.onRequestSucceeded()
            }
            response
        } catch (exception: IOException) {
            AuthFailureCoordinator.reportNetworkFailure()
            throw exception
        }
    }
    .addInterceptor(
        // 민감정보 유출 방지(리뷰 #58): 온보딩이 생년월일·성별·키·몸무게·질환 등을 이 클라이언트로
        // 전송하므로, release 빌드에서는 로깅 OFF, 디버그에서만 BODY. Authorization 토큰은 디버그
        // 로그에서도 마스킹한다(본문은 디버그 로컬 기기에서만 노출).
        HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = if (BuildConfig.DEBUG) {
                // OAuth ID token과 건강정보 요청 본문을 로그에 남기지 않는다.
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    )
    .build()

/**
 * 자격 증명으로 인증을 **얻는** 요청인가. 게스트 발급과 소셜 로그인 두 가지뿐이다.
 *
 * 이 경로들의 401 은 "소셜 토큰이 유효하지 않다"는 뜻이지 세션 만료가 아니다. 세션 이상으로
 * 보고하면 로그인 실패가 곧바로 로그인 화면 재진입으로 이어져 원인을 알리지 못한다.
 * 그 외 API 의 401 은 헤더 유무와 무관하게 세션 이상으로 본다.
 *
 * ⚠️ `/auth/` 접두사로 뭉뚱그리지 않는다(리뷰 P2). `POST /auth/logout` 은 **인증이 필요한**
 *   요청이라 그 401 은 세션 이상이 맞다. 공개 엔드포인트만 허용 목록으로 못박는다.
 */
/**
 * 본문이 우리 API 의 JSON 으로 보이는가. Content-Type 만 본다(본문을 읽으면 소비돼 버린다).
 *
 * 204 No Content 처럼 본문이 없는 정상 응답은 Content-Type 이 없을 수 있으므로 통과시킨다 —
 * 가로채기 판정은 "타입이 있는데 JSON 이 아니다"일 때만 한다.
 */
internal fun Response.looksLikeJson(): Boolean {
    val subtype = body?.contentType()?.subtype?.lowercase() ?: return true
    return subtype == "json" || subtype.endsWith("+json")
}

internal fun Request.isCredentialEndpoint(): Boolean {
    val path = url.encodedPath.substringAfter("/api/v1", "")
    return path == "/auth/guest" || path.startsWith("/auth/login/")
}

val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .client(okHttpClient)
    .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
    .build()
