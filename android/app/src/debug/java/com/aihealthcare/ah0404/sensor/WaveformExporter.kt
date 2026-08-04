package com.aihealthcare.ah0404.sensor

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 수집한 파형을 CSV 파일로 저장하고 공유(다른 앱/기기로 내보내기)한다. **디버그 소스셋 전용** — 릴리스 APK 에는 포함되지 않는다.
 *
 * 저장 위치는 앱 전용 외부 저장소(`getExternalFilesDir`)라 별도 저장소 권한이 필요 없고,
 * Android Studio Device Explorer 나 adb pull 로도 바로 꺼낼 수 있다. 공유는 FileProvider content URI 로 한다.
 */
object WaveformExporter {

    /**
     * 레코더 버퍼를 `waveforms/{trialId}.csv` 로 저장하고 그 File 을 반환한다.
     * 파일명이 곧 trial_id(라벨+ms 정밀 타임스탬프)라 빠른 반복 수집에서도 덮어써지지 않는다(리뷰 #150).
     */
    fun save(context: Context, recorder: WaveformRecorder): File {
        val dir = File(context.getExternalFilesDir(null), "waveforms").apply { mkdirs() }
        val file = File(dir, "${recorder.meta.trialId}.csv")
        file.writeText(recorder.toCsv())
        return file
    }

    /** 저장된 CSV 를 공유 시트로 내보낸다(메일·메신저·드라이브 등). */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "파형 CSV 공유").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
