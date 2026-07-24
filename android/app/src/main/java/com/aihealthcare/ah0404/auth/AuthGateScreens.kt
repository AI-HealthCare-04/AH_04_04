package com.aihealthcare.ah0404.auth

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

@Composable
fun LoginRequiredScreen(
    onGoogleLogin: () -> Unit,
    onKakaoLogin: () -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onResetSession: () -> Unit = {},
    loading: SocialProvider? = null,
    message: String? = null,
    googleEnabled: Boolean = true,
    kakaoEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showExitConfirmation by remember { mutableStateOf(false) }
    BackHandler { showExitConfirmation = true }
    GateLayout(
        title = "다시 로그인이 필요해요",
        message = "안전하게 계속하려면 간편로그인으로 본인 확인을 해 주세요.",
        modifier = modifier,
    ) {
        AigoPrimaryButton(
            text = if (loading == SocialProvider.GOOGLE) "Google 로그인 중…" else "Google로 로그인",
            onClick = onGoogleLogin,
            enabled = googleEnabled && loading == null,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoSecondaryButton(
            text = if (loading == SocialProvider.KAKAO) "카카오 로그인 중…" else "카카오로 로그인",
            onClick = onKakaoLogin,
            enabled = kakaoEnabled && loading == null,
        )
        if (message != null) {
            Spacer(Modifier.height(Dimens.Space12))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(Dimens.Space12))
        AigoSecondaryButton(text = "상태 다시 확인", onClick = onRetry)
        // [DEBUG 전용] 키 미설정 개발 빌드에서 LOGIN_REQUIRED 고립 방지용 탈출구(#119).
        //   세션을 초기화해 온보딩(시작화면)으로 복귀시킨다. 릴리스 빌드에는 노출하지 않는다.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(Dimens.Space12))
            AigoSecondaryButton(
                text = "처음부터 다시 시작 (개발용)",
                onClick = onResetSession,
                enabled = loading == null,
            )
        }
    }
    if (showExitConfirmation) {
        AigoDialog(
            title = "앱을 종료할까요?",
            message = "로그인을 마친 뒤 서비스를 계속 이용할 수 있어요.",
            confirmText = "종료",
            onConfirm = onExit,
            dismissText = "계속하기",
            onDismiss = { showExitConfirmation = false },
            onDismissRequest = { showExitConfirmation = false },
        )
    }
}

@Composable
fun OfflineModeScreen(
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExitConfirmation by remember { mutableStateOf(false) }
    BackHandler { showExitConfirmation = true }
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
    if (showExitConfirmation) {
        AigoDialog(
            title = "앱을 종료할까요?",
            message = "인터넷 연결을 확인한 뒤 다시 시도할 수 있어요.",
            confirmText = "종료",
            onConfirm = onExit,
            dismissText = "계속하기",
            onDismiss = { showExitConfirmation = false },
            onDismissRequest = { showExitConfirmation = false },
        )
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
