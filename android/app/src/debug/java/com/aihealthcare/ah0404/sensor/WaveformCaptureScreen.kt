@file:Suppress("DEPRECATION") // LocalLifecycleOwner: SensorScreen 과 동일하게 ui.platform 버전 사용

package com.aihealthcare.ah0404.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.SegmentOption
import kotlin.math.sqrt

/**
 * ============================================================================
 *  WaveformCaptureScreen : '보행 직후 앉기' 분리용 원시 3축 파형 수집 화면 (#131, 디버그 전용)
 * ============================================================================
 *
 *  라벨(정상 보행 / 보행 직후 앉기)을 고르고 [녹화 시작]을 누른 뒤 실제로 걷고/앉으면, 가속도계
 *  원시 x/y/z 와 그 순간 감지기 상태(magnitude·필터값·상태·걸음 카운트 여부)를 한 행씩 버퍼에 쌓는다.
 *  [정지] 후 [저장 & 공유]로 CSV 를 내보낸다(WaveformExporter). 이 CSV 로 두 동작의 판별 특징을 탐색한다.
 *
 *  ⚠️ 감지기(WalkingStepDetectorLogic)는 **읽기 전용으로만** 흘려보낸다 — 여기서 로직/임계값을 바꾸지 않는다.
 *     프로덕션 측정 경로(WalkingSession)와 무관한 독립 수집 화면이라 실사용 흐름에 영향이 없다.
 *  ⚠️ 릴리스 APK 에는 포함되지 않는다(debug 소스셋). 진입도 설정 화면의 BuildConfig.DEBUG 게이트로만 노출.
 * ============================================================================
 */
@Composable
fun WaveformCaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val detector = remember { WalkingStepDetectorLogic() }
    val recorder = remember { WaveformRecorder() }

    val label = remember { mutableStateOf(WaveformLabel.NORMAL_WALK) }
    val recording = remember { mutableStateOf(false) }
    val sampleCount = remember { mutableIntStateOf(0) }
    val stepCount = remember { mutableIntStateOf(0) }
    val walking = remember { mutableStateOf(false) }
    val startMs = remember { mutableLongStateOf(0L) }
    val savedName = remember { mutableStateOf<String?>(null) }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    val listener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                if (!recorder.isRecording) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val nowMs = SystemClock.elapsedRealtime()
                val magnitude = sqrt(x * x + y * y + z * z)
                // 감지기에 흘려 걸음 카운트 여부를 얻는다(오탐 지점 표시용). 로직은 변경하지 않는다.
                val counted = detector.processSample(x, y, z, nowMs)
                recorder.add(
                    WaveformSample(
                        elapsedMs = nowMs - startMs.longValue,
                        x = x, y = y, z = z,
                        magnitude = magnitude,
                        filteredMagnitude = detector.filteredMagnitude,
                        state = detector.state,
                        count = detector.count,
                        stepCounted = counted,
                    ),
                )
                sampleCount.intValue = recorder.sampleCount
                stepCount.intValue = detector.count
                walking.value = detector.state == WalkingStepDetectorLogic.State.WALKING
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(lifecycleOwner) {
        var observer: LifecycleEventObserver? = null
        if (accelSensor != null) {
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> sensorManager.registerListener(
                        listener, accelSensor, SensorManager.SENSOR_DELAY_GAME,
                    )
                    Lifecycle.Event.ON_PAUSE -> sensorManager.unregisterListener(listener)
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                sensorManager.registerListener(
                    listener, accelSensor, SensorManager.SENSOR_DELAY_GAME,
                )
            }
        }
        onDispose {
            observer?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            sensorManager.unregisterListener(listener)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("파형 수집 (디버그 · #131)", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        if (accelSensor == null) {
            Text(
                "이 기기는 가속도 센서를 지원하지 않습니다.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        Text(
            "라벨을 고르고 [녹화 시작] 후 실제로 걷거나 걷다 앉으세요. [정지] 후 CSV 를 저장·공유합니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )

        AigoSegmentedSelector(
            options = WaveformLabel.entries.map { SegmentOption(it, it.display) },
            selected = label.value,
            onSelect = { if (!recording.value) label.value = it }, // 녹화 중 라벨 변경 금지
            horizontal = true,
        )

        Text(
            text = if (walking.value) "🚶 걷는 중" else "⏸ 멈춤",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (walking.value) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        )
        Text("걸음 ${stepCount.intValue} · 샘플 ${sampleCount.intValue}개", fontSize = 18.sp)

        Spacer(Modifier.height(4.dp))

        if (!recording.value) {
            AigoPrimaryButton(
                text = "● 녹화 시작 (${label.value.display})",
                onClick = {
                    detector.reset()
                    startMs.longValue = SystemClock.elapsedRealtime()
                    recorder.start(label.value)
                    recording.value = true
                    sampleCount.intValue = 0
                    stepCount.intValue = 0
                    walking.value = false
                    savedName.value = null
                },
            )
        } else {
            AigoPrimaryButton(
                text = "■ 정지",
                onClick = {
                    recorder.stop()
                    recording.value = false
                },
            )
        }

        AigoSecondaryButton(
            text = "저장 & 공유 (CSV)",
            enabled = !recording.value && sampleCount.intValue > 0,
            onClick = {
                val file = WaveformExporter.save(context, recorder)
                savedName.value = file.name
                Toast.makeText(context, "저장됨: ${file.name}", Toast.LENGTH_SHORT).show()
                WaveformExporter.share(context, file)
            },
        )

        savedName.value?.let { name ->
            HorizontalDivider()
            Text(
                "마지막 저장: $name",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "위치: Android/data/${context.packageName}/files/waveforms/",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
