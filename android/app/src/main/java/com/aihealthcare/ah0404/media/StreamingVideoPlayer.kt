package com.aihealthcare.ah0404.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.aihealthcare.ah0404.settings.AppSettings

/**
 * 재사용 스트리밍 영상 플레이어 — 운동 영상(#72) 등 HTTPS 스트리밍 + 로컬 캐시.
 *
 *  - CacheDataSource 로 스트리밍 캐시([[VideoCache]]). 캐시 오류 시 원본으로 폴백(FLAG_IGNORE_CACHE_ON_ERROR).
 *  - 화면을 벗어나면(onStop) 일시정지, dispose 시 반드시 release(누수 방지).
 *  - url 이 바뀌면 새 플레이어를 만든다(remember(url)).
 *  - 재생 속도: 기본 컨트롤러(useController)의 톱니(⚙)로 사용자가 영상 안에서 직접 조절한다. 시작값은 전역
 *    [AppSettings.playbackSpeed](기본 1.0배속)이고, 톱니로 바꾸면 전역에 영속해 다음 영상에도 이어진다
 *    (난이도 개념 폐기 → 톱니 통일). ExoPlayer 는 기본 시간축 신축이라 빨라져도 pitch(음정)는 유지된다.
 *  - `onIsPlayingChanged`: ExoPlayer 의 '실제 재생 중' 상태 변화를 알린다(재생 시작 true, 일시정지·버퍼링·
 *    끝·백그라운드 정지 false). 운동 완료 배선(#234)이 '실제로 본 시간'만 재는 데 쓴다(리뷰 P1-A). 기본값 무동작.
 */
@UnstableApi
@Composable
fun StreamingVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    onIsPlayingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 콜백 최신값 유지(리스너를 매 재구성마다 재등록하지 않도록) — 리스너는 player 단위로만 붙였다 뗀다.
    val currentOnIsPlayingChanged by rememberUpdatedState(onIsPlayingChanged)

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
                volume = AppSettings.soundScale // 설정 소리 크기 적용(C-2)
                setPlaybackSpeed(AppSettings.playbackSpeed) // 전역 재생 속도로 시작(톱니로 조절, 기본 1.0)
            }
    }

    // 실제 재생 상태(isPlaying) 변화를 호출부에 전달(#234 P1-A). player 가 바뀌면(url 변경) 리스너를 다시 붙인다.
    //   컨트롤러 톱니로 바꾼 속도(onPlaybackParametersChanged)는 전역에 영속해 다음 영상에도 이어진다.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = currentOnIsPlayingChanged(isPlaying)
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                AppSettings.setPlaybackSpeed(context, playbackParameters.speed)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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
