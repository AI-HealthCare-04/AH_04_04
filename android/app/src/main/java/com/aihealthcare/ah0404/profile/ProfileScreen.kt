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
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.HealthProfileLatest
import com.aihealthcare.ah0404.network.OnbEnums
import com.aihealthcare.ah0404.network.UserInfoResponse
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.AigoTextField
import com.aihealthcare.ah0404.ui.components.SegmentOption
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
    healthVm: HealthInfoViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load(); healthVm.load() }

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
            // 신체 정보 편집(#기록탭 §2) — 키/몸무게/허리/신장건강정보.
            HealthInfoSection(healthVm)
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
    healthVm.saveError?.let { msg ->
        AigoDialog(
            title = "알림",
            message = msg,
            confirmText = "확인",
            onConfirm = healthVm::dismissSaveError,
            onDismissRequest = healthVm::dismissSaveError,
        )
    }
    healthVm.savedMessage?.let { msg ->
        AigoDialog(
            title = "저장 완료",
            message = msg,
            confirmText = "확인",
            onConfirm = healthVm::dismissSavedMessage,
            onDismissRequest = healthVm::dismissSavedMessage,
        )
    }
}

/** 신체 정보 편집 카드(#기록탭 §2). 키·몸무게·허리둘레·신장건강정보 수정 → 새 스냅샷 저장. */
@Composable
private fun HealthInfoSection(healthVm: HealthInfoViewModel) {
    val profile = healthVm.profile
    when {
        healthVm.error && profile == null -> ErrorCard(onRetry = healthVm::load)
        profile == null -> LoadingCard()
        else -> HealthInfoEditor(healthVm, profile)
    }
}

private val KIDNEY_LABELS = mapOf(
    "none" to "해당 없음",
    "kidney_disease" to "신장질환 있음",
    "unknown" to "잘 모르겠어요",
)

// 단백질 제한(#304): 온보딩과 동일 라벨. 신장과 함께 고단백 미션 게이트를 정하므로 내정보에서도 편집 가능해야
//   신장을 '없음'으로 되돌렸을 때 미션이 다시 뜬다.
private val PROTEIN_LABELS = mapOf(
    "none" to "해당 없음",
    "restricted" to "제한 중",
    "unknown" to "잘 모르겠어요",
)

@Composable
private fun HealthInfoEditor(healthVm: HealthInfoViewModel, profile: HealthProfileLatest) {
    // 편집 상태는 profile 이 갱신되면 초기화(저장 후 최신값 반영).
    var height by remember(profile) { mutableStateOf(numberText(profile.heightCm)) }
    var weight by remember(profile) { mutableStateOf(numberText(profile.weightKg)) }
    var waist by remember(profile) { mutableStateOf(profile.waistCm?.let(::numberText) ?: "") }
    var kidney by remember(profile) {
        mutableStateOf(if (profile.kidneyStatus == "dialysis") "kidney_disease" else profile.kidneyStatus)
    }
    var protein by remember(profile) { mutableStateOf(profile.proteinRestrictionStatus) }

    AigoCard {
        Text("신체 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space12))
        AigoTextField(height, { height = it }, "키 (cm)", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(Dimens.Space8))
        AigoTextField(weight, { weight = it }, "몸무게 (kg)", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(Dimens.Space8))
        AigoTextField(waist, { waist = it }, "허리둘레 (cm, 선택)", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(Dimens.Space12))
        Text("신장 건강 정보", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Dimens.Space8))
        AigoSegmentedSelector(
            options = OnbEnums.KIDNEY_STATUS.map { SegmentOption(it, KIDNEY_LABELS[it] ?: it) },
            selected = kidney,
            onSelect = { kidney = it },
        )
        Spacer(Modifier.height(Dimens.Space12))
        // 단백질 제한(#304): 신장과 함께 고단백 미션 게이트라 여기서 되돌릴 수 있어야 미션이 다시 뜬다.
        Text("단백질 제한", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Dimens.Space8))
        AigoSegmentedSelector(
            options = OnbEnums.PROTEIN_RESTRICTION_STATUS.map { SegmentOption(it, PROTEIN_LABELS[it] ?: it) },
            selected = protein,
            onSelect = { protein = it },
        )
        Spacer(Modifier.height(Dimens.Space16))
        AigoPrimaryButton(
            text = if (healthVm.saving) "저장 중…" else "저장",
            onClick = { healthVm.save(height, weight, waist, kidney, protein) {} },
            enabled = !healthVm.saving,
        )
        Spacer(Modifier.height(Dimens.Space8))
        // 하단 고정 안내(§2) — 재평가 상태별로 문구를 실제 동작과 일치시킨다(리뷰 #294:
        //   저장 즉시 반영 시도, 422/점수 미제공/네트워크 실패를 구분. 종전 "다음 …부터 반영"은
        //   재평가 배선 후 사실이 아니게 되어 교체).
        Text(
            scoreRefreshFooterText(healthVm.scoreRefresh),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (healthVm.scoreRefresh == HealthInfoViewModel.ScoreRefreshState.FAILED) {
            Spacer(Modifier.height(Dimens.Space8))
            // 네트워크·서버 실패만 재시도 의미가 있다(422·점수 미제공은 재시도해도 같아 버튼 없음).
            AigoSecondaryButton(text = "점수 다시 계산", onClick = healthVm::retryScoreRefresh)
        }
    }
}

/** 하단 고정 안내 문구 — 재평가 상태별(리뷰 #294 상태 경계). 문구 회귀는 테스트로 고정한다. */
internal fun scoreRefreshFooterText(state: HealthInfoViewModel.ScoreRefreshState?): String = when (state) {
    null -> "저장하면 수정한 정보로 근육 건강 점수를 바로 다시 계산해요."
    HealthInfoViewModel.ScoreRefreshState.IN_PROGRESS -> "저장한 정보로 근육 건강 점수를 다시 계산하고 있어요…"
    HealthInfoViewModel.ScoreRefreshState.APPLIED -> "근육 건강 정보에 바로 반영됐어요."
    HealthInfoViewModel.ScoreRefreshState.NOT_ELIGIBLE -> "정보는 저장됐어요. 지금은 근육 점수 제공 대상이 아니에요."
    HealthInfoViewModel.ScoreRefreshState.FAILED -> "정보는 저장됐어요. 점수 다시 계산에 실패했어요 — 아래 버튼으로 다시 시도해 주세요."
}

/** 소수 반올림 없는 표시용 문자열: 170.0 → "170", 63.5 → "63.5". */
private fun numberText(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

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
