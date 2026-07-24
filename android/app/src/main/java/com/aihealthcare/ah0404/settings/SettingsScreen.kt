package com.aihealthcare.ah0404.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 *  ⛔ 알림·자동로그인은 백엔드 결정상 '미구현'(자동로그인=구현 안 함 / 알림=불필요로 API 제외, 재란 확정)
 *     → "미지원"으로 비활성 표시(후속 없음).
 *  앱 버전은 서버가 아니라 클라 BuildConfig.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenSupport: () -> Unit,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    // 로그아웃 확인 다이얼로그(#154). 시니어 대상이라 실수 방지로 한 번 되묻는다.
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    // 진입마다 서버 설정 재조회(리뷰 #68 교훈).
    LaunchedEffect(Unit) { vm.load() }
    // 전역 적용값(글자·소리)을 VM 의 최종 설정값에 항상 동기화(묶음 C-2, 리뷰 #86-1).
    //   loaded 뿐 아니라 fontSize/soundSize/musicEnabled 변경까지 관찰 → 저장 실패 후 롤백/서버 재조회로 값이
    //   되돌아와도 전역 배율·로컬 저장값이 서버값으로 수렴한다(실패한 값이 잔류하지 않음).
    //   ⚠️ loadError 시엔 동기화 안 함(리뷰 #86-1): 최초 GET 실패 시 vm 값은 서버 확인값이 아니라
    //     기본값(medium)이라, 이를 저장하면 시작 시 복원한 정상 로컬 캐시(예: large)를 덮어쓴다.
    //     서버가 확인해 준 값(성공) 또는 사용자가 바꾼 값(성공 로드 후)일 때만 전역에 반영한다.
    LaunchedEffect(vm.loaded, vm.loadError, vm.fontSize, vm.soundSize, vm.musicEnabled) {
        if (vm.loaded && !vm.loadError) {
            AppSettings.setFontSize(context, vm.fontSize)
            AppSettings.setSoundSize(context, vm.soundSize)
            AppSettings.setMusicEnabled(context, vm.musicEnabled)
        }
    }

    val sizeOptions = listOf(
        SegmentOption("small", "작게"),
        SegmentOption("medium", "보통"),
        SegmentOption("large", "크게"),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
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
                // vm.changeFontSize 가 값을 낙관적으로 바꾸면 위 LaunchedEffect(vm.fontSize) 가
                //   전역 적용(AppSettings) 을 동기화한다(실패 시 롤백값으로도 수렴). 미리보기 문구로 체감.
                AigoSegmentedSelector(sizeOptions, vm.fontSize, vm::changeFontSize, horizontal = true)
                Spacer(Modifier.height(Dimens.Space8))
                Text("보기: 글자 크기가 이렇게 바뀌어요.", style = MaterialTheme.typography.bodyLarge)
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
                // ⛔ 백엔드 결정상 미구현(후속 없음) — "미지원"으로 비활성.
                ToggleRow("알림 받기 (미지원)", checked = false, onChange = {}, enabled = false)
                ToggleRow("자동 로그인 (미지원)", checked = false, onChange = {}, enabled = false)
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

            Spacer(Modifier.height(Dimens.Space4))
            AigoSecondaryButton(
                text = "로그아웃",
                onClick = { showLogoutConfirm = true },
            )

            // #131 파형 수집 진입 — 디버그 빌드에서만 노출(릴리스에는 대상 Activity 자체가 없음).
            //   명시 인텐트를 문자열 ComponentName 으로 실행해, debug 소스셋 전용 Activity 를
            //   릴리스에서 컴파일 참조하지 않는다(참조하면 릴리스 빌드가 깨진다).
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(Dimens.Space4))
                AigoSecondaryButton(
                    text = "🔧 파형 수집 (디버그 · #131)",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent().setClassName(
                                    context,
                                    "com.aihealthcare.ah0404.sensor.WaveformCaptureActivity",
                                ),
                            )
                        }
                    },
                )
                // #91 1-B: '다음 저장 1회 실패'를 무장해 걷기 저장 실패 → [다시 저장] 재시도를 실기기로 확인.
                Spacer(Modifier.height(Dimens.Space4))
                ToggleRow(
                    "🔧 다음 걷기저장 1회 실패 (디버그 · #91)",
                    checked = com.aihealthcare.ah0404.mission.WalkingDebug.failNextSaveArmed,
                    onChange = { com.aihealthcare.ah0404.mission.WalkingDebug.failNextSaveArmed = it },
                )
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

    if (showLogoutConfirm) {
        AigoDialog(
            title = "로그아웃",
            message = "로그아웃할까요? 다시 로그인하면 이어서 사용할 수 있어요.",
            confirmText = "로그아웃",
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismissRequest = { showLogoutConfirm = false },
            dismissText = "취소",
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
internal fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space8, vertical = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
