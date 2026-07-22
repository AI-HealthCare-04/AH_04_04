@file:Suppress("DEPRECATION") // LocalLifecycleOwner: SensorScreen 과 동일하게 ui.platform 버전 사용

package com.aihealthcare.ah0404.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * ============================================================================
 *  WaveformCaptureScreen : '보행 직후 앉기' 분리용 원시 3축 파형 수집 화면 (#131, 디버그 전용)
 * ============================================================================
 *
 *  라벨(정상 보행 / 보행 직후 앉기)과 착용 위치를 고르고 [녹화 시작]을 누른 뒤 실제로 걷고/앉으면,
 *  가속도계 원시 x/y/z 와 그 순간 감지기 상태(magnitude·필터값·상태·걸음 카운트 여부)를 한 행씩 버퍼에 쌓는다.
 *  [정지] 후 [저장 & 공유]로 CSV 를 내보낸다(WaveformExporter). 이 CSV 로 두 동작의 판별 특징을 탐색한다.
 *
 *  데이터 계약(리뷰 #150 반영):
 *   - **앉기 시작 마커**: WALK_THEN_SIT 수집 중 앉기 직전에 [앉기 시작]을 누르면 이후 샘플이 SITTING
 *     구간으로 기록된다 → 보행/앉기 경계(ground truth)를 정지 버튼이 아니라 실시간 마커로 남긴다.
 *   - **시간축은 SensorEvent.timestamp** 기준(첫 샘플=0). 콜백 도착 시각도 함께 남겨 지터를 진단한다.
 *   - **수집 메타**(trial_id·기기·착용 위치)를 매 행에 남겨 시험 간 x/y/z 비교를 가능하게 한다.
 *   - 백그라운드 진입(ON_PAUSE) 시 자동 정지 — 긴 공백이 한 파일에 섞이지 않게 한다.
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
    val placement = remember { mutableStateOf(PLACEMENTS.first().value) }
    val recording = remember { mutableStateOf(false) }
    val phase = remember { mutableStateOf(WaveformPhase.WALKING) }
    val sampleCount = remember { mutableIntStateOf(0) }
    val stepCount = remember { mutableIntStateOf(0) }
    val walking = remember { mutableStateOf(false) }
    val savedName = remember { mutableStateOf<String?>(null) }

    // 타임스탬프 기준선 — 첫 샘플에서 정한다(SensorEvent.timestamp=ns, 콜백=elapsedRealtime ms).
    val sensorBaseNs = remember { mutableLongStateOf(-1L) }
    val callbackBaseMs = remember { mutableLongStateOf(0L) }

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
                val tsNs = event.timestamp
                val cbMs = SystemClock.elapsedRealtime()
                // 첫 샘플을 기준선으로: 이후 두 시간축 모두 0 에서 출발 → 둘의 차이가 곧 지터 지표.
                if (sensorBaseNs.longValue < 0L) {
                    sensorBaseNs.longValue = tsNs
                    callbackBaseMs.longValue = cbMs
                }
                val sensorElapsed = (tsNs - sensorBaseNs.longValue) / 1_000_000L
                val callbackElapsed = cbMs - callbackBaseMs.longValue
                val magnitude = sqrt(x * x + y * y + z * z)
                // 감지기에 흘려 걸음 카운트 여부를 얻는다(오탐 지점 표시용). 생산 재현 위해 콜백 시각(cbMs)을 그대로 넘긴다.
                val counted = detector.processSample(x, y, z, cbMs)
                recorder.add(
                    WaveformSample(
                        sensorElapsedMs = sensorElapsed,
                        callbackElapsedMs = callbackElapsed,
                        x = x, y = y, z = z,
                        magnitude = magnitude,
                        filteredMagnitude = detector.filteredMagnitude,
                        state = detector.state,
                        count = detector.count,
                        stepCounted = counted,
                        phase = recorder.phase, // add 가 레코더 현재 phase 로 다시 고정(단일 소스)
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
                    Lifecycle.Event.ON_PAUSE -> {
                        sensorManager.unregisterListener(listener)
                        // 백그라운드 공백이 한 파일에 섞이지 않게 자동 정지(리뷰 #150).
                        if (recorder.isRecording) {
                            recorder.stop()
                            recording.value = false
                            Toast.makeText(
                                context, "백그라운드 진입 — 녹화를 자동 정지했습니다.", Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
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
            "라벨·착용 위치를 고르고 [녹화 시작] 후 걸으세요. '보행 직후 앉기'는 앉기 직전 [앉기 시작]을 눌러 " +
                "경계를 표시합니다. [정지] 후 CSV 를 저장·공유합니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )

        AigoSegmentedSelector(
            options = WaveformLabel.entries.map { SegmentOption(it, it.display) },
            selected = label.value,
            onSelect = { if (!recording.value) label.value = it }, // 녹화 중 라벨 변경 금지
            horizontal = true,
        )

        AigoSegmentedSelector(
            options = PLACEMENTS,
            selected = placement.value,
            onSelect = { if (!recording.value) placement.value = it }, // 녹화 중 위치 변경 금지
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
        if (recording.value) {
            Text(
                text = if (phase.value == WaveformPhase.SITTING) "구간: 앉기(SITTING)" else "구간: 보행(WALKING)",
                fontSize = 14.sp,
                color = if (phase.value == WaveformPhase.SITTING) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (!recording.value) {
            AigoPrimaryButton(
                text = "● 녹화 시작 (${label.value.display})",
                onClick = {
                    detector.reset()
                    sensorBaseNs.longValue = -1L // 첫 샘플에서 기준선 재설정
                    recorder.start(label.value, newMeta(label.value, placement.value))
                    recording.value = true
                    phase.value = WaveformPhase.WALKING
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

        // 보행 직후 앉기 수집에서만, 앉기 구간 전이 전에 노출되는 ground-truth 마커.
        if (recording.value &&
            label.value == WaveformLabel.WALK_THEN_SIT &&
            phase.value == WaveformPhase.WALKING
        ) {
            AigoSecondaryButton(
                text = "▼ 지금 앉기 시작 표시",
                onClick = {
                    recorder.markSitting()
                    phase.value = WaveformPhase.SITTING
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

/** 착용 위치·방향 선택지 — 서로 다른 시험의 x/y/z 를 비교하려면 위치가 명시돼야 한다(센서 좌표계는 기기 자연방향 기준). */
private val PLACEMENTS = listOf(
    SegmentOption("front_pocket", "앞주머니"),
    SegmentOption("back_pocket", "뒷주머니"),
    SegmentOption("hand", "손"),
    SegmentOption("bag", "가방"),
)

/**
 * 이번 수집의 메타를 만든다. trial_id 는 라벨+ms 정밀 타임스탬프라 빠른 반복 수집에서도 유일하며,
 * 그대로 파일명이 되어 파일 덮어쓰기를 막는다(리뷰 #150).
 */
private fun newMeta(label: WaveformLabel, placement: String): CaptureMeta {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return CaptureMeta(
        trialId = "${label.id}_$stamp",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        placement = placement,
    )
}
