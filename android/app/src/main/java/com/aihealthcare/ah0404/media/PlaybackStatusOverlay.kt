package com.aihealthcare.ah0404.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 스트리밍 재생 상태 오버레이(#345) — 버퍼링·재생 실패를 검은 화면과 구분해 보여준다.
 *
 *  어르신 환경(불안정한 Wi-Fi·데이터 절약 모드)에서 실패·버퍼링이 흔한데, 지금까지는 아무 표시가 없어
 *  '앱이 고장났다'로 읽혔다. 영상 컨테이너(Box) 안에 플레이어 위로 겹쳐 그린다.
 *   - [buffering]: 로딩 중임을 알리는 인디케이터(재생 준비/버퍼링 동안).
 *   - [error]: 실패 안내 + '다시 시도'(플레이어 재생성). 오류가 로딩보다 우선.
 */
@Composable
fun PlaybackStatusOverlay(
    buffering: Boolean,
    error: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        error -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
                modifier = Modifier.padding(Dimens.ScreenPadding),
            ) {
                Text(
                    "영상을 불러오지 못했어요",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "네트워크 연결을 확인한 뒤 다시 시도해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                AigoSecondaryButton(
                    text = "다시 시도",
                    onClick = onRetry,
                    modifier = Modifier.width(200.dp),
                )
            }
        }
        buffering -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
