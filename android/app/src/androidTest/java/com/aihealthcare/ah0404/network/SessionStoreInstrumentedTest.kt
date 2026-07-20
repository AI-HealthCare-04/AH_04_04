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
    }

    @After
    fun tearDown() {
        clearSessionPreferences()
        TokenHolder.token = ""
    }

    @Test
    fun markOnboarded_persistsCompletionAndToken() {
        TokenHolder.token = "saved-token"

        SessionStore.markOnboarded(context)
        TokenHolder.token = ""
        SessionStore.restore(context)

        assertTrue(SessionStore.isOnboarded(context))
        assertEquals("saved-token", TokenHolder.token)
    }

    @Test
    fun clearAuthentication_preservesOnboardingCompletion() {
        TokenHolder.token = "saved-token"
        SessionStore.markOnboarded(context)

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

    private fun clearSessionPreferences() {
        context.getSharedPreferences("aigo_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
