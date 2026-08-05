package com.aihealthcare.ah0404.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2xx 인데 우리 JSON 이 아닌 응답을 걸러내는 판정(PR #433 리뷰 후속).
 *
 *  종전에는 200 이면 무조건 성공으로 보고했다. 캡티브 포털·중간 프록시가 로그인 페이지(HTML)를
 *  200 으로 돌려주면 '성공' 보고가 나가고, 뒤이어 파싱이 터져도 코디네이터에는 아무것도 안 간다.
 *  라우팅이 안 바뀌니 화면이 실패에 고착된다 — 홈 제보에서 401·5xx·네트워크를 소거하고 남은
 *  유력 후보가 이 경로다. #185 에서 VALIDATED 하드 게이트를 뗀 뒤로 이 판정 책임이 여기로 왔다.
 */
class JsonResponseGuardTest {

    private fun response(contentType: String?): Response {
        val body = (contentType?.let { "x".toResponseBody(it.toMediaType()) })
            ?: "x".toResponseBody(null)
        return Response.Builder()
            .request(Request.Builder().url("https://aigo-health.duckdns.org/api/v1/home").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    @Test
    fun 우리_서버의_json_은_통과한다() {
        assertTrue(response("application/json; charset=UTF-8").looksLikeJson())
        assertTrue(response("application/json").looksLikeJson())
        // 벤더 타입도 JSON 이다(+json 접미사).
        assertTrue(response("application/vnd.api+json").looksLikeJson())
    }

    @Test
    fun 가로채기_응답은_json_이_아니다() {
        // 캡티브 포털·프록시가 200 으로 돌려주는 전형적인 형태.
        assertFalse(response("text/html; charset=UTF-8").looksLikeJson())
        assertFalse(response("text/plain").looksLikeJson())
    }

    @Test
    fun 타입이_없으면_통과시킨다() {
        // 204 No Content 처럼 본문이 없는 정상 응답을 가로채기로 오판하면 안 된다.
        //   판정은 "타입이 있는데 JSON 이 아니다"일 때만 한다.
        assertTrue(response(null).looksLikeJson())
    }
}
