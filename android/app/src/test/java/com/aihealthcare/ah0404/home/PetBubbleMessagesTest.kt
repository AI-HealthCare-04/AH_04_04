package com.aihealthcare.ah0404.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBubbleMessagesTest {

    @Test
    fun completed_mission_uses_achievement_instead_of_available_mission_prompt() {
        val message = selectPetBubbleMessage(
            context(
                completedToday = 2,
                availableMeal = 1,
                availableExercise = 1,
                availableWalking = 1,
            ),
            choosing(0, 0),
        )

        assertEquals("completion_count", message.id)
        assertEquals("오늘 미션을 2개 해냈어요. 잘했어요!", message.text)
    }

    @Test
    fun overlapping_achievements_can_select_different_situations() {
        val message = selectPetBubbleMessage(
            context(completedToday = 1, todayWalkingMin = 12.4, todayWalkingSteps = 1_234),
            choosing(2, 0),
        )

        assertEquals("walking_steps", message.id)
        assertTrue(message.text.contains("1,234보"))
    }

    @Test
    fun daytime_meal_mission_uses_one_of_the_agreed_general_meal_prompts() {
        val message = selectPetBubbleMessage(
            context(availableMeal = 1, hourOfDay = 12),
            choosing(0, 1),
        )

        assertEquals("meal_hearty", message.id)
        assertEquals("맛있고 든든하게 챙겨 드세요.", message.text)
    }

    @Test
    fun late_night_suppresses_meal_exercise_and_walking_start_prompts() {
        val promptIds = setOf(
            "meal_check", "meal_hearty", "meal_table",
            "exercise_together", "exercise_light", "exercise_slowly",
            "walking_out", "walking_beside", "walking_comfortable", "walking_pocket", "walking_little",
        )

        listOf(22, 23, 0, 5).forEach { hour ->
            repeat(10) { firstIndex ->
                val message = selectPetBubbleMessage(
                    context(
                        availableMeal = 1,
                        availableExercise = 1,
                        availableWalking = 1,
                        hourOfDay = hour,
                    ),
                    choosing(firstIndex, firstIndex),
                )
                assertFalse("${hour}시에는 시작 제안을 표시하면 안 됩니다.", message.id in promptIds)
            }
        }
    }

    @Test
    fun achievements_are_still_allowed_at_late_night() {
        val message = selectPetBubbleMessage(
            context(completedToday = 1, hourOfDay = 23),
            choosing(0, 0),
        )

        assertEquals("completion_count", message.id)
    }

    @Test
    fun game_availability_does_not_create_a_game_or_cognitive_prompt() {
        val message = selectPetBubbleMessage(
            context(hourOfDay = 13),
            choosing(0, 0),
        )

        assertTrue(message.id.startsWith("afternoon_"))
    }

    @Test
    fun stale_home_data_does_not_make_unverified_activity_claims() {
        val message = selectPetBubbleMessage(
            context(
                completedToday = 3,
                availableMeal = 1,
                todayWalkingSteps = 9_999,
                hasFreshHomeData = false,
                hourOfDay = 9,
            ),
            choosing(0, 0),
        )

        assertEquals("morning_hello", message.id)
    }

    @Test
    fun mission_prompts_are_available_from_six_through_twenty_one() {
        listOf(6, 21).forEach { hour ->
            val message = selectPetBubbleMessage(
                context(availableWalking = 1, hourOfDay = hour),
                choosing(0, 0),
            )
            assertEquals("walking_out", message.id)
        }
    }

    @Test
    fun returningAfterThreeKstCalendarDays_usesSupportiveRevisitMessage() {
        val message = selectPetBubbleMessage(
            context(daysSinceLastVisit = 3),
            choosing(0, 0),
        )

        assertEquals("revisit_welcome", message.id)
        assertEquals("오랜만이에요! 다시 만나서 반가워요.", message.text)
    }

    @Test
    fun returningBeforeThreeDays_keepsNormalHomeMessage() {
        val message = selectPetBubbleMessage(
            context(daysSinceLastVisit = 2, hourOfDay = 12),
            choosing(0, 0),
        )

        assertEquals("afternoon_easy", message.id)
    }

    @Test
    fun previousMessage_isExcludedAcrossAppRelaunches() {
        val message = selectPetBubbleMessage(
            context(
                daysSinceLastVisit = 0,
                excludedMessageId = "afternoon_easy",
                hourOfDay = 12,
            ),
            choosing(0, 0),
        )

        assertEquals("afternoon_hello", message.id)
    }

    @Test
    fun previousRevisitMessage_isNotRepeated() {
        val message = selectPetBubbleMessage(
            context(
                daysSinceLastVisit = 7,
                excludedMessageId = "revisit_welcome",
            ),
            choosing(0, 0),
        )

        assertEquals("revisit_missed", message.id)
    }

    private fun context(
        completedToday: Int = 0,
        availableMeal: Int = 0,
        availableExercise: Int = 0,
        availableWalking: Int = 0,
        todayWalkingMin: Double = 0.0,
        todayWalkingSteps: Int = 0,
        hourOfDay: Int = 12,
        hasFreshHomeData: Boolean = true,
        daysSinceLastVisit: Long? = null,
        excludedMessageId: String? = null,
    ) = PetBubbleContext(
        nickname = "정인",
        completedToday = completedToday,
        availableMeal = availableMeal,
        availableExercise = availableExercise,
        availableWalking = availableWalking,
        todayWalkingMin = todayWalkingMin,
        todayWalkingSteps = todayWalkingSteps,
        hourOfDay = hourOfDay,
        hasFreshHomeData = hasFreshHomeData,
        daysSinceLastVisit = daysSinceLastVisit,
        excludedMessageId = excludedMessageId,
    )

    private fun choosing(vararg indices: Int): (Int) -> Int {
        var cursor = 0
        return { bound ->
            val value = indices.getOrElse(cursor++) { 0 }
            ((value % bound) + bound) % bound
        }
    }
}
