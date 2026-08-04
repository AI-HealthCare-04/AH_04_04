package com.aihealthcare.ah0404.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBubbleVisitPolicyTest {

    @Test
    fun kstDateBoundary_usesKstInsteadOfDeviceTimezone() {
        // Unix epoch 기준 UTC 14:59:59.999 = KST 23:59:59.999
        val beforeKstMidnight = 53_999_999L

        assertEquals(kstEpochDay(beforeKstMidnight) + 1, kstEpochDay(beforeKstMidnight + 1))
        assertEquals("1970-01-01", kstDateString(beforeKstMidnight))
        assertEquals("1970-01-02", kstDateString(beforeKstMidnight + 1))
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

    @Test
    fun sameKstDay_mergesMessageHistoryAndPreservesStreakKey() {
        val previous = PetBubbleVisitState(
            lastVisitEpochDay = 20_000L,
            lastMessageId = "message_a",
            lastStreakKey = "streak:2026-07-27:4",
            shownMessageIds = setOf("message_a"),
        )

        val merged = mergePetBubbleVisitState(
            previous,
            PetBubbleVisitState(
                lastVisitEpochDay = 20_000L,
                lastMessageId = "message_b",
                shownMessageIds = setOf("message_b"),
            ),
        )

        assertEquals(setOf("message_a", "message_b"), merged.shownMessageIds)
        assertEquals("message_b", merged.lastMessageId)
        assertEquals("streak:2026-07-27:4", merged.lastStreakKey)
    }

    @Test
    fun newKstDay_resetsGeneralMessageHistoryButKeepsStreakDeduplication() {
        val previous = PetBubbleVisitState(
            lastVisitEpochDay = 20_000L,
            lastMessageId = "message_a",
            lastStreakKey = "streak:2026-07-27:4",
            shownMessageIds = setOf("message_a"),
        )

        val nextDay = mergePetBubbleVisitState(
            previous,
            PetBubbleVisitState(
                lastVisitEpochDay = 20_001L,
                lastMessageId = "message_b",
                shownMessageIds = setOf("message_b"),
            ),
        )

        assertEquals(setOf("message_b"), nextDay.shownMessageIds)
        assertEquals("streak:2026-07-27:4", nextDay.lastStreakKey)
        assertEquals(setOf("message_b"), shownMessageIdsForToday(nextDay, 20_001L))
        assertTrue(shownMessageIdsForToday(previous, 20_001L).isEmpty())
    }
}
