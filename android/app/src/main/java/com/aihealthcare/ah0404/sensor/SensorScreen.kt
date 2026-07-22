@file:Suppress("DEPRECATION") // LocalLifecycleOwner: lifecycle-runtime-compose 미추가로 ui.platform 버전 사용

package com.aihealthcare.ah0404.sensor

import android.content.Context
import android.content.Intent
import com.aihealthcare.ah0404.pet.WalkingChallengeActivity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.aihealthcare.ah0404.pet.PetIdle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlin.math.sqrt

@Composable
fun SensorScreen(modifier: Modifier = Modifier) {
    val selectedTab = remember { mutableStateOf(0) }
    val tabs = listOf("걷기 만보기", "흔들기 운동")

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab.value) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab.value == index,
                    onClick = { selectedTab.value = index },
                    text = { Text(title, fontSize = 16.sp) }
                )
            }
        }
        when (selectedTab.value) {
            0 -> StepCounterSection()
            1 -> ShakeSection()
        }
    }
}

// ─────────────────────────────────────────────
// 1. 걷기 만보기 (가속도계 2단계 게이팅)
// 1단계: 규칙적인 피크가 연속으로 감지되면 "보행 중" 판정
// 2단계: 보행 중일 때만 걸음 카운트
// → 앉기/방향전환 같은 일회성 동작은 카운트되지 않음
// ─────────────────────────────────────────────

@Composable
fun StepCounterSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val walkLogic = remember { WalkingStepDetectorLogic() }
    val stepCount = remember { mutableStateOf(0) }
    val rawMag = remember { mutableStateOf(0f) }
    val filteredMag = remember { mutableStateOf(9.8f) }
    val walkState = remember { mutableStateOf(WalkingStepDetectorLogic.State.IDLE) }
    val consecutivePeaks = remember { mutableStateOf(0) }

    // 측정 시간·간격 표시(A-4a): "몇 초에 몇 보"를 함께 기록하고, 걸음 내부 이중봉우리를 진단하기 위함.
    val walkSpanMs = remember { mutableStateOf(0L) }
    val lastIntervalMs = remember { mutableStateOf(0L) }
    val cadence = remember { mutableStateOf(0) }

    // 측정 초기화(카운트·상태·화면) — "리셋(0부터 다시 세기)" 버튼에서 사용.
    val resetMeasurement = {
        walkLogic.reset()
        stepCount.value = 0
        walkState.value = WalkingStepDetectorLogic.State.IDLE
        consecutivePeaks.value = 0
        walkSpanMs.value = 0L
        lastIntervalMs.value = 0L
        cadence.value = 0
    }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    val stepListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                rawMag.value = sqrt(x * x + y * y + z * z)
                if (walkLogic.processSample(x, y, z, System.currentTimeMillis())) {
                    stepCount.value = walkLogic.count
                }
                filteredMag.value = walkLogic.filteredMagnitude
                walkState.value = walkLogic.state
                consecutivePeaks.value = walkLogic.consecutivePeaks
                walkSpanMs.value = walkLogic.walkingSpanMs
                lastIntervalMs.value = walkLogic.lastIntervalMs
                cadence.value = walkLogic.cadenceStepsPerMin
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(lifecycleOwner) {
        var observer: LifecycleEventObserver? = null
        if (accelSensor != null) {
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME ->
                        sensorManager.registerListener(
                            stepListener, accelSensor, SensorManager.SENSOR_DELAY_GAME
                        )
                    Lifecycle.Event.ON_PAUSE ->
                        sensorManager.unregisterListener(stepListener)
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                sensorManager.registerListener(
                    stepListener, accelSensor, SensorManager.SENSOR_DELAY_GAME
                )
            }
        }
        onDispose {
            observer?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            sensorManager.unregisterListener(stepListener)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (accelSensor == null) {
            Spacer(Modifier.height(40.dp))
            Text(
                "이 기기는 가속도 센서를 지원하지 않습니다.",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Spacer(Modifier.height(16.dp))
            // 현재 보행 상태 배지
            val walking = walkState.value == WalkingStepDetectorLogic.State.WALKING
            Text(
                text = if (walking) "🚶 걷는 중" else "⏸ 멈춤",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (walking)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            Text("이번 세션 걸음 수", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "${stepCount.value}",
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 96.sp
            )
            Text("걸음", fontSize = 22.sp, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(4.dp))
            // 측정 시간·케이던스 — "몇 초에 몇 보"를 함께 기록하기 위한 표시(A-4a).
            //   경과 = 첫 걸음~마지막 걸음 구간. 평균 보/분 = 감지기가 (걸음-1) 간격으로 환산(순수 로직).
            val spanSec = walkSpanMs.value / 1000f
            Text(
                text = "경과 %.1f초 · 평균 %d보/분".format(spanSec, cadence.value),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
            AigoSecondaryButton(
                text = "리셋 (0부터 다시 세기)",
                onClick = resetMeasurement,
            )
            AigoPrimaryButton(
                text = "🐶 강아지와 산책하기",
                onClick = {
                    context.startActivity(Intent(context, WalkingChallengeActivity::class.java))
                },
            )
            DebugPanel {
                DebugRow("원시 크기", "%.2f m/s²".format(rawMag.value))
                DebugRow("필터된 크기 (알고리즘 입력)", "%.2f m/s²".format(filteredMag.value))
                DebugRow("현재 상태", if (walking) "WALKING" else "IDLE")
                DebugRow("연속 규칙 피크", "${consecutivePeaks.value} / ${walkLogic.peaksToStartWalking} (보행 진입 기준)")
                DebugRow("마지막 피크 간격", "${lastIntervalMs.value} ms (걸음내 이중봉우리 진단)")
            }
            // A-4a 런타임 튜닝 패널(TuneRow)은 실측으로 상수가 확정돼(#89, MIN=350/MAX=1000/THRESHOLD=10.5)
            //   제거했다(#133). 감지 파라미터는 WalkingStepDetectorLogic 의 DEFAULT_* 로 고정된다.
        }
    }
        // 배경 없는(투명) 마스코트 강아지 — 다른 화면으로 옮기려면 이 한 줄만 이동
        PetIdle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(200.dp)
        )
    }
}

// ─────────────────────────────────────────────
// 2. 흔들기 운동 감지
// ─────────────────────────────────────────────

@Composable
fun ShakeSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val shakeLogic = remember { ShakeDetectorLogic() }
    val shakeCount = remember { mutableStateOf(0) }
    val accelX = remember { mutableStateOf(0f) }
    val accelY = remember { mutableStateOf(0f) }
    val accelZ = remember { mutableStateOf(0f) }
    val magnitude = remember { mutableStateOf(0f) }

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    val accelListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                accelX.value = x
                accelY.value = y
                accelZ.value = z
                magnitude.value = sqrt(x * x + y * y + z * z)
                if (shakeLogic.processSample(x, y, z, System.currentTimeMillis())) {
                    shakeCount.value = shakeLogic.count
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(lifecycleOwner) {
        var observer: LifecycleEventObserver? = null
        if (accelSensor != null) {
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME ->
                        sensorManager.registerListener(
                            accelListener, accelSensor, SensorManager.SENSOR_DELAY_GAME
                        )
                    Lifecycle.Event.ON_PAUSE ->
                        sensorManager.unregisterListener(accelListener)
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                sensorManager.registerListener(
                    accelListener, accelSensor, SensorManager.SENSOR_DELAY_GAME
                )
            }
        }
        onDispose {
            observer?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            sensorManager.unregisterListener(accelListener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (accelSensor == null) {
            Spacer(Modifier.height(40.dp))
            Text(
                "이 기기는 가속도 센서를 지원하지 않습니다.",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Spacer(Modifier.height(16.dp))
            Text("흔들기 횟수", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "${shakeCount.value}",
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 96.sp
            )
            Text("회", fontSize = 22.sp, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            AigoSecondaryButton(
                text = "리셋 (0부터 다시 세기)",
                onClick = {
                    shakeLogic.reset()
                    shakeCount.value = 0
                },
            )
            DebugPanel {
                DebugRow("X축", "%.2f m/s²".format(accelX.value))
                DebugRow("Y축", "%.2f m/s²".format(accelY.value))
                DebugRow("Z축", "%.2f m/s²".format(accelZ.value))
                DebugRow("크기 (√x²+y²+z²)", "%.2f m/s²".format(magnitude.value))
                DebugRow("감지 임계값", "%.1f m/s² (초과 시 카운트)".format(ShakeDetectorLogic.SHAKE_THRESHOLD))
                DebugRow("최소 간격", "${ShakeDetectorLogic.MIN_SHAKE_INTERVAL_MS} ms")
            }
        }
    }
}

// ─────────────────────────────────────────────
// 공통 디버그 패널 컴포넌트
// ─────────────────────────────────────────────

@Composable
private fun DebugPanel(content: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    Text(
        "[디버그] 실시간 센서 값",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.outline
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
