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
    @Volatile
    var token: String = ""
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
