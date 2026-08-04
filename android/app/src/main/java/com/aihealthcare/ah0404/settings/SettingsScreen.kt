package com.aihealthcare.ah0404.settings

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.activity.result.ActivityResultLauncher
import com.aihealthcare.ah0404.reminder.InactivityReminder
import com.aihealthcare.ah0404.reminder.CHANNEL_ID
import com.aihealthcare.ah0404.reminder.ReminderWorker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.ui.components.AigoDialog

// 설정 디자인 고도화 팔레트(시안 토큰). 흰색 대신 바탕+테두리 카드(사용자 피드백 일관).
private val SBg = Color(0xFFF7F6F0)
private val SGreen = Color(0xFF1F5D3A)
private val SInk = Color(0xFF202321)
private val SMuted = Color(0xFF6B726D)
private val SCardFill = Color(0xFFF7F6F0)   // 바탕색과 동일(사용자 피드백: 진한 베이지 아님, 바탕색+테두리로 통일)
private val SCardBorder = Color(0xFFE7E5DB)  // 카드 테두리는 은은하게(박스처럼 도드라지지 않게)
private val SGreenTint = Color(0xFFE7F3EA)
private val SUnselBorder = Color(0xFFD3D8CE) // 선택 안 한 알약도 테두리(사용자 피드백)

/**
 * 설정(_15) — 화면 API 계약: GET/PATCH /users/me/settings
 *   { font_size, sound_size, pet_type, music_enabled }
 *
 *  SettingsViewModel 로 서버 값 로드 + 변경 시 PATCH 영속화(낙관적 적용 → 실패 시 롤백).
 *  ⛔ 알림·자동로그인은 백엔드 결정상 '미구현'(자동로그인=구현 안 함 / 알림=불필요로 API 제외, 재란 확정)
 *     → 시안 최종구조에서 토글 자체를 노출하지 않는다(운동 음악만).
 *  앱 버전은 서버가 아니라 클라 BuildConfig.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenSupport: () -> Unit,
    onOpenFaq: () -> Unit,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit = {},
    // 회원탈퇴 성공 후(#356) — 호출부가 세션·공급자 credential 정리 후 로그인 화면으로 보낸다(로그아웃과 동일 경로).
    onWithdrawn: () -> Unit = {},
    // 회원탈퇴 **결과 불명**(#356 리뷰 P1: 타임아웃·401·5xx) — 서버가 이미 파기를 커밋했을 수 있어
    //   호출부(Activity 범위)가 즉시 같은 정리를 태우되, 성공을 단정하지 않는 안내를 로그인 화면에 남긴다.
    onWithdrawUncertain: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    // 미접속 리마인드는 기기 로컬 설정이라 서버(#73)와 동기화하지 않는다 — 여기서 직접 읽고 쓴다.
    var remindersEnabled by rememberSaveable { mutableStateOf(InactivityReminder.isEnabled(context)) }
    // 시스템 쪽 상태(권한·앱 알림·채널)는 화면 밖에서 바뀔 수 있다. 바뀔 만한 시점마다 이 값을 올려
    //   아래 계산을 다시 돌린다.
    var systemNotificationRevision by remember { mutableIntStateOf(0) }
    // ⚠️ **상태로 들고 있다가 손으로 갱신하지 않는다.** 그렇게 했더니 토글을 껐을 때 갱신을 빠뜨려
    //   꺼진 뒤에도 안내가 남았다(실기기 QA). 입력(토글·시스템 상태)에서 매번 파생시킨다.
    val recoveryAction = remember(remindersEnabled, systemNotificationRevision) {
        notificationRecoveryAction(context, remindersEnabled)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        InactivityReminder.markPermissionAsked(context)
        systemNotificationRevision++
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 시스템 설정에서 권한·채널을 바꾸고 돌아오는 경로가 있다.
            if (event == Lifecycle.Event.ON_RESUME) systemNotificationRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 로그아웃 확인 다이얼로그(#154). 시니어 대상이라 실수 방지로 한 번 되묻는다.
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }
    // 회원탈퇴 확인(#356). 되돌릴 수 없는 파괴적 액션이라 로그아웃과 별도로 강하게 안내한다.
    var showWithdrawConfirm by rememberSaveable { mutableStateOf(false) }
    // 진입마다 서버 설정 재조회(리뷰 #68 교훈).
    LaunchedEffect(Unit) { vm.load() }
    // 전역 적용값(글자·소리)을 VM 의 최종 설정값에 항상 동기화(묶음 C-2, 리뷰 #86-1).
    LaunchedEffect(vm.loaded, vm.loadError, vm.fontSize, vm.soundSize, vm.musicEnabled) {
        if (vm.loaded && !vm.loadError) {
            AppSettings.setFontSize(context, vm.fontSize)
            AppSettings.setSoundSize(context, vm.soundSize)
            AppSettings.setMusicEnabled(context, vm.musicEnabled)
        }
    }

    val sizeOptions = listOf("small" to "작게", "medium" to "보통", "large" to "크게")

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(SBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = SInk)
                }
                Spacer(Modifier.width(4.dp))
            }
            Text("설정", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = SInk)
        }
        Spacer(Modifier.height(8.dp))

        // 내 정보(_14) 진입 — 계정·프로필 정보
        SettingsNavCard(
            icon = Icons.Filled.Person,
            title = "내 정보",
            subtitle = "기본 정보와 건강 정보를 확인해요",
            onClick = onOpenProfile,
        )
        Spacer(Modifier.height(14.dp))

        if (vm.loadError) {
            SettingsCard {
                Text("설정을 불러오지 못했어요. 기본값이 표시됩니다.", fontSize = 15.sp, lineHeight = 21.sp, color = SMuted)
                TextButton(onClick = vm::load) { Text("다시 시도", color = SGreen, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(14.dp))
        }

        SettingsCard {
            Text("글자 크기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SInk)
            Spacer(Modifier.height(10.dp))
            SettingSegment(sizeOptions, vm.fontSize, vm::changeFontSize)
            Spacer(Modifier.height(10.dp))
            Text("보기: 글자 크기가 이렇게 바뀌어요.", fontSize = 15.sp, lineHeight = 21.sp, color = SMuted)
        }
        Spacer(Modifier.height(14.dp))

        SettingsCard {
            Text("소리 크기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SInk)
            Spacer(Modifier.height(10.dp))
            SettingSegment(sizeOptions, vm.soundSize, vm::changeSoundSize)
        }
        Spacer(Modifier.height(14.dp))

        SettingsCard {
            Text("펫 종류", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SInk)
            Spacer(Modifier.height(10.dp))
            PetSelector(selected = vm.petType, onSelectDog = { vm.changePetType("dog") })
        }
        Spacer(Modifier.height(14.dp))

        SettingsCard {
            // 미접속 리마인드(1차 검토 피드백 — 이탈 방어). 기기 로컬 알림이라 서버 설정(#73)과 무관하게
            //   AppSettings 계열이 아닌 InactivityReminder 가 직접 들고 있고, 서버 저장도 하지 않는다.
            SettingsToggleRow(
                label = "다시 알림",
                checked = remindersEnabled,
                onChange = { on ->
                    remindersEnabled = on
                    InactivityReminder.setEnabled(context, on)
                    if (on) {
                        ReminderWorker.schedule(context)
                        askNotificationPermission(context, notificationPermission)
                    } else {
                        ReminderWorker.cancel(context)
                    }
                },
                description = "며칠 동안 앱을 열지 않으면 알려드려요",
            )
            // ⚠️ 기본값이 켜짐이라 **토글을 건드릴 일이 없는 사용자는 권한을 물을 기회조차 없다**(리뷰 P1).
            //   그러면 켜져 있다고 믿는 채로 알림은 영영 오지 않는다. '원하는 상태'(토글)와 '실제 전달
            //   가능 상태'(권한·채널)를 함께 드러내되, 토글을 자동으로 바꾸지는 않는다(리뷰 - 지영).
            recoveryAction?.let { action ->
                Spacer(Modifier.height(8.dp))
                Text(
                    InactivityReminder.permissionNotice(action),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    // 채널을 끈 것은 사용자의 선택일 수 있어 오류색으로 되묻지 않는다 — 상태 안내 톤.
                    color = if (action == InactivityReminder.RecoveryAction.OPEN_CHANNEL_SETTINGS) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // 시니어 대상 — 얇은 텍스트는 누르기 어려우므로 최소 높이를 확보한다.
                        .heightIn(min = 44.dp)
                        .clickable {
                            when (action) {
                                InactivityReminder.RecoveryAction.REQUEST_PERMISSION ->
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                InactivityReminder.RecoveryAction.OPEN_APP_SETTINGS ->
                                    openAppNotificationSettings(context)
                                InactivityReminder.RecoveryAction.OPEN_CHANNEL_SETTINGS ->
                                    openReminderChannelSettings(context)
                            }
                        },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsCard {
            // '배경 음악'은 앱 전체에 음악이 깔린다는 뜻으로 읽혀 헷갈린다는 피드백. 실제로 이 토글이
            //   끄고 켜는 것은 운동 따라하기 화면의 루틴 BGM 하나뿐이라(RoutinePlayerScreen), 대상을
            //   이름에 드러내고 한 줄 설명을 덧붙인다.
            SettingsToggleRow(
                label = "운동 음악",
                checked = vm.musicEnabled,
                onChange = vm::changeMusicEnabled,
                description = "운동 따라하기 화면에서 나오는 음악이에요",
            )
        }
        Spacer(Modifier.height(14.dp))

        SettingsCard(contentPadding = 0.dp) {
            // 시안 확정: 두 행은 각각 '다른' 화면으로 간다(예전엔 둘 다 합쳐진 고객센터 한 화면으로 갔음).
            SettingsLinkRow(Icons.AutoMirrored.Filled.HelpOutline, "자주 묻는 질문", onOpenFaq)
            SettingsDivider()
            SettingsLinkRow(Icons.Filled.HeadsetMic, "고객센터", onOpenSupport)
            SettingsDivider()
            SettingsInfoRow(Icons.Filled.Info, "앱 버전", BuildConfig.VERSION_NAME)
        }
        Spacer(Modifier.height(18.dp))

        SettingsOutlineButton("로그아웃") { showLogoutConfirm = true }

        // 회원탈퇴(#356) — 계정 액션 최하단. 되돌릴 수 없는 파괴적 액션이라 로그아웃(아웃라인 버튼)보다
        //   한 단계 약한 텍스트 버튼 + 오류색으로 두어, 일상 동작과 시각적으로 구분한다.
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { showWithdrawConfirm = true },
            enabled = !vm.withdrawing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (vm.withdrawing) "탈퇴 처리 중…" else "회원탈퇴",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // #131 파형 수집 진입 — 디버그 빌드에서만 노출(릴리스에는 대상 Activity 자체가 없음).
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(10.dp))
            SettingsOutlineButton("🔧 파형 수집 (디버그 · #131)") {
                runCatching {
                    context.startActivity(
                        Intent().setClassName(
                            context,
                            "com.aihealthcare.ah0404.sensor.WaveformCaptureActivity",
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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

    if (showWithdrawConfirm) {
        AigoDialog(
            title = "회원탈퇴",
            // 서버가 연결 데이터를 실제 삭제하고 재로그인 시 신규 가입이 되므로(#356 옵션 2),
            //   '삭제됨'과 '복구되지 않음'을 둘 다 명시한다.
            message = "탈퇴하면 그동안의 기록·포인트가 모두 삭제되고 되돌릴 수 없어요.\n" +
                "같은 계정으로 다시 로그인하면 처음부터 새로 시작하게 돼요.",
            confirmText = "탈퇴하기",
            onConfirm = {
                showWithdrawConfirm = false
                vm.withdraw(onWithdrawn, onWithdrawUncertain)
            },
            onDismissRequest = { showWithdrawConfirm = false },
            dismissText = "취소",
        )
    }

    vm.withdrawError?.let { msg ->
        AigoDialog(
            title = "알림",
            message = msg,
            confirmText = "확인",
            onConfirm = vm::dismissWithdrawError,
            onDismissRequest = vm::dismissWithdrawError,
        )
    }

    // 결과 불명 안내는 여기(설정 화면 다이얼로그)에 두지 않는다(리뷰 2차 P1): 인터셉터의 전역 라우팅이
    //   이 화면·VM 을 먼저 폐기할 수 있어, 정리는 vm.withdraw 의 catch 에서 즉시 시작되고 안내는
    //   Activity 범위(AuthLoginViewModel)가 로그인 화면에 남긴다.

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

/** 설정 공통 카드: 바탕보다 살짝 짙게 + 테두리(흰색 지양 — 사용자 피드백). */
@Composable
private fun SettingsCard(contentPadding: androidx.compose.ui.unit.Dp = 18.dp, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SCardFill,
        border = BorderStroke(1.dp, SCardBorder),
    ) {
        Column(Modifier.padding(contentPadding)) { content() }
    }
}

/** '내 정보' 진입 카드: 아이콘 + 제목/부제 + 화살표. */
@Composable
private fun SettingsNavCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SCardFill,
        border = BorderStroke(1.dp, SCardBorder),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(SGreenTint),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = SGreen, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SInk)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 14.sp, lineHeight = 19.sp, color = SMuted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SMuted)
        }
    }
}

/** 3지 세그먼트(작게/보통/크게). 선택 = 초록 강조 알약. */
@Composable
private fun SettingSegment(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (value, label) ->
            val sel = selected == value
            androidx.compose.material3.Surface(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (sel) SGreenTint else Color.Transparent,
                border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) SGreen else SUnselBorder),
            ) {
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) SGreen else SInk,
                )
            }
        }
    }
}

/** 펫 선택: 강아지(선택 가능) / 고양이(준비 중, 비활성 — 시안). */
@Composable
private fun PetSelector(selected: String, onSelectDog: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val dogSel = selected == "dog"
        androidx.compose.material3.Surface(
            onClick = onSelectDog,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = if (dogSel) SGreenTint else Color.Transparent,
            border = BorderStroke(if (dogSel) 1.5.dp else 1.dp, if (dogSel) SGreen else SUnselBorder),
        ) {
            Text(
                "강아지",
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (dogSel) SGreen else SInk,
            )
        }
        // 고양이: 준비 중(비활성) — 시안 최종구조.
        androidx.compose.material3.Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, SUnselBorder),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("고양이", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = SMuted)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = Color(0xFFEBEAE3)) {
                    Text("준비 중", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SMuted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SInk)
            description?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = SMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SGreen,
            ),
        )
    }
}

@Composable
private fun SettingsLinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SGreen, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = SInk, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SMuted)
    }
}

@Composable
private fun SettingsInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = SInk, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = SMuted)
    }
}

@Composable
private fun SettingsDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        color = SCardBorder,
    )
}

@Composable
private fun SettingsOutlineButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFCFBF6),
        border = BorderStroke(1.5.dp, SGreen),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SGreen)
        }
    }
}

/** 다른 화면(고객센터/내정보 등)에서 재사용하는 상단바 — 유지. */
@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
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


/** 프레임워크에서 사실만 읽어 순수 함수([InactivityReminder.recoveryAction])에 넘긴다. */
private fun notificationRecoveryAction(
    context: Context,
    remindersEnabled: Boolean,
): InactivityReminder.RecoveryAction? {
    val supportsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val granted = !supportsRuntime ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val activity = context as? Activity
    return InactivityReminder.recoveryAction(
        rowState = InactivityReminder.rowState(
            enabled = remindersEnabled,
            appNotificationsAllowed = granted && NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelEnabled = ReminderWorker.channelEnabled(context),
        ),
        supportsRuntimePermission = supportsRuntime,
        permissionGranted = granted,
        hasAsked = InactivityReminder.hasAskedPermission(context),
        shouldShowRationale = activity != null && supportsRuntime &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
    )
}

/** Android 13+ 에서만 런타임 요청이 의미가 있다. 이미 허용됐으면 아무것도 하지 않는다. */
private fun askNotificationPermission(context: Context, launcher: ActivityResultLauncher<String>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * '다시 알림' **채널 설정으로 바로** 보낸다(리뷰 - 지영). 앱 알림 설정으로 보내고 목록에서 채널을
 * 찾게 하면 시니어 사용자에게는 사실상 막힌 길이다. 채널 설정은 API 26+ 이므로 그 아래는 앱 설정으로.
 */
private fun openReminderChannelSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        openAppNotificationSettings(context)
        return
    }
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // 일부 기기는 이 인텐트를 처리하지 못한다 — 그때는 앱 알림 설정으로 떨어뜨린다.
    runCatching { context.startActivity(intent) }.onFailure { openAppNotificationSettings(context) }
}

/** 요청 팝업이 더 이상 뜨지 않을 때의 마지막 수단 — 앱 알림 설정 화면으로 보낸다. */
private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
