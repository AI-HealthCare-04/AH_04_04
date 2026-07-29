package com.aihealthcare.ah0404.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBubbleMessagesTest {

    @Test
    fun fresh_completed_streak_has_priority_and_uses_server_snapshot_key() {
        val message = selectPetBubbleMessage(
            context(
                completedToday = 2,
                streakCurrentDays = 4,
                streakCompletedToday = true,
                streakAsOfDate = "2026-07-27",
            ),
            choosing(0, 0),
        )

        assertEquals("streak_praise", message.id)
        assertEquals("작심삼일을 넘어섰어요! 정말 대단해요!", message.text)
        assertEquals("streak:2026-07-27:4", message.deduplicationKey)
    }

    @Test
    fun praises_only_agreed_milestones_with_day_based_copy_for_fourteen_and_twenty_one() {
        val expectedMessages = mapOf(
            1 to "오늘의 건강한 실천을 시작했어요. 정말 잘하셨어요!",
            4 to "작심삼일을 넘어섰어요! 정말 대단해요!",
            7 to "건강한 실천을 시작한 지 벌써 일주일이에요!",
            14 to "벌써 14일째 꾸준히 실천하고 있어요!",
            21 to "벌써 21일째 꾸준히 실천하고 있어요!",
            30 to "건강한 실천을 한 달 동안 이어 왔어요! 정말 대단해요!",
            60 to "건강한 실천을 2개월째 이어 가고 있어요!",
            90 to "건강한 실천을 3개월째 이어 가고 있어요!",
        )

        expectedMessages.forEach { (days, expectedText) ->
            val message = selectPetBubbleMessage(
                context(
                    streakCurrentDays = days,
                    streakCompletedToday = true,
                    streakAsOfDate = "2026-07-27",
                ),
            )

            assertEquals("streak_praise", message.id)
            assertEquals(expectedText, message.text)
        }
    }

    @Test
    fun non_milestone_streak_days_fall_back_to_a_normal_message() {
        listOf(0, 2, 3, 5, 6, 8, 13, 15, 20, 22, 29, 31, 59, 61).forEach { days ->
            val message = selectPetBubbleMessage(
                context(
                    streakCurrentDays = days,
                    streakCompletedToday = true,
                    streakAsOfDate = "2026-07-27",
                ),
                choosing(0, 0),
            )

            assertEquals("${days}일은 스트릭 칭찬 대상이 아니어야 합니다.", "afternoon_easy", message.id)
        }
    }

    @Test
    fun mismatched_server_date_falls_back_without_streak_praise() {
        val message = selectPetBubbleMessage(
            context(
                streakCurrentDays = 7,
                streakCompletedToday = true,
                streakAsOfDate = "2026-07-26",
                todayKstDate = "2026-07-27",
            ),
            choosing(0, 0),
        )

        assertEquals("afternoon_easy", message.id)
    }

    @Test
    fun revisit_message_keeps_priority_over_a_streak_milestone() {
        val message = selectPetBubbleMessage(
            context(
                daysSinceLastVisit = 3,
                streakCurrentDays = 7,
                streakCompletedToday = true,
                streakAsOfDate = "2026-07-27",
            ),
            choosing(0, 0),
        )

        assertEquals("revisit_welcome", message.id)
    }

    @Test
    fun same_streak_snapshot_is_not_praised_twice() {
        val message = selectPetBubbleMessage(
            context(
                completedToday = 1,
                streakCurrentDays = 4,
                streakCompletedToday = true,
                streakAsOfDate = "2026-07-27",
                shownStreakKey = "streak:2026-07-27:4",
            ),
            choosing(0, 0),
        )

        assertEquals("completion_count", message.id)
    }

    @Test
    fun next_server_streak_snapshot_can_be_praised() {
        val message = selectPetBubbleMessage(
            context(
                streakCurrentDays = 7,
                streakCompletedToday = true,
                streakAsOfDate = "2026-07-28",
                todayKstDate = "2026-07-28",
                shownStreakKey = "streak:2026-07-27:4",
            ),
        )

        assertEquals("streak:2026-07-28:7", message.deduplicationKey)
    }

    @Test
    fun incomplete_zero_or_stale_streak_falls_back_without_claiming_achievement() {
        val incomplete = selectPetBubbleMessage(
            context(streakCurrentDays = 4, streakCompletedToday = false, streakAsOfDate = "2026-07-27"),
            choosing(0, 0),
        )
        val zero = selectPetBubbleMessage(
            context(streakCurrentDays = 0, streakCompletedToday = true, streakAsOfDate = "2026-07-27"),
            choosing(0, 0),
        )
        val stale = selectPetBubbleMessage(
            context(
                streakCurrentDays = 4,
                streakCompletedToday = true,
                streakAsOfDate = "2026-07-27",
                hasFreshHomeData = false,
            ),
            choosing(0, 0),
        )

        assertEquals("afternoon_easy", incomplete.id)
        assertEquals("afternoon_easy", zero.id)
        assertEquals("afternoon_easy", stale.id)
    }

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
                excludedMessageIds = setOf("afternoon_easy"),
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
                excludedMessageIds = setOf("revisit_welcome"),
            ),
            choosing(0, 0),
        )

        assertEquals("revisit_missed", message.id)
    }

    @Test
    fun sameDayMessages_doNotRepeatUntilTheAvailablePoolIsExhausted() {
        val shown = mutableSetOf<String>()

        // 오후 인사 2개 + 일반 격려 5개를 모두 한 번씩 소비하기 전에는 A→B→A 반복이 없어야 한다.
        repeat(7) {
            val message = selectPetBubbleMessage(
                context(
                    daysSinceLastVisit = 0,
                    excludedMessageIds = shown.toSet(),
                    hourOfDay = 12,
                ),
                choosing(0, 0),
            )
            assertTrue("같은 KST 날짜에는 아직 안 본 문구를 선택해야 합니다.", shown.add(message.id))
        }

        // 유효 후보를 전부 소비한 뒤에는 홈 말풍선이 사라지지 않도록 기존 풀을 다시 사용할 수 있다.
        val messageAfterExhaustion = selectPetBubbleMessage(
            context(
                daysSinceLastVisit = 0,
                excludedMessageIds = shown.toSet(),
                hourOfDay = 12,
            ),
            choosing(0, 0),
        )
        assertTrue(messageAfterExhaustion.id in shown)
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
        excludedMessageIds: Set<String> = emptySet(),
        streakCurrentDays: Int = 0,
        streakCompletedToday: Boolean = false,
        streakAsOfDate: String? = null,
        todayKstDate: String? = "2026-07-27",
        shownStreakKey: String? = null,
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
        excludedMessageIds = excludedMessageIds,
        streakCurrentDays = streakCurrentDays,
        streakCompletedToday = streakCompletedToday,
        streakAsOfDate = streakAsOfDate,
        todayKstDate = todayKstDate,
        shownStreakKey = shownStreakKey,
    )

    private fun choosing(vararg indices: Int): (Int) -> Int {
        var cursor = 0
        return { bound ->
            val value = indices.getOrElse(cursor++) { 0 }
            ((value % bound) + bound) % bound
        }
    }
}
