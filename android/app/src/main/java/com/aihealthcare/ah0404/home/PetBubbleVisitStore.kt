package com.aihealthcare.ah0404.home

import android.content.Context

internal const val PET_REVISIT_AFTER_DAYS = 3L

private const val PREFS = "pet_bubble_visits"
private const val DAY_MS = 86_400_000L
private const val KST_OFFSET_MS = 9 * 60 * 60 * 1_000L

internal data class PetBubbleVisitState(
    val lastVisitEpochDay: Long,
    val lastMessageId: String?,
)

/** KST는 일광절약시간이 없는 UTC+9 고정 시간대라 epoch millis를 안전하게 날짜 번호로 바꿀 수 있다. */
internal fun kstEpochDay(epochMillis: Long = System.currentTimeMillis()): Long =
    Math.floorDiv(epochMillis + KST_OFFSET_MS, DAY_MS)

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
            ).takeIf { it.lastVisitEpochDay >= 0L }
        }.getOrElse {
            clear(userId)
            null
        }
    }

    fun write(userId: Int, state: PetBubbleVisitState): Boolean {
        if (userId <= 0 || state.lastVisitEpochDay < 0L || state.lastMessageId.isNullOrBlank()) return false
        return runCatching {
            preferences.edit()
                .putLong(visitKey(userId), state.lastVisitEpochDay)
                .putString(messageKey(userId), state.lastMessageId)
                .apply()
            true
        }.getOrDefault(false)
    }

    private fun clear(userId: Int) {
        runCatching {
            preferences.edit()
                .remove(visitKey(userId))
                .remove(messageKey(userId))
                .apply()
        }
    }

    private fun visitKey(userId: Int) = "user_${userId}_last_visit_epoch_day"
    private fun messageKey(userId: Int) = "user_${userId}_last_message_id"
}
