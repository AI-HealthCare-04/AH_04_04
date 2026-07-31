package com.aihealthcare.ah0404.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.common.Player
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
 *  - `speed`: 운동 난이도별 재생 속도(가볍게 0.8 / 보통 1.0 / 힘차게 1.25). ExoPlayer 기본 시간축 신축이라
 *    빨라져도 pitch(음정)는 유지된다. url 이 안 바뀌어도 속도 변경은 즉시 반영(LaunchedEffect).
 *  - `onIsPlayingChanged`: ExoPlayer 의 '실제 재생 중' 상태 변화를 알린다(재생 시작 true, 일시정지·버퍼링·
 *    끝·백그라운드 정지 false). 운동 완료 배선(#234)이 '실제로 본 시간'만 재는 데 쓴다(리뷰 P1-A). 기본값 무동작.
 *  - `startPositionMs`: 진입 시 이 위치로 이어재생(#235). 이탈 후 다시 들어와도 처음부터 재생되지 않게, 호출부가
 *    직전 위치를 보관했다가 넘긴다. 0(기본)이면 처음부터.
 *  - `onPositionSaved`: 이탈(dispose) 직전 현재 재생 위치(ms)를 알린다(#235). 호출부가 url 별로 보관해 다음
 *    진입의 [startPositionMs] 로 되돌려준다. 영상이 끝까지 재생됐으면 0 을 넘겨 다음 진입은 처음부터 시작한다.
 */
@UnstableApi
@Composable
fun StreamingVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    speed: Float = 1f,
    startPositionMs: Long = 0L,
    onIsPlayingChanged: (Boolean) -> Unit = {},
    onPositionSaved: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 콜백 최신값 유지(리스너를 매 재구성마다 재등록하지 않도록) — 리스너는 player 단위로만 붙였다 뗀다.
    val currentOnIsPlayingChanged by rememberUpdatedState(onIsPlayingChanged)
    val currentOnPositionSaved by rememberUpdatedState(onPositionSaved)

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
                // 이어보기(#235): prepare 전에 seek 해 두면 준비 완료 후 그 위치부터 재생된다. 0 이면 처음부터.
                if (startPositionMs > 0L) seekTo(startPositionMs)
                prepare()
                playWhenReady = autoPlay
                volume = com.aihealthcare.ah0404.settings.AppSettings.soundScale // 설정 소리 크기 적용(C-2)
                setPlaybackSpeed(speed) // 운동 난이도별 재생 속도(초기값)
            }
    }

    // 난이도(속도)가 바뀌면 url 이 그대로여도 재생 중 플레이어에 즉시 반영.
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }

    // 실제 재생 상태(isPlaying) 변화를 호출부에 전달(#234 P1-A). player 가 바뀌면(url 변경) 리스너를 다시 붙인다.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = currentOnIsPlayingChanged(isPlaying)
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
            // 이어보기(#235): release 전에 현재 위치를 보고한다. 끝까지 본 경우(STATE_ENDED)는 0 으로 넘겨
            //   다음 진입이 처음부터 시작하게 한다(완주한 영상을 다시 끝 지점에서 열지 않도록).
            val savedMs = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            currentOnPositionSaved(savedMs)
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
