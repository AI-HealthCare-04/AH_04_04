package com.aihealthcare.ah0404.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.SegmentOption
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 설정(_15) — 화면 API 계약: GET/PATCH /users/me/settings
 *   { font_size, sound_size, pet_type, music_enabled }
 *
 *  SettingsViewModel 로 서버 값 로드 + 변경 시 PATCH 영속화(낙관적 적용 → 실패 시 롤백).
 *  🟡 계약 GAP: 알림 on/off·자동로그인 on/off 필드 없음 → "준비 중"으로 비활성 표시.
 *  앱 버전은 서버가 아니라 클라 BuildConfig.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSupport: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    // 진입마다 서버 설정 재조회(리뷰 #68 교훈).
    LaunchedEffect(Unit) { vm.load() }

    val sizeOptions = listOf(
        SegmentOption("small", "작게"),
        SegmentOption("medium", "보통"),
        SegmentOption("large", "크게"),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "설정", onBack = onBack)

        Column(
            Modifier.padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            // 내 정보(_14) 진입 — 계정·프로필 정보
            AigoSecondaryButton(text = "내 정보", onClick = onOpenProfile)

            if (vm.loadError) {
                AigoCard {
                    Text(
                        "설정을 불러오지 못했어요. 기본값이 표시됩니다.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.Space4))
                    TextButton(onClick = vm::load) { Text("다시 시도") }
                }
            }
            AigoCard {
                Text("글자 크기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Dimens.Space8))
                AigoSegmentedSelector(sizeOptions, vm.fontSize, vm::changeFontSize, horizontal = true)
            }
            AigoCard {
                Text("소리 크기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Dimens.Space8))
                AigoSegmentedSelector(sizeOptions, vm.soundSize, vm::changeSoundSize, horizontal = true)
            }
            AigoCard {
                Text("펫 종류", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Dimens.Space8))
                AigoSegmentedSelector(
                    listOf(SegmentOption("dog", "강아지"), SegmentOption("cat", "고양이")),
                    vm.petType, vm::changePetType, horizontal = true,
                )
            }
            AigoCard {
                ToggleRow("배경 음악", vm.musicEnabled, vm::changeMusicEnabled)
                // 🟡 GAP(계약 필드 없음) — 준비 중으로 비활성.
                ToggleRow("알림 받기 (준비 중)", checked = false, onChange = {}, enabled = false)
                ToggleRow("자동 로그인 (준비 중)", checked = false, onChange = {}, enabled = false)
            }
            AigoCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("앱 버전", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.Space4))
            AigoSecondaryButton(
                text = "고객센터 · 자주 묻는 질문",
                onClick = onOpenSupport,
            )
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
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.MinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space8, vertical = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
