package com.aihealthcare.ah0404

import org.junit.Assert.assertEquals
import org.junit.Test

class MainBackNavigationTest {
    @Test
    fun `홈에서 뒤로가면 종료 확인을 요청한다`() {
        assertEquals(MainBackAction.CONFIRM_EXIT, mainBackAction(MainTab.HOME))
    }

    @Test
    fun `홈이 아닌 탭에서 뒤로가면 홈으로 돌아간다`() {
        MainTab.entries
            .filterNot { it == MainTab.HOME }
            .forEach { tab ->
                assertEquals(MainBackAction.RETURN_HOME, mainBackAction(tab))
            }
    }
}
