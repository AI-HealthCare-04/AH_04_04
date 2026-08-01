package com.aihealthcare.ah0404.network

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * SessionStore 의 토큰 암호화 저장(#358, 심사 5-4) 계약 검증.
 *
 * Keystore 는 JVM 에 없으므로 가역 fake cipher 를 주입해 **저장 계층 계약**만 고정한다:
 *  ① 디스크에는 평문이 아니라 봉투(v1:...)만 남는다
 *  ② 봉투는 restore 에서 복호화되어 세션이 복원된다
 *  ③ 평문(#358 이전 저장분)은 폐기되어 재로그인으로 유도된다(팀 합의 마이그레이션)
 *  ④ 복호화·암호화 실패는 크래시 없이 로그아웃 폴백/저장 생략으로 처리된다(이슈 체크리스트)
 * (실제 Keystore 경로는 머지 전 실기기 QA 로 확인 — 앱 재시작 복원·로그아웃 → 재로그인·게스트.)
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreTokenEncryptionTest {

    /**
     * 가역 fake — 봉투 포맷은 실제 TokenEnvelope 를 그대로 쓰되, 본문은 문자열 반전 + 마커.
     * 평문이 부분 문자열로도 남지 않아야 '디스크에 평문 없음' 단언이 유효하다.
     */
    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String): String = TokenEnvelope.build("iv", "fk." + plain.reversed())

        override fun decrypt(stored: String): String? {
            val (_, cipher) = TokenEnvelope.parse(stored) ?: return null
            if (!cipher.startsWith("fk.")) return null
            return cipher.removePrefix("fk.").reversed()
        }
    }

    private class BrokenCipher : TokenCipher {
        override fun encrypt(plain: String): String = throw IllegalStateException("keystore unavailable")

        override fun decrypt(stored: String): String? = null
    }

    private lateinit var context: Context
    private val prefs
        get() = context.getSharedPreferences("aigo_session", Context.MODE_PRIVATE)

    private fun socialSession(token: String) =
        AuthSession(accessToken = token, isGuest = false, onboardingCompleted = true, userId = 7)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SessionStore.resetSession(context)
        SessionStore.tokenCipher = FakeCipher()
    }

    @After
    fun tearDown() {
        SessionStore.resetSession(context)
        SessionStore.tokenCipher = KeystoreTokenCipher
    }

    @Test
    fun `완료된 소셜 로그인은 디스크에 암호문 봉투만 남긴다`() {
        SessionStore.applyLogin(context, socialSession("jwt-abc"))

        val stored = prefs.getString("access_token", null)
        assertTrue("봉투 형식이어야 한다: $stored", stored != null && TokenEnvelope.isEnvelope(stored))
        assertFalse("평문 토큰이 디스크에 남으면 안 된다", stored!!.contains("jwt-abc"))
    }

    @Test
    fun `봉투로 저장된 세션은 restore 에서 복호화되어 복원된다`() {
        SessionStore.applyLogin(context, socialSession("jwt-roundtrip"))
        TokenHolder.token = "" // 앱 재시작 시뮬레이션

        SessionStore.restore(context)

        assertEquals("jwt-roundtrip", TokenHolder.token)
        assertTrue(SessionStore.sessionOnboarded)
        assertEquals(7, SessionStore.persistentUserId)
    }

    @Test
    fun `기존 평문 토큰은 폐기되고 재로그인 상태로 복원된다`() {
        // #358 이전 설치 시뮬레이션: 평문 JWT + 완료 플래그가 디스크에 있다.
        prefs.edit()
            .putString("access_token", "eyJhbGciOiJIUzI1NiJ9.legacy.sig")
            .putBoolean("onboarding_completed", true)
            .putInt("user_id", 7)
            .apply()

        SessionStore.restore(context)

        assertEquals("", TokenHolder.token) // 로그인 필요 상태
        assertNull(SessionStore.persistentUserId)
        assertNull(prefs.getString("access_token", null)) // 평문 잔재 제거
        assertFalse(prefs.contains("user_id"))
        // 완료 플래그는 로그아웃(#154)과 같은 의미로 보존 — 재로그인 시 온보딩을 반복하지 않는다.
        assertTrue(prefs.getBoolean("onboarding_completed", false))
    }

    @Test
    fun `복호화 실패는 크래시 없이 로그아웃 폴백한다`() {
        // 기기 복원·키 손상 시뮬레이션: 봉투 형식이지만 fake 접두어가 아니라 복호화가 실패한다.
        prefs.edit()
            .putString("access_token", TokenEnvelope.build("iv", "corrupted"))
            .putBoolean("onboarding_completed", true)
            .apply()

        SessionStore.restore(context)

        assertEquals("", TokenHolder.token)
        assertNull(SessionStore.persistentUserId)
        assertNull(prefs.getString("access_token", null)) // 복호화 불가 잔재 제거
    }

    @Test
    fun `암호화 실패 시 저장만 생략하고 이번 세션은 동작한다`() {
        SessionStore.tokenCipher = BrokenCipher()

        SessionStore.applyLogin(context, socialSession("jwt-mem-only"))

        assertEquals("jwt-mem-only", TokenHolder.token) // 메모리 세션은 유지(크래시 금지)
        assertTrue(SessionStore.sessionOnboarded)
        assertNull(prefs.getString("access_token", null)) // 평문 저장으로 후퇴하지 않는다
        assertFalse(prefs.contains("user_id"))
    }

    @Test
    fun `게스트 로그인은 여전히 아무것도 저장하지 않는다`() {
        SessionStore.applyLogin(
            context,
            AuthSession(accessToken = "guest-token", isGuest = true, onboardingCompleted = false),
        )

        assertNull(prefs.getString("access_token", null))
        assertEquals("guest-token", TokenHolder.token)
    }
}
