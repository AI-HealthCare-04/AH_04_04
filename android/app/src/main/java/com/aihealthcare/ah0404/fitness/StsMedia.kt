package com.aihealthcare.ah0404.fitness

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aihealthcare.ah0404.R

/**
 * 번들 루프 영상(sts_loop.mp4, res/raw) — 5STS 자세 가이드. 무음·무한반복·컨트롤 없음.
 * 스트리밍이 아니라 앱 자산이라 오프라인 온보딩에서도 재생된다. (앱에서 루프 가공·역재생·속도 조절 금지)
 */
@UnstableApi
@Composable
fun StsLoopVideo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.sts_loop)))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // 무음 — 안내는 TTS 가 담당(혹시 오디오 있는 파일이 섞여도 대비).
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { exo.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { useController = false } },
        update = { it.player = exo },
    )
}

/** assets/ 경로의 이미지를 ImageBitmap 으로 로드(실패 시 null → 호출부에서 생략). */
@Composable
fun rememberAssetImageBitmap(assetPath: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(assetPath) {
        runCatching {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it).asImageBitmap() }
        }.getOrNull()
    }
}
