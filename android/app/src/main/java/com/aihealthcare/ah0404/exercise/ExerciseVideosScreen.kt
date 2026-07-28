package com.aihealthcare.ah0404.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.aihealthcare.ah0404.media.StreamingVideoPlayer
import com.aihealthcare.ah0404.settings.AppSettings
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.routine.RoutinePlayerScreen
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 운동하기 — 4단계(몸풀기·앉아서·서서·마무리) 영상 탭. 백엔드 #72(GET /exercise-videos) 배선.
 *
 *  available=true 단계는 스트리밍 재생, false 는 "준비중" 표시(탭은 숨기지 않음).
 *  현재 서버 업로드 전이라 대부분 준비중 — filename 채워지면 앱 코드 변경 없이 켜진다.
 */
@UnstableApi
@Composable
fun ExerciseVideosScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ExerciseVideosViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    // 번들 루틴(몸풀기·마무리)은 백엔드 목록과 무관하게 오프라인에서도 재생 가능(심사 환경 안정 버전).
    //   여러 동작을 조합한 가이드 루틴이라 단일 스트리밍 영상이 아니라 번들 RoutinePlayer 로 띄운다(#72 스트리밍과 별개).
    var routineFile by remember { mutableStateOf<String?>(null) }
    routineFile?.let { file ->
        RoutinePlayerScreen(
            routineFile = file,
            onExit = { routineFile = null },
            onComplete = { routineFile = null },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        TopBar(title = "영상 따라 운동하기", onBack = onBack)

        // 번들 몸풀기는 네트워크와 무관하게 '즉시' 시작 가능해야 한다(오프라인/느린망 포함).
        //   서버 목록이 오면 탭으로, 아직이면(로딩/빈/에러) 폴백에서 몸풀기 버튼을 바로 보여준다.
        if (vm.videos.isNotEmpty()) {
            StageTabs(vm.videos, onStartRoutine = { routineFile = it })
        } else {
            WarmupFallback(
                onStart = { routineFile = "warmup_common.json" },
                loading = vm.loading,
                retry = if (vm.error) vm::load else null,
            )
        }
    }
}

@Composable
private fun StageTabs(videos: List<ExerciseVideoItem>, onStartRoutine: (String) -> Unit) {
    var selected by remember(videos) { mutableIntStateOf(0) }
    val current = videos[selected.coerceIn(0, videos.lastIndex)]

    Column {
        TabRow(selectedTabIndex = selected) {
            videos.forEachIndexed { index, v ->
                Tab(
                    selected = index == selected,
                    onClick = { selected = index },
                    text = { Text(v.label, style = MaterialTheme.typography.bodyLarge) },
                )
            }
        }
        VideoArea(current, onStartRoutine = onStartRoutine)
    }
}

/** 번들 루틴(RoutinePlayer)으로 재생하는 단계 → 그 단계의 루틴 JSON 파일. 스트리밍이 아니라 조합형 가이드 루틴이다. */
private fun bundledRoutineFile(stage: String): String? = when (stage) {
    "warmup" -> "warmup_common.json"
    "cooldown" -> "cooldown_common.json"
    else -> null
}

@UnstableApi
@Composable
private fun VideoArea(item: ExerciseVideoItem, onStartRoutine: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding)
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val url = item.videoUrl
        val bundledRoutine = bundledRoutineFile(item.stage)
        when {
            // 몸풀기·마무리: 번들 루틴(따라 하는 실제 운동). 스트리밍 준비중과 별개로 오프라인에서도 지금 재생 가능.
            bundledRoutine != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🤸", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "따라 하는 ${item.label} 운동이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onStartRoutine(bundledRoutine) }) { Text("운동 시작하기") }
                }
            }
            // 그 외 단계: 스트리밍 영상(서버 업로드 시).
            item.available && url != null -> StreamingVideoPlayer(
                url = url,
                modifier = Modifier.fillMaxSize(),
                speed = AppSettings.exerciseSpeedFor(AppSettings.exerciseDifficulty), // 운동 난이도별 재생 속도
            )
            // 준비중(서버 업로드 전) — 탭은 유지하되 안내.
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🎬", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "${item.label} 영상은 준비 중이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 백엔드 목록이 아직 없어도(로딩/오프라인/준비중) 번들 몸풀기는 '즉시' 시작할 수 있게 하는 폴백.
 * 서버 로딩은 몸풀기 버튼을 막지 않고 "다른 운동 불러오는 중"으로만 별도 표시한다.
 */
@Composable
private fun WarmupFallback(onStart: () -> Unit, loading: Boolean, retry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(Dimens.ScreenPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🤸", style = MaterialTheme.typography.headlineLarge)
            Text(
                "따라 하는 몸풀기 운동을 지금 할 수 있어요.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = onStart) { Text("몸풀기 운동 시작하기") }
            when {
                loading -> Text(
                    "다른 운동을 불러오는 중…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                retry != null -> TextButton(onClick = retry) { Text("다른 운동 다시 불러오기") }
            }
        }
    }
}
