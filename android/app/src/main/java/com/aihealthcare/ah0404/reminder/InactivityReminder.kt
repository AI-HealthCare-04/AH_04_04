package com.aihealthcare.ah0404.reminder

import android.content.Context

/**
 * 미접속 리마인드 알림(1차 검토 피드백 - 이탈 방어).
 *
 * **왜 '마지막 접속'인가**: 챌린지 완료를 기준으로 하면 운동 영상만 따라 하는 사용자에게 "안 하고 있다"고
 * 잘못 알린다. 접속은 그 위의 개념이라 - 영상만 보든 챌린지만 하든 앱은 열어야 한다 - 한 기준으로 둘 다 덮는다.
 * 이탈은 결국 '앱을 안 여는 것'이므로 목적에도 맞는다.
 *
 * **서버를 쓰지 않는다**: 판단에 필요한 건 마지막 접속 일자뿐이고 그건 기기가 안다. FCM·푸시 서버 없이
 * 기기 로컬 알림만으로 끝나므로 백엔드 변경이 없다.
 *
 * 날짜는 **epochDay**(로컬 시간대 기준 일 단위)로 다룬다. 시:분을 빼면 "몇 밤 지났나"가 그대로 뺄셈이 되고,
 * 자정 경계·서머타임 같은 문제도 생기지 않는다.
 */
object InactivityReminder {

    /** 며칠 안 들어오면 알릴 것인가. 하루이틀은 흔한 공백이라 3일부터 본다. */
    const val REMIND_AFTER_DAYS = 3

    /** 알림을 보낸 뒤 다음 알림까지 최소 간격. 매일 울리면 알림을 꺼버린다. */
    const val MIN_GAP_BETWEEN_REMINDERS = 3

    /**
     * 연속으로 보낼 수 있는 최대 횟수. 이만큼 보내도 안 들어오면 **그만둔다** -
     * 돌아올 마음이 없는 사용자에게 무한히 울리는 건 이탈 방어가 아니라 그냥 성가심이다.
     * 접속하면 [recordAccess] 가 0 으로 되돌린다.
     */
    const val MAX_CONSECUTIVE_REMINDERS = 3

    private const val PREFS = "aigo_reminder"
    private const val KEY_ENABLED = "reminder_enabled"
    private const val KEY_LAST_ACCESS = "last_access_epoch_day"
    private const val KEY_LAST_NOTIFIED = "last_notified_epoch_day"
    private const val KEY_SENT_COUNT = "consecutive_sent"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 알림 사용 여부. 기본 켜짐 - 이탈 방어가 목적이라 기본값이 꺼짐이면 의미가 없다. 설정에서 끌 수 있다. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 앱을 열었다 - 마지막 접속을 오늘로 갱신하고 연속 발송 횟수를 되돌린다.
     * 돌아온 사용자를 다시 처음부터 세는 것이 맞다.
     */
    fun recordAccess(context: Context, todayEpochDay: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_ACCESS, todayEpochDay)
            .putInt(KEY_SENT_COUNT, 0)
            .apply()
    }

    fun lastAccessEpochDay(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_ACCESS, NEVER).takeIf { it != NEVER }

    fun lastNotifiedEpochDay(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_NOTIFIED, NEVER).takeIf { it != NEVER }

    fun consecutiveSent(context: Context): Int = prefs(context).getInt(KEY_SENT_COUNT, 0)

    fun recordNotified(context: Context, todayEpochDay: Long) {
        val p = prefs(context)
        p.edit()
            .putLong(KEY_LAST_NOTIFIED, todayEpochDay)
            .putInt(KEY_SENT_COUNT, p.getInt(KEY_SENT_COUNT, 0) + 1)
            .apply()
    }

    private const val NEVER = -1L

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * 로컬 시간대 기준 오늘의 epochDay.
     *
     * `java.time.LocalDate` 는 API 26 부터라 minSdk 24 에서 쓰려면 core library desugaring 을 켜야 한다 —
     * 날짜 하나 때문에 앱 전체 빌드 설정을 바꾸는 건 과하다. 일 단위만 필요하므로 직접 계산한다.
     * [Math.floorDiv] 를 쓰는 이유: 1970 이전(음수 밀리초)에서도 내림이 맞아야 한다.
     */
    fun todayEpochDay(
        nowMillis: Long = System.currentTimeMillis(),
        zoneOffsetMillis: Int = java.util.TimeZone.getDefault().getOffset(nowMillis),
    ): Long = Math.floorDiv(nowMillis + zoneOffsetMillis, MILLIS_PER_DAY)

    /**
     * 오늘 알림을 보낼 것인가. **순수 함수** - 저장소·안드로이드 프레임워크와 분리해 테스트로 규칙을 고정한다.
     *
     * @param lastAccessEpochDay null = 앱을 연 기록이 없음. 설치만 하고 안 쓴 사람에게 리마인드는 어색하므로 보내지 않는다.
     */
    fun shouldRemind(
        enabled: Boolean,
        lastAccessEpochDay: Long?,
        lastNotifiedEpochDay: Long?,
        consecutiveSent: Int,
        todayEpochDay: Long,
        remindAfterDays: Int = REMIND_AFTER_DAYS,
        minGap: Int = MIN_GAP_BETWEEN_REMINDERS,
        maxConsecutive: Int = MAX_CONSECUTIVE_REMINDERS,
    ): Boolean {
        if (!enabled) return false
        if (lastAccessEpochDay == null) return false
        if (consecutiveSent >= maxConsecutive) return false
        val daysAway = todayEpochDay - lastAccessEpochDay
        // 미래 날짜(기기 시간 변경·시간대 이동)는 공백으로 치지 않는다 - 음수 간격에 알림을 보내면 안 된다.
        if (daysAway < remindAfterDays) return false
        if (lastNotifiedEpochDay != null && todayEpochDay - lastNotifiedEpochDay < minGap) return false
        return true
    }

    /**
     * 알림 문구. 며칠 비었는지에 따라 나눈다 - 오래 비었는데 "며칠 못 뵈었어요"는 어색하다.
     * 시니어 대상이라 **탓하지 않고**(빼먹었다/못 했다) 가볍게 다시 시작할 거리를 준다.
     */
    fun reminderText(daysAway: Long): Pair<String, String> = when {
        daysAway >= 14L -> "오랜만이에요" to "오랜만에 몸 상태를 확인해 볼까요? 가볍게 걷기부터 좋아요."
        daysAway >= 7L -> "한 주 동안 못 뵈었어요" to "오늘 10분만 걸어도 근육에 도움이 돼요."
        else -> "며칠 못 뵈었어요" to "오늘은 가볍게 걷기부터 시작해 볼까요?"
    }
}
