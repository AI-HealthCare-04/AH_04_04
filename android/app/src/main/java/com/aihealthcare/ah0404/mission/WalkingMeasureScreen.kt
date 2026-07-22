@file:Suppress("DEPRECATION") // LocalLifecycleOwner: lifecycle-runtime-compose 미추가로 ui.platform 버전 사용

package com.aihealthcare.ah0404.mission

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.pet.PetWalkingView
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoHeroCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.WalkSitGuidanceNote
import com.aihealthcare.ah0404.ui.theme.Dimens
import kotlinx.coroutines.delay

/** 측정 중 화면 갱신 주기(ms). 걸음/경과/상태를 세션에서 읽어온다. */
private const val POLL_MS = 300L

/**
 * ============================================================================
 *  WalkingMeasureScreen : 걷기 미션 측정 화면 (#90 A-4b)
 * ============================================================================
 *
 *  걸음 수·경과 시간·산책 영상이 하나의 세션(WalkingSessionViewModel)을 공유한다(산정 지점 1곳).
 *  보행 확정 전에는 걸음 수 대신 '측정 중…'을 보여주고(잠정 표시), 확정되면 소급 카운트를 노출한다.
 *
 *  🟡 #132 안내(보행 직후 곧바로 앉기 과다카운트 완화)가 실제 사용자에게 노출되는 첫 화면이다
 *     — 측정 중 걸음 수 아래에 WalkSitGuidanceNote 를 배치한다.
 *
 *  생명주기 복원(#90 2단계):
 *   - 구성 변경(회전·폰트 크기 변경 등): VM 을 Activity ViewModelStore 에 두어(viewModel())
 *     세션(걸음·활성시간)을 그대로 유지. 진입 상태(어느 미션인지)는 MainActivity 의
 *     rememberSaveable 이 보존한다.
 *   - 백그라운드/전화: onPause 로 센서·영상 정지 + 경과 시계 동결(드리프트 제거), onResume 자동 재개.
 *   - 화면 이탈(뒤로/완료 후 확인): leave() 로 세션 리셋(센서 해제 + 다음 진입 stale 방지).
 *   - 프로세스 종료 후 측정값 복원은 범위 밖(#91/#105) — 재생성 시 READY 부터 시작.
 * ============================================================================
 */
@UnstableApi
@Composable
fun WalkingMeasureScreen(
    mission: Mission,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // VM 을 Activity ViewModelStore 에 둔다(viewModel()) → 구성 변경(회전 등)에 세션이 생존한다.
    // 오버레이는 자체 ViewModelStoreOwner 가 없어 Activity 에 바인딩되므로, 화면을 떠날 때
    // leave() 가 명시적으로 reset() 을 호출해 (1) 센서 해제 (2) 재진입 시 이전 세션 상태 부활을 막는다.
    val vm: WalkingSessionViewModel = viewModel {
        WalkingSessionViewModel(WalkingSession(context.applicationContext))
    }
    val ui = vm.uiState

    // 진동·음성 피드백(#92) — 화면을 보지 않아도 진행을 알 수 있게 핵심 순간에만 신호를 낸다.
    // tracker(순수)가 "언제" 를, feedback(Android)이 "어떻게(진동/TTS)" 를 담당. 둘 다 화면 수명.
    val feedback: WalkingFeedback = remember { AndroidWalkingFeedback(context) }
    val tracker = remember { WalkingFeedbackTracker() }
    // 목표 신호는 단위가 걸음일 때만(그 외 걷기 목표는 목표 도달 신호 없음).
    val goalSteps = mission.targetValue.takeIf { mission.targetUnit == "steps" }

    // 화면을 완전히 떠날 때: 신호 이력·세션을 리셋(센서 해제 + stale 방지)한 뒤 상위로 이탈.
    val leave = {
        tracker.reset()
        vm.reset()
        onBack()
    }

    // 펫 산책 영상 뷰 — 세션과 같은 화면/상태를 공유. remember 로 유지, 화면 이탈 시 release.
    val petView = remember {
        PetWalkingView(context).apply {
            setBackground(R.drawable.park_background)
            setPuppyVideo(R.raw.puppy_walk_green)
        }
    }

    // 세션 상태(확정·걸음)가 바뀔 때마다 새로 발생한 신호만 재생(트래커가 1회로 dedupe).
    // 측정 중에만 steps 가 변하므로 백그라운드(pause)에선 신호가 나지 않는다(#144 와 정합).
    LaunchedEffect(ui.confirmed, ui.steps) {
        tracker.onUpdate(ui.confirmed, ui.steps, goalSteps).forEach(feedback::play)
    }

    // 측정 중 동안만 주기 폴링. phase 가 바뀌면 이 이펙트가 재시작돼 루프가 자연히 멈춘다.
    LaunchedEffect(ui.phase) {
        if (ui.phase == WalkingSessionViewModel.Phase.MEASURING) {
            while (true) {
                vm.poll()
                delay(POLL_MS)
            }
        }
    }

    // 측정 중일 때만 산책 영상 재생. 그 외 상태에선 정지.
    LaunchedEffect(ui.phase) {
        if (ui.phase == WalkingSessionViewModel.Phase.MEASURING) petView.startWalking()
        else petView.pauseWalking()
    }

    // 생명주기: 센서·영상 재개/정지 + 화면 이탈 시 영상 자원 해제.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { vm.onResume(); petView.onResume() }
                Lifecycle.Event.ON_PAUSE -> { vm.onPause(); petView.onPause() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 화면 이탈 시 센서 확실히 해제(측정 중 뒤로가기 시 ON_PAUSE가 안 오는 경로 커버).
            vm.onPause()
            petView.release()
            feedback.release() // TTS 엔진·자원 해제
        }
    }

    BackHandler { leave() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
    ) {
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            text = mission.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "목표 ${mission.targetValue} ${targetUnitLabel(mission.targetUnit)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 산책 영상(측정 중 재생) — 준비/측정 단계에서 노출.
        if (ui.phase == WalkingSessionViewModel.Phase.READY ||
            ui.phase == WalkingSessionViewModel.Phase.MEASURING
        ) {
            AigoHeroCard(modifier = Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { petView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
        }

        when (ui.phase) {
            WalkingSessionViewModel.Phase.READY -> ReadyContent(
                sensorAvailable = ui.sensorAvailable,
                startFailed = ui.startFailed,
                onStart = vm::startMeasuring,
            )

            WalkingSessionViewModel.Phase.MEASURING -> MeasuringContent(
                ui = ui,
                onFinish = vm::finish,
            )

            WalkingSessionViewModel.Phase.DONE -> DoneContent(
                ui = ui,
                onConfirm = leave,
            )
        }
        Spacer(Modifier.height(Dimens.Space8))
    }
}

@Composable
private fun ReadyContent(
    sensorAvailable: Boolean,
    startFailed: Boolean,
    onStart: () -> Unit,
) {
    if (!sensorAvailable) {
        Text(
            text = "이 기기는 걸음 측정을 지원하지 않아요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    if (startFailed) {
        // 센서는 있으나 등록에 실패한 경우 — 다시 시도 안내.
        Text(
            text = "측정을 시작하지 못했어요. 잠시 후 다시 눌러 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(
            text = "휴대폰을 손에 들거나 주머니에 넣고,\n준비되면 아래 버튼을 눌러 걸어 주세요.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    AigoPrimaryButton(text = if (startFailed) "다시 시도" else "측정 시작", onClick = onStart)
}

@Composable
private fun MeasuringContent(
    ui: WalkingSessionViewModel.UiState,
    onFinish: () -> Unit,
) {
    val walking = ui.walking
    Text(
        text = if (walking) "🚶 걷는 중" else "⏸ 멈춤",
        style = MaterialTheme.typography.titleMedium,
        color = if (walking) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
    )
    Text("이번 세션 걸음 수", style = MaterialTheme.typography.titleMedium)
    if (ui.confirmed) {
        Text(
            text = "${ui.steps}",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("걸음", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    } else {
        // 보행 확정 전(워밍업): 숫자 대신 잠정 표시. 몇 걸음 규칙적으로 걸어야 카운트가 시작된다.
        Text(
            text = "측정 중…",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "규칙적으로 몇 걸음 걸으면 세기 시작해요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = "경과 ${formatElapsed(ui.elapsedSec)}",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.secondary,
    )

    // 🟡 #132: 보행 직후 곧바로 앉기 과다카운트 완화 안내(실사용 노출 지점).
    WalkSitGuidanceNote()

    AigoPrimaryButton(text = "측정 종료", onClick = onFinish)
}

@Composable
private fun DoneContent(
    ui: WalkingSessionViewModel.UiState,
    onConfirm: () -> Unit,
) {
    Text("🎉 걷기 완료!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    AigoCard {
        ResultRow("걸음 수", "${ui.steps} 걸음")
        Spacer(Modifier.height(Dimens.Space8))
        ResultRow("걸은 시간", formatElapsed(ui.elapsedSec))
    }
    // 거리·포인트 반영과 서버 저장은 다음 업데이트(#91)에서 연결된다. 지금은 측정값만 확정해 보여준다.
    Text(
        text = "기록 저장은 다음 업데이트에서 연결돼요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AigoPrimaryButton(text = "확인", onClick = onConfirm)
}

@Composable
private fun ResultRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

/** 경과 초 → "m분 s초" / "s초". */
private fun formatElapsed(sec: Int): String {
    if (sec < 60) return "${sec}초"
    return "${sec / 60}분 ${sec % 60}초"
}
