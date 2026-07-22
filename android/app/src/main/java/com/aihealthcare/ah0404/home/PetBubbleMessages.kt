package com.aihealthcare.ah0404.home

import kotlin.math.roundToInt
import kotlin.random.Random

internal data class PetBubbleContext(
    val nickname: String,
    val completedToday: Int,
    val availableMeal: Int,
    val availableExercise: Int,
    val availableWalking: Int,
    val todayWalkingMin: Double,
    val todayWalkingSteps: Int,
    val hourOfDay: Int,
    val hasFreshHomeData: Boolean = true,
)

internal data class PetBubbleMessage(
    val id: String,
    val text: String,
)

/**
 * 현재 홈 데이터로 사실임을 확인할 수 있는 문구만 고른다.
 *
 * 먼저 상황을 고르고 그 안에서 문구를 고르므로, 문구가 많은 상황이 선택 확률을
 * 과도하게 차지하지 않는다. 앱 실행 간 중복 방지는 공통 로컬 저장소(#105) 연동 후
 * 확장한다.
 */
internal fun selectPetBubbleMessage(
    context: PetBubbleContext,
    randomIndex: (Int) -> Int = { Random.Default.nextInt(it) },
): PetBubbleMessage {
    if (context.hasFreshHomeData) {
        val achievementGroups = buildList {
            if (context.completedToday > 0) add(completionMessages(context.completedToday))
            if (context.todayWalkingMin >= 1.0) add(walkingMinuteMessages(context.todayWalkingMin))
            if (context.todayWalkingSteps > 0) add(walkingStepMessages(context.todayWalkingSteps))
        }
        if (achievementGroups.isNotEmpty()) return chooseFromGroups(achievementGroups, randomIndex)
    }

    val groups = buildList {
        if (context.hasFreshHomeData && !isLateNight(context.hourOfDay)) {
            if (context.availableMeal > 0) add(mealPromptMessages)
            if (context.availableExercise > 0) add(exercisePromptMessages)
            if (context.availableWalking > 0) add(walkingPromptMessages)
        }
        add(timeGreetingMessages(context.hourOfDay))
        add(generalEncouragementMessages(context.nickname))
    }
    return chooseFromGroups(groups, randomIndex)
}

internal fun isLateNight(hourOfDay: Int): Boolean = hourOfDay >= 22 || hourOfDay < 6

private fun chooseFromGroups(
    groups: List<List<PetBubbleMessage>>,
    randomIndex: (Int) -> Int,
): PetBubbleMessage {
    val group = groups[randomIndex(groups.size).floorMod(groups.size)]
    return group[randomIndex(group.size).floorMod(group.size)]
}

private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

private fun generalEncouragementMessages(nickname: String): List<PetBubbleMessage> = listOf(
    PetBubbleMessage("general_nickname", "${nickname}님, 오늘도 천천히 함께해요!"),
    PetBubbleMessage("general_together_fun", "같이해서 더 즐거워요!"),
    PetBubbleMessage("general_happy_together", "오늘도 함께할 수 있어 기뻐요."),
    PetBubbleMessage("general_slowly", "우리 천천히 같이 해봐요."),
    PetBubbleMessage("general_exciting", "함께하면 더 신나요!"),
)

private fun timeGreetingMessages(hourOfDay: Int): List<PetBubbleMessage> = when (hourOfDay) {
    in 6..10 -> listOf(
        PetBubbleMessage("morning_hello", "좋은 아침이에요! 오늘도 만나서 반가워요."),
        PetBubbleMessage("morning_sleep", "잘 주무셨어요? 저는 꿈에서 춤췄어요."),
    )
    in 11..16 -> listOf(
        PetBubbleMessage("afternoon_easy", "여유로운 오후 보내고 계신가요?"),
        PetBubbleMessage("afternoon_hello", "오늘도 반가워요. 천천히 함께해요."),
    )
    in 17..21 -> listOf(
        PetBubbleMessage("evening_well_done", "오늘도 수고 많으셨어요."),
        PetBubbleMessage("evening_rest", "편안한 저녁 보내세요."),
    )
    else -> listOf(
        PetBubbleMessage("night_rest", "편안한 밤 보내세요."),
        PetBubbleMessage("night_tomorrow", "내일 또 반갑게 만나요."),
    )
}

private val mealPromptMessages = listOf(
    PetBubbleMessage("meal_check", "오늘 식사 미션을 함께 확인해볼까요?"),
    PetBubbleMessage("meal_hearty", "맛있고 든든하게 챙겨 드세요."),
    PetBubbleMessage("meal_table", "오늘 밥상도 맛있게 챙겨 드세요."),
)

private val exercisePromptMessages = listOf(
    PetBubbleMessage("exercise_together", "오늘은 저랑 운동해요?"),
    PetBubbleMessage("exercise_light", "가볍게 몸을 움직여 볼까요?"),
    PetBubbleMessage("exercise_slowly", "우리 천천히 같이 운동해봐요."),
)

private val walkingPromptMessages = listOf(
    PetBubbleMessage("walking_out", "오늘은 산책 가요?"),
    PetBubbleMessage("walking_beside", "천천히 걸어도 괜찮아요. 제가 옆에 있을게요."),
    PetBubbleMessage("walking_comfortable", "편한 만큼 천천히 걸어볼까요?"),
    PetBubbleMessage("walking_pocket", "저도 주머니에 넣고 데려가 주세요!"),
    PetBubbleMessage("walking_little", "조금씩 움직이는 것도 충분히 좋아요."),
)

private fun completionMessages(completedToday: Int): List<PetBubbleMessage> = listOf(
    PetBubbleMessage("completion_count", "오늘 미션을 ${completedToday}개 해냈어요. 잘했어요!"),
    PetBubbleMessage("completion_proud", "우와, 해내셨네요! 제가 다 뿌듯해요."),
    PetBubbleMessage("completion_great", "오늘 진짜 멋있으셨어요!"),
    PetBubbleMessage("completion_step", "한 걸음 해내셨네요. 정말 멋져요!"),
    PetBubbleMessage("completion_self_care", "오늘도 스스로를 잘 챙기셨어요."),
    PetBubbleMessage("completion_small_action", "오늘의 작은 실천이 정말 소중해요."),
)

private fun walkingMinuteMessages(minutes: Double): List<PetBubbleMessage> = listOf(
    PetBubbleMessage(
        "walking_minutes",
        "오늘 ${minutes.roundToInt()}분 걸었어요. 꾸준히 이어가고 있어요!",
    ),
    PetBubbleMessage("walking_minutes_praise", "오늘도 걷기를 이어가셨네요. 정말 멋져요!"),
)

private fun walkingStepMessages(steps: Int): List<PetBubbleMessage> = listOf(
    PetBubbleMessage("walking_steps", "오늘 %,d보 걸었어요. 멋져요!".format(steps)),
    PetBubbleMessage("walking_steps_praise", "오늘 움직인 모든 걸음이 정말 소중해요."),
)
