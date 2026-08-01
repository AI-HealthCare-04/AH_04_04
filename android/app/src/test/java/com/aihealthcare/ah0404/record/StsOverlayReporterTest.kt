package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.StsOverlayShownRequest
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StsOverlayReporter(#366) 순수 JVM 테스트 — send 대체 지점을 가짜로 바꿔 Robolectric 없이 검증.
 *
 * 계약:
 *   - report() 는 화면이 계산해 둔 값을 요청 DTO 에 1:1 로 담아 보낸다.
 *   - fire-and-forget: 전송이 예외를 던져도 밖으로 새지 않는다(화면 동작에 영향 없음).
 */
class StsOverlayReporterTest {

    private val originalSend = StsOverlayReporter.send

    @After
    fun tearDown() {
        StsOverlayReporter.send = originalSend
    }

    @Test
    fun report_passesFieldsThrough() {
        val captured = mutableListOf<StsOverlayShownRequest>()
        StsOverlayReporter.send = { captured += it }

        runBlocking { StsOverlayReporter.report(tier = "strong", stsSec = 13.5, bmi = 26.1, scoreBand = "good").join() }

        assertEquals(1, captured.size)
        assertEquals("strong", captured[0].tier)
        assertEquals(13.5, captured[0].stsSec!!, 0.0)
        assertEquals(26.1, captured[0].bmi!!, 0.0)
        assertEquals("good", captured[0].scoreBand)
    }

    @Test
    fun report_nullOptionalFields() {
        val captured = mutableListOf<StsOverlayShownRequest>()
        StsOverlayReporter.send = { captured += it }

        runBlocking { StsOverlayReporter.report(tier = "basic", stsSec = 12.0, bmi = null, scoreBand = null).join() }

        assertEquals("basic", captured[0].tier)
        assertNull(captured[0].bmi)
        assertNull(captured[0].scoreBand)
    }

    @Test
    fun report_swallowsSendFailure() {
        StsOverlayReporter.send = { throw IOException("network down") }

        // join() 이 예외 없이 끝나면 실패가 밖으로 새지 않은 것.
        runBlocking { StsOverlayReporter.report(tier = "basic", stsSec = 12.0, bmi = null, scoreBand = "maintain").join() }
        assertTrue(true)
    }
}
