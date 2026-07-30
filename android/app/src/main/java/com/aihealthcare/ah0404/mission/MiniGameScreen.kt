package com.aihealthcare.ah0404.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.util.UnstableApi
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.media.StreamingVideoPlayer
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 미니게임 영상 화면 — '게임하기' 미션(#93).
 *
 * 운동영상 4단계 카탈로그(#72) 밖의 짧은 재미 영상을 서버 /videos 에서 스트리밍한다.
 *  - URL 은 서버 카탈로그가 아니라 앱에 직접 주입(BuildConfig.MINI_GAME_VIDEO_URL). 재생 컴포넌트 재사용.
 *  - 재생 속도는 영상 안 톱니(⚙)로 조절(전역 [AppSettings.playbackSpeed], 기본 1.0배속) — 난이도 연동 폐기.
 *  - 뒤로가기는 호스트(MainActivity 서브스크린)가 처리하므로 여기선 onBack 버튼만 둔다(중복 방지, 리뷰 #220).
 *  - 미션 완료(포인트) 처리는 이 화면이 지지 않는다(#91 단일 기록 지점 원칙, 이중 경로 금지) — 후속.
 */
@UnstableApi
@Composable
fun MiniGameScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("미니게임", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StreamingVideoPlayer(
            url = BuildConfig.MINI_GAME_VIDEO_URL,
            autoPlay = true,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        AigoSecondaryButton(text = "돌아가기", onClick = onBack)
    }
}
