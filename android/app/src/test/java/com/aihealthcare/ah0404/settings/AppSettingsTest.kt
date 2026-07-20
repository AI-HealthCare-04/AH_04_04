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

    /** 배경음악 게이트(C-3): 끄기 → 무음, 켜기 → 기준볼륨 × 소리배율. */
    @Test
    fun bgm_media_volume_gate() {
        // 끄기: 소리 배율과 무관하게 무음
        assertEquals(0f, AppSettings.mediaVolume(0.4f, musicEnabled = false, soundScale = 1.0f))
        assertEquals(0f, AppSettings.mediaVolume(0.4f, musicEnabled = false, soundScale = 0.5f))
        // 켜기: 기준볼륨 × 소리배율
        assertEquals(0.4f, AppSettings.mediaVolume(0.4f, musicEnabled = true, soundScale = 1.0f))
        assertEquals(0.32f, AppSettings.mediaVolume(0.4f, musicEnabled = true, soundScale = 0.8f), 1e-6f)
        assertEquals(0.2f, AppSettings.mediaVolume(0.4f, musicEnabled = true, soundScale = 0.5f), 1e-6f)
    }
}
