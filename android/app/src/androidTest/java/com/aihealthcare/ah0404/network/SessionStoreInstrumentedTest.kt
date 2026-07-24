package com.aihealthcare.ah0404.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearSessionPreferences()
        TokenHolder.token = ""
        SessionStore.resetSession(context)
    }

    @After
    fun tearDown() {
        clearSessionPreferences()
        TokenHolder.token = ""
        SessionStore.resetSession(context)
    }

    @Test
    fun markOnboarded_social_persistsCompletionAndToken() {
        TokenHolder.token = "saved-token"

        SessionStore.markOnboarded(context, isGuest = false)
        TokenHolder.token = ""
        SessionStore.restore(context)

        assertTrue(SessionStore.isOnboarded(context))
        assertTrue(SessionStore.sessionOnboarded)
        assertEquals("saved-token", TokenHolder.token)
    }

    /** #153: 게스트 완주는 이번 세션만 MAIN(메모리 게이트) 이고 디스크엔 토큰·플래그를 남기지 않는다. */
    @Test
    fun markOnboarded_guest_isMemoryOnly_notPersisted() {
        TokenHolder.token = "guest-token"

        SessionStore.markOnboarded(context, isGuest = true)

        // 이번 세션은 온보딩 완료로 취급(홈 진입 가능)
        assertTrue(SessionStore.sessionOnboarded)
        // 그러나 디스크엔 아무것도 남지 않아 재실행 시 시작화면으로 복귀
        assertFalse(SessionStore.isOnboarded(context))
        TokenHolder.token = ""
        SessionStore.restore(context)
        assertFalse(SessionStore.sessionOnboarded)
        assertEquals("", TokenHolder.token)
    }

    /** #153: 완료된 소셜 로그인은 즉시 영속화되고 재실행에도 유지된다. */
    @Test
    fun applyLogin_completedSocial_persists() {
        SessionStore.applyLogin(
            context,
            AuthSession(accessToken = "social-token", isGuest = false, onboardingCompleted = true, userId = 7),
        )
        assertTrue(SessionStore.sessionOnboarded)

        TokenHolder.token = ""
        SessionStore.restore(context)
        assertTrue(SessionStore.isOnboarded(context))
        assertEquals("social-token", TokenHolder.token)
    }

    /** #153 증상 ①: 미완료 소셜(약관 전) 토큰은 메모리에만 두고 디스크에 쓰지 않는다. */
    @Test
    fun applyLogin_incompleteSocial_isMemoryOnly() {
        SessionStore.applyLogin(
            context,
            AuthSession(accessToken = "incomplete-token", isGuest = false, onboardingCompleted = false),
        )
        // 이번 세션은 토큰으로 온보딩을 이어감(메모리)
        assertEquals("incomplete-token", TokenHolder.token)
        assertFalse(SessionStore.sessionOnboarded)

        // 재실행 시 잔존 없음
        TokenHolder.token = ""
        SessionStore.restore(context)
        assertFalse(SessionStore.isOnboarded(context))
        assertEquals("", TokenHolder.token)
    }

    /** #153 증상 ②: 게스트 로그인 토큰은 어떤 경로로도 디스크에 남지 않는다. */
    @Test
    fun applyLogin_guest_isMemoryOnly() {
        SessionStore.applyLogin(
            context,
            AuthSession(accessToken = "guest-token", isGuest = true, onboardingCompleted = false),
        )
        assertEquals("guest-token", TokenHolder.token)
        assertFalse(SessionStore.sessionOnboarded)

        TokenHolder.token = ""
        SessionStore.restore(context)
        assertFalse(SessionStore.isOnboarded(context))
        assertEquals("", TokenHolder.token)
    }

    /** #153 계정 전환: 완료 소셜 뒤 게스트로 로그인하면 이전 계정 잔재(토큰·플래그)가 지워진다. */
    @Test
    fun applyLogin_guestAfterCompletedSocial_clearsPreviousPersistence() {
        SessionStore.applyLogin(
            context,
            AuthSession("social-token", isGuest = false, onboardingCompleted = true),
        )
        assertTrue(SessionStore.isOnboarded(context))

        SessionStore.applyLogin(
            context,
            AuthSession("guest-token", isGuest = true, onboardingCompleted = false),
        )
        assertFalse(SessionStore.sessionOnboarded)

        SessionStore.restore(context)
        assertFalse(SessionStore.isOnboarded(context))
        assertEquals("", TokenHolder.token)
    }

    @Test
    fun clearAuthentication_preservesOnboardingCompletion() {
        TokenHolder.token = "saved-token"
        SessionStore.markOnboarded(context, isGuest = false)

        SessionStore.clearAuthentication(context)
        SessionStore.restore(context)

        assertTrue(SessionStore.isOnboarded(context))
        assertEquals("", TokenHolder.token)
    }

    @Test
    fun demoPath_withoutMarkOnboarded_isNotPersistent() {
        // 둘러보기 콜백은 SessionStore를 호출하지 않는다.
        assertFalse(SessionStore.isOnboarded(context))

        SessionStore.restore(context)

        assertFalse(SessionStore.isOnboarded(context))
        assertEquals("", TokenHolder.token)
    }

    @Test
    fun resetSession_clearsTokenAndOnboarding() {
        // DEBUG 탈출구(#119): 세션 전체 초기화 → 다음 라우팅이 온보딩(시작화면)으로 복귀하도록
        //   토큰과 온보딩 완료 플래그가 모두 지워져야 한다(clearAuthentication과 달리 완료 플래그도 제거).
        TokenHolder.token = "saved-token"
        SessionStore.markOnboarded(context, isGuest = false)
        assertTrue(SessionStore.isOnboarded(context))

        SessionStore.resetSession(context)
        SessionStore.restore(context)

        assertFalse(SessionStore.isOnboarded(context))
        assertFalse(SessionStore.sessionOnboarded)
        assertEquals("", TokenHolder.token)
    }

    private fun clearSessionPreferences() {
        context.getSharedPreferences("aigo_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
