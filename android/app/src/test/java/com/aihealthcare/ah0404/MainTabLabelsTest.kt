package com.aihealthcare.ah0404

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabLabelsTest {
    @Test
    fun `bottom navigation exposes the four product tabs in order`() {
        val labels = MainTab.entries.map(MainTab::label)

        assertEquals(listOf("홈", "미션", "기록", "설정"), labels)
    }
}
