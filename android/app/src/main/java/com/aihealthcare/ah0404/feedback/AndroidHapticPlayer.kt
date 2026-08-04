package com.aihealthcare.ah0404.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 플랫폼 `Vibrator` 기반 [HapticPlayer] 구현.
 *
 *  `VIBRATE` 권한이 필요하지만 normal 등급이라 런타임 요청 없이 매니페스트 선언만으로 동작한다.
 *  진동 장치가 없는 기기에서는 조용히 무시한다([TtsSpeaker] 와 같은 graceful degrade).
 *
 *  버전 분기를 여기 한 곳에 모아, 기능별 코드가 `VibratorManager`/`VibrationEffect`/레거시를
 *  각각 다루지 않게 한다.
 */
class AndroidHapticPlayer(context: Context) : HapticPlayer {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)

    override var isEnabled: Boolean = true

    override fun play(pattern: HapticPattern) {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        vibrate(vib, pattern.timings())
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    /**
     * 패턴별 타이밍(ms). `[대기, 진동, 대기, 진동, …]` 형식이라 항상 짝수 길이다.
     * 시니어 대상이라 전반적으로 길게 잡아 인지하기 쉽게 했다.
     */
    private fun HapticPattern.timings(): LongArray = when (this) {
        HapticPattern.LIGHT -> longArrayOf(0, 40)
        HapticPattern.CONFIRMATION -> longArrayOf(0, 120)
        HapticPattern.TRANSITION -> longArrayOf(0, 80, 120, 80)
        HapticPattern.SUCCESS -> longArrayOf(0, 180, 150, 180)
        HapticPattern.WARNING -> longArrayOf(0, 250, 120, 250, 120, 250)
    }

    private fun vibrate(vib: Vibrator, timings: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // -1 = 반복 없음
            vib.vibrate(VibrationEffect.createWaveform(timings, -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(timings, -1)
        }
    }

    private companion object {
        fun resolveVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
