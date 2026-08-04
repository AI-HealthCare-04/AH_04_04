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

    // 재생 속도 기본값·옵션 계약(난이도 폐기 → 톱니 통일). 기본 1.0배속, 옵션 0.75/1.0/1.25/1.5.
    @Test
    fun playback_speed_defaults_and_options() {
        assertEquals(1.0f, AppSettings.DEFAULT_SPEED)
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f), AppSettings.SPEED_OPTIONS)
    }

    // Media3 컨트롤러가 옵션 밖 값(1.75·2.0 등)을 줘도 전역엔 확정 4옵션 중 최근접값만 저장(지영 리뷰).
    @Test
    fun playback_speed_normalizes_to_confirmed_options() {
        assertEquals(1.5f, AppSettings.normalizeSpeed(1.75f)) // 옵션 밖 → 최근접(1.5)
        assertEquals(1.5f, AppSettings.normalizeSpeed(2.0f))  // 상한 밖 → 최대 옵션
        assertEquals(0.75f, AppSettings.normalizeSpeed(0.1f)) // 하한 밖 → 최소 옵션
        assertEquals(1.0f, AppSettings.normalizeSpeed(0.9f))
        assertEquals(1.25f, AppSettings.normalizeSpeed(1.2f))
        // 확정 옵션값은 그대로 보존(복원 경계)
        AppSettings.SPEED_OPTIONS.forEach { assertEquals(it, AppSettings.normalizeSpeed(it)) }
    }
}
