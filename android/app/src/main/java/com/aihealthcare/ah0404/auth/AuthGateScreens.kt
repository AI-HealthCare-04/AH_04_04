package com.aihealthcare.ah0404.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

@Composable
fun LoginRequiredScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GateLayout(
        title = "다시 로그인이 필요해요",
        message = "안전하게 계속하려면 간편로그인으로 본인 확인을 해 주세요.",
        modifier = modifier,
    ) {
        AigoPrimaryButton(text = "Google 로그인 (준비 중)", onClick = {}, enabled = false)
        Spacer(Modifier.height(Dimens.Space12))
        AigoSecondaryButton(text = "카카오 로그인 (준비 중)", onClick = {}, enabled = false)
        Spacer(Modifier.height(Dimens.Space12))
        AigoSecondaryButton(text = "상태 다시 확인", onClick = onRetry)
    }
}

@Composable
fun OfflineModeScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GateLayout(
        title = "인터넷 연결이 없어요",
        message = "서버에는 연결하지 않고, 저장된 미션만 사용할 수 있는 오프라인 모드예요.",
        modifier = modifier,
    ) {
        Text(
            "이 기기에 저장된 미션이 아직 없다면 인터넷 연결 후 다시 시도해 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space16))
        AigoPrimaryButton(text = "연결 다시 확인", onClick = onRetry)
    }
}

@Composable
private fun GateLayout(
    title: String,
    message: String,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(Dimens.Space12))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space32))
        actions()
    }
}
