package com.aihealthcare.ah0404.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.UserInfoResponse
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoTextField
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * `_14 내 정보` — GET/PATCH /users/me(#67 통합 응답) 배선.
 *
 *  계정 정보 + 생년월일·성별·보유포인트·운동강도를 한 화면에 모아 보여주고, 닉네임을 인라인 편집한다.
 *  진입할 때마다 재조회(리뷰 #68 교훈). 닉네임 저장은 성공 시에만 반영, 실패 시 안내 + 편집 유지.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "내 정보", onBack = onBack)

        Column(
            Modifier.padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            val info = vm.info
            when {
                vm.error && info == null -> ErrorCard(onRetry = vm::load)
                info == null -> LoadingCard()
                else -> {
                    // 재진입 조회 실패가 캐시된 info 뒤에 숨지 않도록 배너+재시도 노출(리뷰 #70 지적 2).
                    if (vm.error) RefreshErrorBanner(onRetry = vm::load)
                    ProfileContent(vm, info)
                }
            }
        }
    }

    vm.saveError?.let { msg ->
        AigoDialog(
            title = "알림",
            message = msg,
            confirmText = "확인",
            onConfirm = vm::dismissSaveError,
            onDismissRequest = vm::dismissSaveError,
        )
    }
}

@Composable
private fun ProfileContent(vm: ProfileViewModel, info: UserInfoResponse) {
    // 닉네임 인라인 편집 상태(info 갱신 시 초기값 리셋).
    var editing by remember(info.nickname) { mutableStateOf(false) }
    var draft by remember(info.nickname) { mutableStateOf(info.nickname) }

    AigoCard {
        Text("닉네임", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dimens.Space4))
        if (!editing) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(info.nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { draft = info.nickname; editing = true }) { Text("수정") }
            }
        } else {
            AigoTextField(draft, { draft = it }, "닉네임 (1~50자)")
            Spacer(Modifier.height(Dimens.Space8))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                AigoPrimaryButton(
                    text = if (vm.saving) "저장 중…" else "저장",
                    onClick = { vm.updateNickname(draft) { editing = false } },
                    enabled = !vm.saving,
                    modifier = Modifier.weight(1f),
                )
                AigoSecondaryButton(
                    text = "취소",
                    onClick = { editing = false; draft = info.nickname },
                    enabled = !vm.saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // 나머지 정보(읽기 전용)
    AigoCard {
        InfoRow("생년월일", formatBirth(info.birthDate))
        Divider()
        InfoRow("성별", sexLabel(info.sex))
        Divider()
        InfoRow("운동 강도", activityLevelLabel(info.activityLevel))
        Divider()
        InfoRow("보유 포인트", "%,d P".format(info.currentPoints))
        Divider()
        InfoRow("로그인 방식", providerLabel(info.provider))
        Divider()
        InfoRow("가입일", formatDate(info.createdAt))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.MinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun LoadingCard() {
    AigoCard {
        Box(Modifier.fillMaxWidth().padding(Dimens.Space16), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/** 캐시된 정보가 있는 상태에서 재조회가 실패했을 때, 낡은 값 위에 얹는 오류 안내 배너. */
@Composable
private fun RefreshErrorBanner(onRetry: () -> Unit) {
    AigoCard {
        Text(
            "최신 정보를 불러오지 못했어요. 아래 값은 이전에 불러온 정보일 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space4))
        TextButton(onClick = onRetry) { Text("다시 시도") }
    }
}

@Composable
private fun ErrorCard(onRetry: () -> Unit) {
    AigoCard {
        Text(
            "내 정보를 불러오지 못했어요. 네트워크를 확인해 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space4))
        TextButton(onClick = onRetry) { Text("다시 시도") }
    }
}

// ── 표시 라벨/포맷 ───────────────────────────────────────────────────────────
private fun formatBirth(birth: String?): String =
    if (birth != null && birth.length >= 10) birth.substring(0, 10).replace('-', '.') else "미입력"

/** KST ISO8601 → "YYYY.MM.DD". */
private fun formatDate(iso: String): String =
    if (iso.length >= 10) iso.substring(0, 10).replace('-', '.') else iso

private fun sexLabel(sex: String?): String = when (sex) {
    "male" -> "남성"
    "female" -> "여성"
    else -> "미입력"
}

private fun activityLevelLabel(level: String): String = when (level) {
    "easy" -> "가볍게"
    "hard" -> "활발히"
    else -> "보통"
}

private fun providerLabel(provider: String): String = when (provider) {
    "google" -> "구글"
    "kakao" -> "카카오"
    "guest" -> "게스트"
    else -> provider
}
