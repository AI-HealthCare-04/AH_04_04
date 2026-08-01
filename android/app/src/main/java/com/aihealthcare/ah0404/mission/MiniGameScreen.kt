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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.media.StreamingVideoPlayer
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 미니게임 영상 화면 — '게임하기' 미션(#93).
 *
 * 운동영상 4단계 카탈로그(#72) 밖의 짧은 재미 영상을 서버 /videos 에서 스트리밍한다.
 *  - URL 은 서버 카탈로그가 아니라 앱에 직접 주입(BuildConfig.MINI_GAME_VIDEO_URL). 재생 컴포넌트 재사용.
 *  - 재생 속도는 영상 안 톱니(⚙)로 조절(전역 [AppSettings.playbackSpeed], 기본 1.0배속) — 난이도 연동 폐기.
 *  - 뒤로가기는 호스트(MainActivity 서브스크린)가 처리하므로 여기선 onBack 버튼만 둔다(중복 방지, 리뷰 #220).
 *  - 완료 기록(#348 리뷰): 영상 완주 시 [MiniGameViewModel] 이 게임 미션 완료를 1회 기록한다 —
 *    이것이 게임 완료의 단일 기록 지점(#91)이며, 성공 시 [onCompleted] 로 목록 갱신(today_done, #346)을 알린다.
 */
@UnstableApi
@Composable
fun MiniGameScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // 완료 기록 대상 미션. 호출부(MainActivity)가 미션 목록에서 고른 게임 미션을 넘긴다. null 이면 기록 생략(방어).
    mission: Mission? = null,
    // 완료 기록 시도 후 호출(성공·유실·실패 공통) — 호출부가 미션 목록을 재조회해 서버 권위값으로 조정한다(#348 2차).
    onCompleted: () -> Unit = {},
    vm: MiniGameViewModel = viewModel(),
) {
    // 재진입 복구(#348 2차): 직전 방문에서 기록에 최종 실패한 완주가 있으면 다시 시도한다(포인트 유실 방지).
    LaunchedEffect(Unit) { vm.retryPendingIfAny(onCompleted) }

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
            // 영상 끝(STATE_ENDED) → 완료 기록 + 자동 복귀(#344, #348 리뷰): 기록은 백그라운드로 보내고
            //   화면은 즉시 돌아간다(기록 실패가 복귀를 막지 않음 — VM 이 조용히 흡수).
            //   마지막 프레임에 멈춘 채 머무르면 시니어 사용자에게 '고장'으로 읽힌다.
            onEnded = {
                mission?.let { vm.recordCompletion(it, onCompleted) }
                onBack()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        AigoSecondaryButton(text = "돌아가기", onClick = onBack)
    }
}
