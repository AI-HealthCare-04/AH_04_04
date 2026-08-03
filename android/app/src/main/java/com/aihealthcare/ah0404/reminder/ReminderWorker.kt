package com.aihealthcare.ah0404.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aihealthcare.ah0404.R
import java.util.concurrent.TimeUnit

private const val TAG = "ReminderWorker"
private const val CHANNEL_ID = "aigo_reminder"
private const val NOTIF_ID = 4041
internal const val REMINDER_WORK_NAME = "aigo_inactivity_reminder"

/**
 * 하루 한 번 깨어나 "며칠째 안 들어왔는가"를 보고 리마인드 알림을 띄운다(1차 검토 피드백 - 이탈 방어).
 *
 * 판단 규칙은 전부 [InactivityReminder.shouldRemind] 에 있고 여기서는 **읽고 - 묻고 - 띄우는** 일만 한다.
 * 규칙을 순수 함수로 빼둔 덕에 날짜 경계·중복 발송 같은 까다로운 부분을 JVM 테스트로 고정할 수 있다.
 *
 * WorkManager 의 주기 작업은 **정확한 시각을 보장하지 않는다**(Doze·배터리 최적화로 지연될 수 있음).
 * 리마인드는 몇 시간 늦어도 의미가 유지되므로 정확 알람(AlarmManager + SCHEDULE_EXACT_ALARM)을 쓰지 않았다 -
 * 그 권한은 심사에서 정당화가 필요하고, 여기서는 필요가 없다.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val today = InactivityReminder.todayEpochDay()
        val lastAccess = InactivityReminder.lastAccessEpochDay(ctx)

        val send = InactivityReminder.shouldRemind(
            enabled = InactivityReminder.isEnabled(ctx),
            lastAccessEpochDay = lastAccess,
            lastNotifiedEpochDay = InactivityReminder.lastNotifiedEpochDay(ctx),
            consecutiveSent = InactivityReminder.consecutiveSent(ctx),
            todayEpochDay = today,
        )
        if (!send || lastAccess == null) return Result.success()

        // 알림 권한이 없으면(Android 13+ 거부, 또는 채널·앱 알림 끔) 조용히 넘어간다 —
        //   보내지 못한 알림을 보냈다고 기록하면 권한을 준 뒤 연속 횟수가 이미 소진돼 있다.
        if (!canPostNotifications(ctx)) {
            Log.i(TAG, "알림 권한 없음 — 리마인드 생략")
            return Result.success()
        }

        val (title, body) = InactivityReminder.reminderText(today - lastAccess)
        @Suppress("MissingPermission") // 바로 위 canPostNotifications 로 확인했다
        runCatching { notify(ctx, title, body) }
            .onSuccess { InactivityReminder.recordNotified(ctx, today) }
            .onFailure { Log.w(TAG, "리마인드 알림 실패: ${it.javaClass.simpleName}") }
        return Result.success()
    }

    /**
     * 알림을 실제로 띄울 수 있는가. Android 13+ 의 런타임 권한과 사용자가 앱·채널 알림을 끈 경우를 모두 본다
     * (권한은 있어도 알림을 꺼둘 수 있다).
     */
    private fun canPostNotifications(ctx: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun notify(ctx: Context, title: String, body: String) {
        createChannelIfNeeded(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            // 시니어 대상 - 글자가 커지면 한 줄이 잘리므로 펼침 스타일을 함께 준다.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(launchIntent(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
    }

    private fun launchIntent(ctx: Context): PendingIntent? {
        val intent: Intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannelIfNeeded(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        // 걷기 측정 채널(IMPORTANCE_LOW, 진행 상태 전용)과 **분리한다** — 사용자가 리마인드만
        //   따로 끌 수 있어야 하고, 측정 중 알림을 끄면 포그라운드 서비스 표시까지 사라진다.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "다시 알림",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "며칠 동안 앱을 열지 않으면 알려드려요" }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        /**
         * 하루 한 번 검사를 예약한다. 앱이 열릴 때마다 불러도 안전하다(KEEP 이라 기존 예약을 덮지 않음).
         * 재부팅 후 재예약은 WorkManager 가 알아서 한다.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                // flex 구간을 주면 시스템이 다른 작업과 묶어 배터리를 덜 쓴다.
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                REMINDER_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(REMINDER_WORK_NAME)
        }
    }
}
