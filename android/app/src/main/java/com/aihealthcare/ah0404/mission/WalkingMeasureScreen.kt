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
 *  생명주기: onResume/onPause 에 맞춰 센서·영상을 재개/정지하고, 화면을 벗어나면 영상을 release.
 *     (백그라운드/회전 후 '세션 값 복원'은 후속 #90 2단계 — 지금은 화면을 떠나면 세션이 끝난다.)
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
    // 이 화면은 자체 ViewModelStoreOwner(NavBackStackEntry)가 없어, viewModel()을 쓰면 VM이 Activity
    // 스토어에 남아 (1) 화면 이탈 후에도 센서가 등록된 채 남고 (2) 재진입 시 이전 세션 상태가 되살아난다.
    // PR1 의미("화면을 떠나면 세션이 끝난다")대로, VM을 화면 생존기간에 묶고 이탈 시 센서를 해제한다.
    // (회전/백그라운드 후 세션 값 복원은 후속 #90 2단계.)
    val vm = remember {
        WalkingSessionViewModel(WalkingSession(context.applicationContext))
    }
    val ui = vm.uiState

    // 펫 산책 영상 뷰 — 세션과 같은 화면/상태를 공유. remember 로 유지, 화면 이탈 시 release.
    val petView = remember {
        PetWalkingView(context).apply {
            setBackground(R.drawable.park_background)
            setPuppyVideo(R.raw.puppy_walk_green)
        }
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
        }
    }

    BackHandler { onBack() }

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
                onConfirm = onBack,
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
