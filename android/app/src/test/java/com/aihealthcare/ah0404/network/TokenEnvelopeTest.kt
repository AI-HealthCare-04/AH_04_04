package com.aihealthcare.ah0404.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 암호문 봉투 포맷(#358)의 순수 함수 검증. Keystore 없이 JVM 에서 포맷 계약을 고정한다:
 * 버전 프리픽스가 평문(구버전 저장분)과 봉투를 구분하는 유일한 판별자이므로,
 * 여기가 무너지면 마이그레이션·복호화 폴백이 모두 오동작한다.
 */
class TokenEnvelopeTest {

    @Test
    fun `build 와 parse 는 왕복한다`() {
        val stored = TokenEnvelope.build("aXY=", "Y2lwaGVy")
        assertTrue(TokenEnvelope.isEnvelope(stored))
        assertEquals("aXY=" to "Y2lwaGVy", TokenEnvelope.parse(stored))
    }

    @Test
    fun `평문 토큰은 봉투로 판별되지 않는다`() {
        // JWT 평문(#358 이전 저장분)은 마이그레이션 대상으로 구분되어야 한다.
        val legacyJwt = "eyJhbGciOiJIUzI1NiJ9.payload.signature"
        assertFalse(TokenEnvelope.isEnvelope(legacyJwt))
        assertNull(TokenEnvelope.parse(legacyJwt))
    }

    @Test
    fun `형식이 깨진 봉투는 null 로 거부한다`() {
        assertNull(TokenEnvelope.parse("v1:"))
        assertNull(TokenEnvelope.parse("v1:onlyone"))
        assertNull(TokenEnvelope.parse("v1::"))
        assertNull(TokenEnvelope.parse("v1:iv:"))
        assertNull(TokenEnvelope.parse("v1::cipher"))
    }

    @Test
    fun `암호문 안의 콜론은 두 조각 분리를 깨지 않는다`() {
        // limit=2 분리 계약: 두 번째 조각에 콜론이 있어도 그대로 보존된다(base64 는 콜론이 없지만 방어).
        assertEquals("iv" to "a:b", TokenEnvelope.parse("v1:iv:a:b"))
    }
}
