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

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        TopBar(title = "운동하기", onBack = onBack)

        when {
            vm.error && vm.videos.isEmpty() -> CenterMessage("운동 영상을 불러오지 못했어요.", retry = vm::load)
            vm.loading && vm.videos.isEmpty() -> CenterLoading()
            vm.videos.isEmpty() -> CenterMessage("운동 영상이 아직 없어요.")
            else -> StageTabs(vm.videos)
        }
    }
}

@Composable
private fun StageTabs(videos: List<ExerciseVideoItem>) {
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
        VideoArea(current)
    }
}

@UnstableApi
@Composable
private fun VideoArea(item: ExerciseVideoItem) {
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
        if (item.available && url != null) {
            StreamingVideoPlayer(url = url, modifier = Modifier.fillMaxSize())
        } else {
            // 준비중(서버 업로드 전) — 탭은 유지하되 안내.
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

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun CenterMessage(text: String, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(Dimens.ScreenPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (retry != null) TextButton(onClick = retry) { Text("다시 시도") }
        }
    }
}
