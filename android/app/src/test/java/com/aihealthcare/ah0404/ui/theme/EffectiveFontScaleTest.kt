package com.aihealthcare.ah0404.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** 유효 글자 배율 = OS 배율 × 앱 배율, 단 상한으로 과증폭 방지(리뷰 #86-2). */
class EffectiveFontScaleTest {

    @Test fun normal_os_app_large_multiplies() =
        assertEquals(1.2f, effectiveFontScale(os = 1.0f, app = 1.2f)) // 1.0×1.2

    @Test fun normal_os_app_small() =
        assertEquals(0.9f, effectiveFontScale(os = 1.0f, app = 0.9f))

    @Test fun big_os_and_app_large_capped_to_ceiling() =
        // OS 1.5 × 앱 1.2 = 1.8 → 상한 max(1.5,1.5)=1.5 로 캡(소형 화면 안전)
        assertEquals(1.5f, effectiveFontScale(os = 1.5f, app = 1.2f))

    @Test fun very_big_os_preserved_no_app_amplification() =
        // OS 2.0 은 상한보다 커도 그대로 유지(접근성 보존), 앱 증폭만 차단
        assertEquals(2.0f, effectiveFontScale(os = 2.0f, app = 1.2f))
}
