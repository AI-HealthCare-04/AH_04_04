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
     * 설정 화면 '다시 알림' 행의 상태(리뷰 P1).
     *
     * 토글이 켜져 있다고 해서 알림이 오는 게 아니다 - Android 13+ 는 런타임 권한이 필요하고, 권한이 있어도
     * 사용자가 앱 알림을 통째로 꺼둘 수 있다. 그 어긋남을 화면이 숨기면 **사용자는 켜져 있다고 믿는데
     * 알림은 영영 오지 않는다.** 기본값이 켜짐이라 더 그렇다 - 토글을 건드릴 일이 없으니 권한을 물을
     * 기회조차 없다. 그래서 세 상태를 구분해 [RowState.NEEDS_PERMISSION] 을 화면에 드러낸다.
     */
    enum class RowState {
        /** 사용자가 껐다. 안내할 것 없음 — 끈 선택을 되묻지 않는다. */
        OFF,

        /** 켜져 있고 실제로 알림이 갈 수 있다. */
        ACTIVE,

        /** 켜져 있지만 런타임 권한·앱 알림 스위치가 막고 있다. */
        NEEDS_PERMISSION,

        /** 켜져 있고 권한도 있지만 **'다시 알림' 채널만** 시스템에서 꺼졌다. */
        CHANNEL_BLOCKED,
    }

    /**
     * '원하는 상태'(앱 토글)와 '실제 전달 가능 상태'(권한·채널)를 함께 본다(리뷰 - 지영).
     *
     * 채널이 꺼졌다고 앱 토글을 자동으로 끄지 않는다 — 시스템에서 채널을 다시 켜도 앱 토글은 꺼진 채
     * 남아 **또 다른 불일치**가 생기고, 시스템 설정 변경이 앱 내부 선호를 조용히 바꾸면 사용자가
     * 원인을 이해할 수 없다. 대신 두 상태를 각각 드러낸다.
     */
    fun rowState(enabled: Boolean, appNotificationsAllowed: Boolean, channelEnabled: Boolean): RowState = when {
        !enabled -> RowState.OFF
        !appNotificationsAllowed -> RowState.NEEDS_PERMISSION
        !channelEnabled -> RowState.CHANNEL_BLOCKED
        else -> RowState.ACTIVE
    }

    /** 권한을 아직 물어볼 수 있을 때. 눌러서 바로 허용할 수 있다. */
    const val PERMISSION_NOTICE_ASKABLE = "알림 권한이 꺼져 있어 알림이 가지 않아요. 눌러서 허용해 주세요."

    /** 요청이 더 이상 뜨지 않는 상태(영구 거부·앱 알림 전체 끔). 시스템 설정으로 보내야 한다. */
    const val PERMISSION_NOTICE_SETTINGS = "알림 권한이 꺼져 있어 알림이 가지 않아요. 눌러서 설정에서 켜 주세요."

    /**
     * 채널만 꺼진 경우. 권한 문제가 아니므로 **원인을 정확히** 말하고, 사용자가 직접 끈 선택을
     * 되묻는 느낌이 되지 않게 오류가 아니라 상태 안내 톤으로 쓴다(리뷰 - 지영).
     */
    const val CHANNEL_NOTICE = "시스템에서 '다시 알림'이 꺼져 있어요. 눌러서 켤 수 있어요."

    private const val KEY_PERMISSION_ASKED = "permission_asked"

    /** 알림 권한을 한 번이라도 요청한 적 있는가. 아래 [canRequestPermission] 의 모호함을 푸는 데 쓴다. */
    fun hasAskedPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMISSION_ASKED, false)

    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    /**
     * 지금 권한 요청 팝업을 띄울 수 있는가(리뷰 - 정인).
     *
     * Android 13+ 에서 영구 거부되면 `launch()` 는 **팝업 없이 조용히 끝난다.** 그 상태에서 요청만 다시
     * 던지면 사용자는 눌러도 아무 일이 없는 화면을 보게 되므로, 시스템 알림 설정으로 보내야 한다.
     *
     * `shouldShowRequestPermissionRationale` 만으로는 판별이 안 된다 - **한 번도 안 물어본 상태**와
     * **영구 거부 상태**가 둘 다 false 라서다. 그래서 '물어본 적 있는가'를 함께 본다.
     */
    fun canRequestPermission(hasAsked: Boolean, shouldShowRationale: Boolean): Boolean =
        !hasAsked || shouldShowRationale

    /** 안내를 눌렀을 때 무엇을 해야 복구되는가. */
    enum class RecoveryAction {
        REQUEST_PERMISSION,

        /** 앱 알림 설정. 런타임 권한을 요청으로 되살릴 수 없을 때. */
        OPEN_APP_SETTINGS,

        /**
         * **채널 설정으로 바로** 보낸다(리뷰 - 지영). 앱 알림 설정으로 보내고 목록에서 채널을 찾게 하면
         * 시니어 사용자에게는 사실상 막힌 길이다.
         */
        OPEN_CHANNEL_SETTINGS,
    }

    /**
     * 막힌 상태를 **무엇으로 풀 수 있는지** 고른다(리뷰 P1 2차).
     *
     * 알림이 막히는 경로는 하나가 아니다. 런타임 권한 거부만 보고 요청을 던지면, **권한은 허용됐는데
     * 사용자가 앱 알림을 통째로 끈 경우** 요청 함수가 "이미 허용됨"으로 즉시 돌아와 **눌러도 아무 일이
     * 일어나지 않는다.** Android 12 이하도 런타임 권한 자체가 없어 같은 막다른 길이 된다.
     * 그래서 '권한이 없는가'가 아니라 **'런타임 요청으로 복구되는가'** 를 기준으로 나눈다.
     */
    fun recoveryAction(
        rowState: RowState,
        supportsRuntimePermission: Boolean,
        permissionGranted: Boolean,
        hasAsked: Boolean,
        shouldShowRationale: Boolean,
    ): RecoveryAction? = when (rowState) {
        // 끈 사람에게도, 잘 되는 사람에게도 할 말이 없다.
        RowState.OFF, RowState.ACTIVE -> null
        RowState.CHANNEL_BLOCKED -> RecoveryAction.OPEN_CHANNEL_SETTINGS
        RowState.NEEDS_PERMISSION -> when {
            // Android 12 이하: 런타임 권한이 없다 → 막혔다면 앱 알림 스위치가 꺼진 것뿐이다.
            !supportsRuntimePermission -> RecoveryAction.OPEN_APP_SETTINGS
            // 권한은 있는데 막혔다 → 요청해봤자 즉시 "이미 허용됨"으로 끝난다.
            permissionGranted -> RecoveryAction.OPEN_APP_SETTINGS
            canRequestPermission(hasAsked, shouldShowRationale) -> RecoveryAction.REQUEST_PERMISSION
            // 영구 거부 — launch() 가 팝업 없이 조용히 끝난다.
            else -> RecoveryAction.OPEN_APP_SETTINGS
        }
    }

    /** 복구 방법에 맞는 안내 문구. 문구와 동작이 어긋나면 눌러도 기대한 화면이 안 뜬다. */
    fun permissionNotice(action: RecoveryAction): String = when (action) {
        RecoveryAction.REQUEST_PERMISSION -> PERMISSION_NOTICE_ASKABLE
        RecoveryAction.OPEN_APP_SETTINGS -> PERMISSION_NOTICE_SETTINGS
        RecoveryAction.OPEN_CHANNEL_SETTINGS -> CHANNEL_NOTICE
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
