package com.aihealthcare.ah0404.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

    // 몸풀기 번들 루틴은 백엔드 목록과 무관하게 재생 가능. 전체화면으로 띄운다(스트리밍 #72과 별개).
    var showRoutine by remember { mutableStateOf(false) }
    if (showRoutine) {
        RoutinePlayerScreen(
            routineFile = "warmup_common.json",
            onExit = { showRoutine = false },
            onComplete = { showRoutine = false },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        TopBar(title = "운동하기", onBack = onBack)

        when {
            vm.loading && vm.videos.isEmpty() -> CenterLoading()
            // 백엔드 목록이 비었거나 실패해도(오프라인/준비중) 번들 몸풀기는 항상 제공한다.
            vm.videos.isEmpty() -> WarmupFallback(onStart = { showRoutine = true }, retry = if (vm.error) vm::load else null)
            else -> StageTabs(vm.videos, onStartWarmup = { showRoutine = true })
        }
    }
}

@Composable
private fun StageTabs(videos: List<ExerciseVideoItem>, onStartWarmup: () -> Unit) {
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
        VideoArea(current, onStartWarmup = onStartWarmup)
    }
}

@UnstableApi
@Composable
private fun VideoArea(item: ExerciseVideoItem, onStartWarmup: () -> Unit) {
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
        when {
            // 몸풀기: 번들 루틴(따라 하는 실제 운동). 스트리밍 준비중과 별개로 지금 재생 가능.
            item.stage == "warmup" -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🤸", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "따라 하는 몸풀기 운동이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onStartWarmup) { Text("운동 시작하기") }
                }
            }
            // 그 외 단계: 스트리밍 영상(서버 업로드 시).
            item.available && url != null -> StreamingVideoPlayer(url = url, modifier = Modifier.fillMaxSize())
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

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** 백엔드 목록이 비어도(오프라인/준비중) 번들 몸풀기 루틴은 항상 시작할 수 있게 하는 폴백. */
@Composable
private fun WarmupFallback(onStart: () -> Unit, retry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(Dimens.ScreenPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🤸", style = MaterialTheme.typography.headlineLarge)
            Text(
                "따라 하는 몸풀기 운동을 지금 할 수 있어요.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = onStart) { Text("몸풀기 운동 시작하기") }
            if (retry != null) TextButton(onClick = retry) { Text("다른 운동 다시 불러오기") }
        }
    }
}
