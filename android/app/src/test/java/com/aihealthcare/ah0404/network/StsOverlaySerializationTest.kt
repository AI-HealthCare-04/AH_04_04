package com.aihealthcare.ah0404.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StsOverlayShownRequest(#366)가 "실제로 전송되는 JSON"을 검증한다(백엔드/에뮬레이터 불필요).
 *
 * NetworkClient 의 Json 과 동일 설정(ignoreUnknownKeys=true, encodeDefaults 기본=false)을 재현.
 * 가장 중요한 계약:
 *   - 서버 DTO(app/dtos/analytics.py)와 필드명이 snake_case 로 일치해야 한다.
 *   - null 인 선택 필드는 전송에서 빠져야 한다(서버 기본 null 로 저장).
 */
class StsOverlaySerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun stsOverlayShown_serialization() {
        val body = StsOverlayShownRequest(tier = "strong", stsSec = 13.5, bmi = 26.1, scoreBand = "good")
        val s = json.encodeToString(body)
        println("POST /events/sts-overlay-shown  →  $s")
        assertTrue(s.contains("\"tier\":\"strong\""))
        assertTrue(s.contains("\"sts_sec\":13.5"))
        assertTrue(s.contains("\"bmi\":26.1"))
        assertTrue(s.contains("\"score_band\":\"good\""))
    }

    @Test
    fun stsOverlayShown_nullFieldsOmitted() {
        val body = StsOverlayShownRequest(tier = "basic", stsSec = 12.0)
        val s = json.encodeToString(body)
        println("POST /events/sts-overlay-shown  →  $s")
        assertTrue(s.contains("\"tier\":\"basic\""))
        assertFalse(s.contains("bmi"))
        assertFalse(s.contains("score_band"))
    }

    @Test
    fun stsOverlayShown_responseParses() {
        val parsed = json.decodeFromString<StsOverlayShownResponse>("""{"recorded": true}""")
        assertTrue(parsed.recorded)
    }
}
