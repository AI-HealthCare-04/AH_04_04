package com.aihealthcare.ah0404.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetBubbleVisitPolicyTest {

    @Test
    fun kstDateBoundary_usesKstInsteadOfDeviceTimezone() {
        // Unix epoch 기준 UTC 14:59:59.999 = KST 23:59:59.999
        val beforeKstMidnight = 53_999_999L

        assertEquals(kstEpochDay(beforeKstMidnight) + 1, kstEpochDay(beforeKstMidnight + 1))
    }

    @Test
    fun threeKstCalendarDays_isRevisitThreshold() {
        val today = 20_000L

        assertEquals(2L, daysSinceLastVisit(today - 2, today))
        assertEquals(PET_REVISIT_AFTER_DAYS, daysSinceLastVisit(today - 3, today))
    }

    @Test
    fun missingFutureAndCorruptedDates_fallBackSafely() {
        assertNull(daysSinceLastVisit(null, 20_000L))
        assertNull(daysSinceLastVisit(20_001L, 20_000L))
        assertNull(daysSinceLastVisit(-1L, 20_000L))
    }
}
