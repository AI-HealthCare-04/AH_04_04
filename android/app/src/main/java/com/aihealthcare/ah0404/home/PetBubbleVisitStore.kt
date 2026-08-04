package com.aihealthcare.ah0404.home

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal const val PET_REVISIT_AFTER_DAYS = 3L

private const val PREFS = "pet_bubble_visits"
private const val DAY_MS = 86_400_000L
private const val KST_OFFSET_MS = 9 * 60 * 60 * 1_000L

internal data class PetBubbleVisitState(
    val lastVisitEpochDay: Long,
    val lastMessageId: String?,
    val lastStreakKey: String? = null,
    val shownMessageIds: Set<String> = emptySet(),
)

/** KST는 일광절약시간이 없는 UTC+9 고정 시간대라 epoch millis를 안전하게 날짜 번호로 바꿀 수 있다. */
internal fun kstEpochDay(epochMillis: Long = System.currentTimeMillis()): Long =
    Math.floorDiv(epochMillis + KST_OFFSET_MS, DAY_MS)

/** 서버 `as_of_date`와 비교할 현재 KST 서비스 날짜를 ISO-8601 날짜 문자열로 만든다. */
internal fun kstDateString(epochMillis: Long = System.currentTimeMillis()): String {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+09:00")).apply {
        timeInMillis = epochMillis
    }
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
}

/**
 * 마지막 방문일과 오늘 사이의 KST 달력 날짜 차이.
 * 미래값·음수 등 손상된 값은 재방문 근거로 쓰지 않고 null로 폴백한다.
 */
internal fun daysSinceLastVisit(lastVisitEpochDay: Long?, todayEpochDay: Long): Long? {
    if (lastVisitEpochDay == null || lastVisitEpochDay < 0L || lastVisitEpochDay > todayEpochDay) return null
    return todayEpochDay - lastVisitEpochDay
}

/**
 * 사용자별 홈 방문·직전 말풍선 상태(#145).
 *
 * 키에는 서버 `user_id`만 사용하며 토큰 원문·닉네임을 넣지 않는다. 호출부는 완료된 소셜 계정의
 * `SessionStore.persistentUserId`가 있을 때만 읽고 쓴다. SharedPreferences 값이 손상돼도 예외를
 * 화면까지 전파하지 않고 해당 사용자 상태만 폐기해 기본 말풍선으로 폴백한다.
 */
internal class PetBubbleVisitStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(userId: Int): PetBubbleVisitState? {
        if (userId <= 0) return null
        return runCatching {
            val visitKey = visitKey(userId)
            if (!preferences.contains(visitKey)) return null
            PetBubbleVisitState(
                lastVisitEpochDay = preferences.getLong(visitKey, -1L),
                lastMessageId = preferences.getString(messageKey(userId), null),
                lastStreakKey = preferences.getString(streakKey(userId), null),
                shownMessageIds = preferences.getStringSet(messageIdsKey(userId), null)
                    ?.toSet()
                    ?: setOfNotNull(preferences.getString(messageKey(userId), null)),
            ).takeIf { it.lastVisitEpochDay >= 0L }
        }.getOrElse {
            clear(userId)
            null
        }
    }

    fun write(userId: Int, state: PetBubbleVisitState): Boolean {
        if (userId <= 0 || state.lastVisitEpochDay < 0L || state.lastMessageId.isNullOrBlank()) return false
        return runCatching {
            val merged = mergePetBubbleVisitState(read(userId), state)
            val editor = preferences.edit()
                .putLong(visitKey(userId), merged.lastVisitEpochDay)
                .putString(messageKey(userId), merged.lastMessageId)
                .putStringSet(messageIdsKey(userId), merged.shownMessageIds)
            // 일반 말풍선 기록이 뒤이어도 같은 스트릭 칭찬의 노출 이력은 보존한다.
            merged.lastStreakKey?.let { editor.putString(streakKey(userId), it) }
            editor.apply()
            true
        }.getOrDefault(false)
    }

    private fun clear(userId: Int) {
        runCatching {
            preferences.edit()
                .remove(visitKey(userId))
                .remove(messageKey(userId))
                .remove(streakKey(userId))
                .remove(messageIdsKey(userId))
                .apply()
        }
    }

    private fun visitKey(userId: Int) = "user_${userId}_last_visit_epoch_day"
    private fun messageKey(userId: Int) = "user_${userId}_last_message_id"
    private fun streakKey(userId: Int) = "user_${userId}_last_streak_key"
    private fun messageIdsKey(userId: Int) = "user_${userId}_shown_message_ids"
}

/**
 * 같은 사용자·KST 날짜의 말풍선 이력을 누적한다.
 *
 * SharedPreferences 쓰기가 연달아 발생해도 마지막 write가 앞선 문구 이력을 덮어쓰지 않게,
 * 저장 직전 상태와 새 상태를 병합한다. 날짜가 바뀌면 일반 문구 이력만 초기화하고 스트릭
 * 중복 방지 키는 보존한다(키 자체에 서버 기준일이 포함돼 다음 마일스톤을 막지 않는다).
 */
internal fun mergePetBubbleVisitState(
    previous: PetBubbleVisitState?,
    next: PetBubbleVisitState,
): PetBubbleVisitState {
    val sameKstDay = previous?.lastVisitEpochDay == next.lastVisitEpochDay
    val shownMessageIds = buildSet {
        if (sameKstDay) addAll(previous.shownMessageIds)
        addAll(next.shownMessageIds)
        next.lastMessageId?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return next.copy(
        lastStreakKey = next.lastStreakKey ?: previous?.lastStreakKey,
        shownMessageIds = shownMessageIds,
    )
}

/** 이전 방문이 오늘인 경우에만 당일 노출 이력을 선택 정책에 전달한다. */
internal fun shownMessageIdsForToday(
    state: PetBubbleVisitState?,
    todayEpochDay: Long,
): Set<String> = state
    ?.takeIf { it.lastVisitEpochDay == todayEpochDay }
    ?.shownMessageIds
    .orEmpty()
