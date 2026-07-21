package com.aihealthcare.ah0404

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainNavigationTest {
    @Test
    fun `bottom navigation exposes the four product tabs in order`() {
        val labels = MainTab.entries.map(MainTab::label)

        assertEquals(listOf("홈", "미션", "기록", "설정"), labels)
        assertFalse(labels.any { it in setOf("음성", "센서", "흔들기") })
    }
}
