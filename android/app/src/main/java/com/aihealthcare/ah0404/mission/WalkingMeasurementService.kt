package com.aihealthcare.ah0404.mission

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aihealthcare.ah0404.R

/**
 * ============================================================================
 *  WalkingMeasurementService : 걷기 측정 백그라운드 지속(#199, 증분 A)
 * ============================================================================
 *
 *  화면이 꺼지거나 앱이 백그라운드로 가도 걸음 측정이 멈추지 않도록, 측정 중에는 포그라운드
 *  서비스로 프로세스를 살려 둔다(지울 수 없는 알림 "걷기 측정 중 · N보"를 표시).
 *
 *  ⚠️ 이 서비스는 **센서를 직접 등록하지 않는다**. 걸음 수의 원천은 컨트롤러
 *     (ServiceWalkingSessionController)가 소유한 단일 WalkingSession 이며, 서비스는
 *     WalkingMeasurement 홀더의 공급자로부터 걸음 수만 읽어 알림을 갱신한다(이중 계수 방지).
 *
 *  FGS 타입 = health(피트니스 측정). 실행 선행요건은 매니페스트의 HIGH_SAMPLING_RATE_SENSORS
 *  (자동 승인)로 충족한다 — ACTIVITY_RECOGNITION 재도입 불필요(공식 문서 확인).
 *
 *  범위(증분 A): '화면 꺼짐/백그라운드(프로세스 생존)' 동안의 측정 지속만. 프로세스/Activity 종료
 *  후 재부착·저장 체크포인트·센서 하이브리드 최적화는 후속 증분(#199 B/C/D).
 * ============================================================================
 */
class WalkingMeasurementService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 측정 수명 동안 CPU 를 깨워 두는 partial wake lock. FGS 는 프로세스 우선순위만 올릴 뿐 SoC
     * suspend 를 막지 못하므로, 화면 OFF 로 기기가 suspend 되면 non-wake-up 가속도계 콜백이 끊기고
     * FIFO 초과분이 유실된다(10분 측정 걸음 누락). start~모든 종료 경로(stopMeasurement/onDestroy)에서
     * 획득·해제한다. reference-count 를 끄고 isHeld 로 가드해 중복 acquire/release 를 안전하게 만든다.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            notifySteps(WalkingMeasurement.currentSteps())
            handler.postDelayed(this, UPDATE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null // 바인드 안 함(홀더로 값 공유)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMeasurement()
        // 시스템이 죽여도 세션(홀더)이 사라진 상태에서 되살릴 의미가 없으므로 NOT_STICKY.
        // 종료는 companion stop()→stopService()→onDestroy() 경로로만 이뤄진다(ACTION_STOP 없음).
        return START_NOT_STICKY
    }

    private fun startMeasurement() {
        acquireWakeLock()
        createChannelIfNeeded()
        val notif = buildNotification(WalkingMeasurement.currentSteps())
        startAsForeground(notif)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, UPDATE_MS)
    }

    override fun onDestroy() {
        // 모든 종료 경로(stopService)가 여기로 수렴한다: 티커 정지 + WakeLock 해제.
        //   포그라운드 알림은 서비스 파괴 시 시스템이 자동 제거하므로 별도 stopForeground 불필요.
        handler.removeCallbacks(ticker)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
        // 세션이 비정상적으로 안 끝나도 무한 점유되지 않도록 넉넉한 안전 타임아웃을 둔다(정상 해제는 stop 경로).
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startAsForeground(notif: Notification) {
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            Build.VERSION.SDK_INT >= 30 ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            else ->
                startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "걷기 측정",
            NotificationManager.IMPORTANCE_LOW, // 소리·헤드업 없음(측정 지속 표시 전용)
        ).apply { description = "걷기 챌린지 측정 중 상태 알림" }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(steps: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("걷기 챌린지")
            .setContentText(walkingMeasurementNotificationText(steps))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun notifySteps(steps: Int) {
        // POST_NOTIFICATIONS 미허용(13+)이면 표시만 생략, 서비스·측정은 계속된다.
        //   Lint(MissingPermission)가 인정하는 명시적 권한 체크를 쓴다(areNotificationsEnabled 로는
        //   notify() 의 권한 요구를 만족한 것으로 보지 않음). 13 미만은 권한이 자동 부여라 그대로 게시.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(steps))
    }

    companion object {
        private const val ACTION_START = "com.aihealthcare.ah0404.mission.action.START_WALK_MEASURE"
        private const val CHANNEL_ID = "walking_measurement"
        private const val NOTIF_ID = 4199
        private const val UPDATE_MS = 1_000L
        private const val WAKE_LOCK_TAG = "ah0404:walking_measurement"
        // 걷기 챌린지는 10분 상한. 정상 해제는 stop 경로가 담당하고, 이 값은 누수 방지용 안전 상한이다.
        private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L

        /** 측정 시작 시 서비스를 포그라운드로 띄운다. */
        fun start(context: Context) {
            val intent = Intent(context, WalkingMeasurementService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 측정 종료/이탈 시 서비스를 내린다.
         *
         * `stopService()` 를 쓴다(과거 `startService(ACTION_STOP)` X). FGS 가 이미 내려간 뒤
         * 백그라운드에서 다시 호출되는 경로(측정 완료 → 홈 → 시스템의 Activity 파괴 →
         * onCleared → cancel → teardownService)가 실존하는데, Android 8+ 백그라운드 실행 제한상
         * `startService()` 는 이때 IllegalStateException/BackgroundServiceStartNotAllowed 을 던진다.
         * `stopService()` 는 그 제한이 없고, 이미 종료된 서비스에 불려도 무해하다(중복 cancel 안전).
         * 정리는 onDestroy(티커 정지·WakeLock 해제)가, 알림 제거는 서비스 파괴 시 시스템이 담당한다.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, WalkingMeasurementService::class.java))
        }
    }
}
