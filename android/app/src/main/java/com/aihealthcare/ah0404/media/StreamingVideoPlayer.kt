package com.aihealthcare.ah0404.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 재사용 스트리밍 영상 플레이어 — 운동 영상(#72) 등 HTTPS 스트리밍 + 로컬 캐시.
 *
 *  - CacheDataSource 로 스트리밍 캐시([[VideoCache]]). 캐시 오류 시 원본으로 폴백(FLAG_IGNORE_CACHE_ON_ERROR).
 *  - 화면을 벗어나면(onStop) 일시정지, dispose 시 반드시 release(누수 방지).
 *  - url 이 바뀌면 새 플레이어를 만든다(remember(url)).
 */
@UnstableApi
@Composable
fun StreamingVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(url) {
        val cacheFactory = CacheDataSource.Factory()
            .setCache(VideoCache.get(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = autoPlay
                volume = com.aihealthcare.ah0404.settings.AppSettings.soundScale // 설정 소리 크기 적용(C-2)
            }
    }

    // 앱이 백그라운드로 가면 일시정지(배터리/데이터 절약).
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    // factory 는 View 생성 시 1회만 실행되므로, 바뀌는 player 는 update 에서 매 재구성마다 반영한다
    //   (리뷰 #76: url 변경 시 새 ExoPlayer 로 교체되어야 재생 가능한 다른 탭 영상이 정상 재생됨).
    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { useController = true } },
        update = { it.player = player },
    )
}
