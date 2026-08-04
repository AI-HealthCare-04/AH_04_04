package com.aihealthcare.ah0404.home

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetBubbleVisitStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: PetBubbleVisitStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearPreferences()
        store = PetBubbleVisitStore(context)
    }

    @After
    fun tearDown() = clearPreferences()

    @Test
    fun state_survivesStoreRecreation_andIsIsolatedPerUser() {
        assertTrue(store.write(11, PetBubbleVisitState(20_000L, "general_slowly")))
        assertTrue(store.write(22, PetBubbleVisitState(20_003L, "revisit_welcome")))

        val restored = PetBubbleVisitStore(context)
        assertEquals(PetBubbleVisitState(20_000L, "general_slowly"), restored.read(11))
        assertEquals(PetBubbleVisitState(20_003L, "revisit_welcome"), restored.read(22))
        assertNull(restored.read(33))
    }

    @Test
    fun corruptedValue_isDiscardedInsteadOfCrashingHome() {
        context.getSharedPreferences("pet_bubble_visits", Context.MODE_PRIVATE)
            .edit()
            .putString("user_11_last_visit_epoch_day", "broken")
            .commit()

        assertNull(store.read(11))
        assertNull(store.read(11))
    }

    private fun clearPreferences() {
        context.getSharedPreferences("pet_bubble_visits", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
