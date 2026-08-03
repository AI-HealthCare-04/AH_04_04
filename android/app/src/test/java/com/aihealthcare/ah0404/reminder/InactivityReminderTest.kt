package com.aihealthcare.ah0404.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 미접속 리마인드 규칙(1차 검토 피드백 - 이탈 방어).
 *
 * 알림은 잘못 보내면 되돌릴 수 없다 - 사용자가 알림을 꺼버리면 그 뒤로는 어떤 리마인드도 못 보낸다.
 * 그래서 "보낼 조건"보다 **"보내지 않을 조건"** 을 더 촘촘히 고정한다.
 */
class InactivityReminderTest {

    private val today = 20_000L // epochDay. 실제 값은 의미 없고 상대 간격만 본다.

    private fun shouldRemind(
        enabled: Boolean = true,
        lastAccess: Long? = today - 3,
        lastNotified: Long? = null,
        sent: Int = 0,
    ) = InactivityReminder.shouldRemind(
        enabled = enabled,
        lastAccessEpochDay = lastAccess,
        lastNotifiedEpochDay = lastNotified,
        consecutiveSent = sent,
        todayEpochDay = today,
    )

    @Test
    fun reminds_after_the_threshold() {
        assertTrue("3일 비면 알린다", shouldRemind(lastAccess = today - 3))
        assertTrue(shouldRemind(lastAccess = today - 10))
    }

    @Test
    fun does_not_remind_before_the_threshold() {
        // 하루이틀 공백은 흔하다. 여기서 알리면 성가심으로만 남는다.
        assertFalse(shouldRemind(lastAccess = today))
        assertFalse(shouldRemind(lastAccess = today - 1))
        assertFalse("경계 직전(2일)은 아직 아니다", shouldRemind(lastAccess = today - 2))
    }

    @Test
    fun does_not_remind_when_turned_off() {
        assertFalse(shouldRemind(enabled = false, lastAccess = today - 30))
    }

    @Test
    fun does_not_remind_someone_who_never_opened_the_app() {
        // 설치만 하고 한 번도 안 연 사람에게 "다시 와달라"는 어색하다.
        assertFalse(shouldRemind(lastAccess = null))
    }

    @Test
    fun does_not_remind_twice_within_the_minimum_gap() {
        // 어제 보냈으면 오늘은 안 보낸다 - 매일 울리면 알림을 꺼버린다.
        assertFalse(shouldRemind(lastAccess = today - 5, lastNotified = today - 1))
        assertFalse("같은 날 두 번은 특히 안 된다", shouldRemind(lastAccess = today - 5, lastNotified = today))
        assertTrue("간격이 차면 다시 보낸다", shouldRemind(lastAccess = today - 6, lastNotified = today - 3))
    }

    @Test
    fun stops_after_the_consecutive_cap() {
        // 세 번 보내도 안 오면 그만둔다. 돌아올 마음이 없는 사용자에게 무한히 울리는 건 이탈 방어가 아니다.
        assertTrue(shouldRemind(lastAccess = today - 30, sent = InactivityReminder.MAX_CONSECUTIVE_REMINDERS - 1))
        assertFalse(shouldRemind(lastAccess = today - 30, sent = InactivityReminder.MAX_CONSECUTIVE_REMINDERS))
        assertFalse(shouldRemind(lastAccess = today - 30, sent = 99))
    }

    @Test
    fun a_future_last_access_never_triggers_a_reminder() {
        // 기기 시간을 앞당겼다 되돌리거나 시간대를 이동하면 마지막 접속이 미래가 될 수 있다.
        //   음수 간격을 공백으로 치면 엉뚱한 알림이 나간다.
        assertFalse(shouldRemind(lastAccess = today + 5))
    }

    // ── 설정 행 상태(리뷰 P1) ─────────────────────────────────────────────────
    // 기본값이 켜짐이라 토글을 건드릴 일이 없는 사용자는 권한을 물을 기회조차 없다.
    //   그러면 켜져 있다고 믿는 채로 알림은 영영 오지 않는다. 토글 표시와 권한 상태가 어긋나면
    //   화면이 그 사실을 말해야 한다.

    @Test
    fun enabled_without_permission_is_surfaced_as_blocked() {
        assertEquals(
            "켜져 있는데 알림이 못 가는 상태를 숨기면 안 된다",
            InactivityReminder.RowState.BLOCKED,
            InactivityReminder.rowState(enabled = true, notificationsAllowed = false),
        )
    }

    @Test
    fun enabled_with_permission_is_active() {
        assertEquals(
            InactivityReminder.RowState.ACTIVE,
            InactivityReminder.rowState(enabled = true, notificationsAllowed = true),
        )
    }

    @Test
    fun turned_off_never_shows_a_permission_notice() {
        // 사용자가 끈 상태에서 권한 안내를 띄우면 끈 선택을 되묻는 잔소리가 된다.
        assertEquals(
            InactivityReminder.RowState.OFF,
            InactivityReminder.rowState(enabled = false, notificationsAllowed = false),
        )
        assertEquals(
            InactivityReminder.RowState.OFF,
            InactivityReminder.rowState(enabled = false, notificationsAllowed = true),
        )
    }

    @Test
    fun the_toggle_and_the_notice_never_contradict_each_other() {
        // 안내가 뜨는 경우는 '켜짐 + 권한 없음' 하나뿐이어야 한다.
        listOf(true, false).forEach { enabled ->
            listOf(true, false).forEach { allowed ->
                val state = InactivityReminder.rowState(enabled, allowed)
                val showsNotice = state == InactivityReminder.RowState.BLOCKED
                assertEquals(
                    "enabled=$enabled allowed=$allowed",
                    enabled && !allowed,
                    showsNotice,
                )
            }
        }
    }

    @Test
    fun permission_notices_tell_the_user_what_to_do() {
        // 상태만 알리고 끝내면 시니어 사용자는 무엇을 눌러야 할지 모른다.
        assertTrue(InactivityReminder.PERMISSION_NOTICE_ASKABLE.contains("눌러서"))
        assertTrue(InactivityReminder.PERMISSION_NOTICE_SETTINGS.contains("설정"))
    }

    // ── 문구 ────────────────────────────────────────────────────────────────
    // 시니어 대상이라 탓하지 않는다. "빼먹었다/못 했다" 대신 다시 시작할 거리를 준다.

    @Test
    fun copy_matches_how_long_they_have_been_away() {
        assertEquals("며칠 못 뵈었어요", InactivityReminder.reminderText(3).first)
        assertEquals("한 주 동안 못 뵈었어요", InactivityReminder.reminderText(7).first)
        assertEquals("오랜만이에요", InactivityReminder.reminderText(14).first)
        assertEquals("오랜만이에요", InactivityReminder.reminderText(100).first)
    }

    // ── epochDay 계산 ───────────────────────────────────────────────────────
    // java.time 은 minSdk 24 에서 desugaring 없이 못 써서 직접 계산한다. 경계를 테스트로 고정한다.

    @Test
    fun today_epoch_day_counts_local_days() {
        val day = 86_400_000L
        // UTC 기준 1970-01-02 00:00 = epochDay 1
        assertEquals(1L, InactivityReminder.todayEpochDay(nowMillis = day, zoneOffsetMillis = 0))
        assertEquals(1L, InactivityReminder.todayEpochDay(nowMillis = day * 2 - 1, zoneOffsetMillis = 0))
        assertEquals(2L, InactivityReminder.todayEpochDay(nowMillis = day * 2, zoneOffsetMillis = 0))
    }

    @Test
    fun today_epoch_day_uses_the_local_timezone() {
        val day = 86_400_000L
        val kst = 9 * 60 * 60 * 1000 // UTC+9
        // UTC 로는 아직 1월 1일 23시지만 한국은 이미 1월 2일이다 — 사용자가 체감하는 날짜를 따라야 한다.
        val utcJan1_23h = day - 60 * 60 * 1000
        assertEquals(0L, InactivityReminder.todayEpochDay(nowMillis = utcJan1_23h, zoneOffsetMillis = 0))
        assertEquals(1L, InactivityReminder.todayEpochDay(nowMillis = utcJan1_23h, zoneOffsetMillis = kst))
    }

    @Test
    fun copy_never_blames_the_user() {
        listOf(3L, 7L, 14L, 60L).forEach { days ->
            val (title, body) = InactivityReminder.reminderText(days)
            listOf("빼먹", "실패", "못 했", "안 했", "게을").forEach { banned ->
                assertFalse("탓하는 표현 금지: $title / $body", (title + body).contains(banned))
            }
        }
    }
}
