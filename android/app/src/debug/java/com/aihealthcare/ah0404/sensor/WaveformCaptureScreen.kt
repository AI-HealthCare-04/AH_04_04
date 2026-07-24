@file:Suppress("DEPRECATION") // LocalLifecycleOwner: SensorScreen 과 동일하게 ui.platform 버전 사용

package com.aihealthcare.ah0404.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.SegmentOption
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * ============================================================================
 *  WaveformCaptureScreen : '보행 직후 앉기' 분리용 원시 3축 파형 수집 화면 (#131, 디버그 전용)
 * ============================================================================
 *
 *  라벨(정상 보행 / 보행 직후 앉기 / 서서 앉기만 / 제자리 발끌기)과 착용 규격을 고르고 [녹화 시작]을 누른 뒤 실제로 걷고/앉으면,
 *  가속도계 원시 x/y/z 와 그 순간 감지기 상태(magnitude·필터값·상태·걸음 카운트 여부)를 한 행씩 버퍼에 쌓는다.
 *  [정지] 후 [저장 & 공유]로 CSV 를 내보낸다(WaveformExporter). 이 CSV 로 두 동작의 판별 특징을 탐색한다.
 *
 *  데이터 계약(리뷰 #150 반영):
 *   - **기기 무접촉 자동 큐**: WALK_THEN_SIT 은 시작 후 정해진 시간(WALK_SECONDS)만큼 걷다가 자동 카운트다운이
 *     끝나면 **비프음**으로 앉기 신호를 낸다. 그 순간 recorder.markSitting() 이 event=sit_cue 마커를 CSV 에
 *     찍는다 → **화면 터치 없이** 보행/앉기 경계(ground truth)를 기록해, 주머니/가방 배치에서도 파형이
 *     오염되지 않는다(Earthworm-jk 블로커1). 비프는 오디오라 가속도계 영향이 작고, 큐 직후 짧은 구간은
 *     excluded 로 표시돼 학습에서 뺄 수 있다.
 *   - **구조화 placement 프리셋**: 위치+좌/우+화면방향+상단방향+접힘까지 고정 규격으로 골라, 매 행에 남긴다
 *     (Earthworm-jk 블로커2). 선택 규격의 배치 안내를 화면에 그대로 띄운다.
 *   - **시간축은 SensorEvent.timestamp** 기준(첫 샘플=0). 콜백 도착 시각도 함께 남겨 지터를 진단.
 *   - 백그라운드(ON_PAUSE) 진입 시 자동 정지 + 버퍼 상한 도달 시 자동 정지(수집분 손실 방지).
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
    val toneGen = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val label = remember { mutableStateOf(WaveformLabel.NORMAL_WALK) }
    val preset = remember { mutableStateOf(PLACEMENT_PRESETS.first()) }
    val recording = remember { mutableStateOf(false) }
    val phase = remember { mutableStateOf(WaveformPhase.WALKING) }
    val cueCountdown = remember { mutableIntStateOf(0) } // WALK_THEN_SIT 앉기 큐까지 남은 초
    val sampleCount = remember { mutableIntStateOf(0) }
    val stepCount = remember { mutableIntStateOf(0) }
    val walking = remember { mutableStateOf(false) }
    val savedName = remember { mutableStateOf<String?>(null) }

    // 타임스탬프 기준선 — 첫 샘플에서 정한다(SensorEvent.timestamp=ns, 콜백=elapsedRealtime ms).
    val sensorBaseNs = remember { mutableLongStateOf(-1L) }
    val callbackBaseMs = remember { mutableLongStateOf(0L) }

    // HW 만보기 하이브리드 비교(#184/#176): TYPE_STEP_COUNTER 누적(녹화 시작 기준 0)·TYPE_STEP_DETECTOR 이벤트.
    val hwCounterBase = remember { mutableLongStateOf(-1L) }   // 녹화 시작 시 첫 누적값
    val hwCounterLatest = remember { mutableLongStateOf(-1L) }
    val hwPendingDetector = remember { mutableIntStateOf(0) }  // 직전 accel 샘플 이후 감지된 스텝 이벤트 수

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val stepCounterSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    val stepDetectorSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }

    // TYPE_STEP_* 는 ACTIVITY_RECOGNITION(위험 권한, API29+) 없이는 이벤트가 오지 않는다. 화면 진입 시 요청.
    //   거부돼도 hw_* 컬럼이 0 으로 남을 뿐 수집 자체는 정상(그레이스풀 열화).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context, "활동 인식 권한 없음 — HW 만보기 컬럼은 0 으로 기록됩니다(가속도 수집은 정상).",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    val listener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                // HW 만보기 두 센서는 같은 리스너로 받아 최신값/이벤트만 갱신하고 반환(가속도 샘플에서 함께 기록).
                when (event.sensor?.type) {
                    Sensor.TYPE_STEP_COUNTER -> {
                        if (recorder.isRecording) {
                            val v = event.values[0].toLong() // 부팅 이후 누적
                            if (hwCounterBase.longValue < 0L) hwCounterBase.longValue = v
                            hwCounterLatest.longValue = v
                        }
                        return
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        if (recorder.isRecording) hwPendingDetector.intValue += 1
                        return
                    }
                }
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
                // HW 만보기: 녹화 시작 기준 누적 + 직전 accel 이후 감지된 스텝 이벤트(소비 후 리셋).
                val hwCounter = if (hwCounterBase.longValue >= 0L) {
                    (hwCounterLatest.longValue - hwCounterBase.longValue).toInt()
                } else {
                    0
                }
                val hwDetected = hwPendingDetector.intValue > 0
                hwPendingDetector.intValue = 0
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
                        hwStepCounter = hwCounter,
                        hwStepDetected = hwDetected,
                        // phase/event/excluded 는 recorder.add 가 큐 상태로 다시 고정한다(단일 소스).
                    ),
                )
                sampleCount.intValue = recorder.sampleCount
                stepCount.intValue = detector.count
                walking.value = detector.state == WalkingStepDetectorLogic.State.WALKING
                // 버퍼 상한 도달 → recorder 가 자동 정지했으면 화면도 정지 반영(수집분 손실 방지).
                if (recorder.stoppedByCap && recording.value) {
                    recording.value = false
                    Toast.makeText(
                        context, "샘플 상한 도달 — 자동 정지했습니다. 저장하세요.", Toast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // 기기 무접촉 고정 프로토콜: 시작 후 화면을 건드리지 않아도 자동 큐·자동 종료까지 진행된다.
    //   앉기 큐 있는 라벨(hasSitCue): WALK_SECONDS 활동(보행/서있기) → 비프 큐(전달 검증) → markSitting →
    //     SIT_SECONDS 앉기 → 자동 정지.
    //   큐 없는 라벨            : 동일 총 길이(WALK_SECONDS+SIT_SECONDS) 활동(보행/제자리) → 자동 정지.
    // 종료도 자동이라, 주머니 배치에서 휴대폰을 꺼내는 조작이 수집 구간에 섞이지 않는다(Earthworm-jk 블로커).
    LaunchedEffect(recording.value) {
        if (!recording.value) return@LaunchedEffect
        val autoStop = {
            recorder.stop()
            recording.value = false
            Toast.makeText(context, "수집 완료 — 저장하세요.", Toast.LENGTH_LONG).show()
        }
        if (label.value.hasSitCue) {
            countdown(cueCountdown, WALK_SECONDS)
            // 비프 전달 검증: 미디어 음량>0 && startTone 성공이어야 실제로 들린 것. 실패면 markSitting 하지 않고 중단.
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val started = runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
                .getOrDefault(false)
            if (volume <= 0 || !started) {
                recorder.stop()
                recording.value = false
                Toast.makeText(
                    context,
                    "비프 전달 실패(음량 $volume) — 녹화를 중단합니다. 미디어 음량을 올리고 [🔊 비프 테스트]로 확인하세요.",
                    Toast.LENGTH_LONG,
                ).show()
                return@LaunchedEffect
            }
            // 큐 전달 확인됨 → 무접촉 마커. 큐 직후 짧은 구간은 recorder 가 excluded 처리.
            recorder.markSitting()
            phase.value = WaveformPhase.SITTING
            countdown(cueCountdown, SIT_SECONDS) // 앉기 구간 수집
            if (recording.value) autoStop()
        } else {
            countdown(cueCountdown, WALK_SECONDS + SIT_SECONDS)
            if (recording.value) autoStop()
        }
    }

    // 가속도 + HW 만보기 두 센서를 같은 리스너로 등록/해제한다(HW 는 있을 때만).
    val registerSensors = {
        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        stepCounterSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        stepDetectorSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }
    DisposableEffect(lifecycleOwner) {
        var observer: LifecycleEventObserver? = null
        if (accelSensor != null) {
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> registerSensors()
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
                registerSensors()
            }
        }
        onDispose {
            observer?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            sensorManager.unregisterListener(listener)
        }
    }

    DisposableEffect(Unit) {
        onDispose { toneGen.release() }
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
            "라벨·착용 규격을 고르고 안내대로 휴대폰을 두세요. 앉기 큐가 있는 라벨(보행 직후 앉기·서서 앉기만)은 " +
                "[녹화 시작] 후 ${WALK_SECONDS}초 뒤 **비프음이 울리면** 그때 앉으면 됩니다(화면 조작 없음). " +
                "[정지] 후 CSV 를 저장·공유합니다.",
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
            options = PLACEMENT_PRESETS.map { SegmentOption(it, it.chip) },
            selected = preset.value,
            onSelect = { if (!recording.value) preset.value = it }, // 녹화 중 규격 변경 금지
            horizontal = true,
        )
        Text(
            "배치: ${preset.value.instruction}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
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
            val isSit = phase.value == WaveformPhase.SITTING
            Text(
                text = when {
                    label.value.hasSitCue && isSit ->
                        "🔔 앉기 신호! 지금 앉으세요 — ${cueCountdown.intValue}초 후 자동 종료"
                    label.value.hasSitCue ->
                        "앉기 신호까지 ${cueCountdown.intValue}초 — ${label.value.actionHint}"
                    else ->
                        "수집 종료까지 ${cueCountdown.intValue}초 — ${label.value.actionHint}"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSit) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (!recording.value) {
            // 녹화 전 비프 점검 — 큐가 실제 들리는지(음량>0 + startTone 성공) 미리 확인.
            AigoSecondaryButton(
                text = "🔊 비프 테스트",
                onClick = {
                    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (volume <= 0) {
                        Toast.makeText(
                            context, "미디어 음량이 0 입니다 — 볼륨을 올리세요.", Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        val ok = runCatching { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
                            .getOrDefault(false)
                        Toast.makeText(
                            context,
                            if (ok) "비프 정상 — 이 소리가 들리면 준비 완료(음량 $volume)."
                            else "비프 실패 — 다시 시도하세요.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            AigoPrimaryButton(
                text = "● 녹화 시작 (${label.value.display})",
                onClick = {
                    detector.reset()
                    sensorBaseNs.longValue = -1L // 첫 샘플에서 기준선 재설정
                    hwCounterBase.longValue = -1L // HW 만보기 누적도 이번 녹화 기준 0 으로
                    hwCounterLatest.longValue = -1L
                    hwPendingDetector.intValue = 0
                    recorder.start(label.value, newMeta(label.value, preset.value.spec))
                    recording.value = true
                    phase.value = WaveformPhase.WALKING
                    cueCountdown.intValue =
                        if (label.value.hasSitCue) WALK_SECONDS else WALK_SECONDS + SIT_SECONDS
                    sampleCount.intValue = 0
                    stepCount.intValue = 0
                    walking.value = false
                    savedName.value = null
                },
            )
        } else {
            // 정상 흐름은 자동 종료. 이 버튼은 중단용(누르면 화면 조작이 섞이므로 그 파일은 폐기 권장).
            AigoPrimaryButton(
                text = "■ 중단(정지)",
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

/**
 * 녹화 시작 후 앉기 큐(비프)까지의 큐 전 활동 시간(초). 라벨별로 보행/서있기/제자리 등 활동만 다르고
 * 길이는 공통이라, 서로 다른 시나리오의 파형을 같은 시간 축에서 비교할 수 있다.
 * (SIT_ONLY 에서는 이 구간이 앉기 전 '서있기 기준선'이 된다.)
 */
private const val WALK_SECONDS = 15

/** 앉기 큐 이후 수집하는 시간(초) — 이 시간 뒤 자동 정지. 큐 없는 라벨은 WALK_SECONDS+SIT_SECONDS 총길이. */
private const val SIT_SECONDS = 5

/** 1초 단위 카운트다운(초 단위 표시 갱신). 코루틴 취소(정지·pause) 시 즉시 중단된다. */
private suspend fun countdown(state: MutableIntState, seconds: Int) {
    var remaining = seconds
    while (remaining > 0) {
        state.intValue = remaining
        delay(1000)
        remaining--
    }
    state.intValue = 0
}

/**
 * 착용 규격 프리셋 — 각 프리셋은 위치+좌/우+화면방향+상단방향+접힘까지 고정한 PlacementSpec 과
 * 화면 안내 문구를 함께 갖는다. 서로 다른 시험의 x/y/z 를 비교하려면 이 규격이 명시·고정돼야 한다.
 */
private data class PlacementPreset(val spec: PlacementSpec, val chip: String, val instruction: String)

private val PLACEMENT_PRESETS = listOf(
    PlacementPreset(
        PlacementSpec("front_pocket_r_in", "front_pocket", "right", "in", "up", "folded"),
        "앞주머니(안)",
        "오른쪽 앞주머니 · 화면 몸쪽 · 상단 위 · 접은 상태",
    ),
    PlacementPreset(
        PlacementSpec("front_pocket_r_out", "front_pocket", "right", "out", "up", "folded"),
        "앞주머니(밖)",
        "오른쪽 앞주머니 · 화면 바깥쪽 · 상단 위 · 접은 상태",
    ),
    PlacementPreset(
        PlacementSpec("hand_portrait", "hand", "na", "user", "up", "unfolded"),
        "손(세로)",
        "손에 세로로 들기 · 화면 사용자쪽 · 상단 위 · 펼친 상태",
    ),
)

/**
 * 이번 수집의 메타를 만든다. trial_id 는 라벨+ms 정밀 타임스탬프라 빠른 반복 수집에서도 유일하며,
 * 그대로 파일명이 되어 파일 덮어쓰기를 막는다(리뷰 #150).
 */
private fun newMeta(label: WaveformLabel, placement: PlacementSpec): CaptureMeta {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return CaptureMeta(
        trialId = "${label.id}_$stamp",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        placement = placement,
        // 앉기 큐 라벨은 큐 전달 확인 시 markSitting 이 success 로 갱신. 큐 전 중단이면 pending 으로 남아 걸러진다.
        cueDelivery = if (label.hasSitCue) "pending" else "na",
    )
}
