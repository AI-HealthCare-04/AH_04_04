package com.aihealthcare.ah0404.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** AppSettings 스케일 매핑 — 글자/소리 크기 → 배율(묶음 C-2). */
class AppSettingsTest {

    @Test
    fun font_scale_mapping() {
        assertEquals(0.9f, AppSettings.fontScaleFor("small"))
        assertEquals(1.0f, AppSettings.fontScaleFor("medium"))
        assertEquals(1.2f, AppSettings.fontScaleFor("large"))
        assertEquals(1.0f, AppSettings.fontScaleFor("unknown")) // 알 수 없는 값은 보통
    }

    @Test
    fun sound_scale_mapping() {
        assertEquals(0.5f, AppSettings.soundScaleFor("small"))
        assertEquals(0.8f, AppSettings.soundScaleFor("medium"))
        assertEquals(1.0f, AppSettings.soundScaleFor("large"))
        assertEquals(0.8f, AppSettings.soundScaleFor("unknown"))
    }
}
