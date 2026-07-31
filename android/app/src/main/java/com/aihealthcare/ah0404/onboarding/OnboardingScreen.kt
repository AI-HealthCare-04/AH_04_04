package com.aihealthcare.ah0404.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.auth.AuthLoginUiState
import com.aihealthcare.ah0404.auth.AuthLoginViewModel
import com.aihealthcare.ah0404.auth.SocialProvider
import com.aihealthcare.ah0404.auth.SocialSignInClients
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.fitness.StsAssessmentScreen
import com.aihealthcare.ah0404.fitness.formatStsSeconds
import com.aihealthcare.ah0404.ui.components.AigoCheckboxRow
import com.aihealthcare.ah0404.ui.components.AigoDayStepper
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.AigoTextField
import com.aihealthcare.ah0404.ui.components.AigoTonalButton
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.components.SegmentOption
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 온보딩 흐름 호스트 — 역할분담 §3-정인, 작업순서 §4-②.
 *  S0 진입 → S1 약관 → S3 프로필 → S4 체력검사(or 스킵) → S5 결과(disclaimer 필수).
 *  완료 시 onComplete(isGuest) 로 메인 화면 진입(#153: 게스트면 영속화 스킵).
 *
 *  @param onComplete 온보딩 완주(결과 → 홈). isGuest 를 넘겨 영속화 여부를 호출부가 가른다.
 *  @param onReroute 이미 완료된 소셜 계정이 로그인함 → 온보딩을 건너뛰고 홈으로 라우팅 재평가(#153).
 */
@Composable
fun OnboardingScreen(
    onComplete: (isGuest: Boolean) -> Unit,
    onReroute: () -> Unit,
    onBrowseDemo: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    vm: OnboardingViewModel = viewModel(),
    authVm: AuthLoginViewModel = viewModel(),
) {
    val activity = LocalContext.current as Activity
    val authState by authVm.state.collectAsState()
    // 화면 진입 시 stale 상태 복구(#153 후속): 토큰이 없는데(로그아웃·세션리셋) 이 Activity-수명 VM 에
    //   이전 온보딩 step(예: RESULT)이 남아 있으면 WELCOME 으로 되돌린다. 안 그러면 '홈으로 시작하기'가
    //   토큰 없는 완료로 처리돼 LOGIN_REQUIRED ↔ 리셋 사이를 도는 무한루프가 생긴다.
    LaunchedEffect(Unit) {
        if (TokenHolder.token.isBlank() && vm.step != OnbStep.WELCOME) {
            vm.resetToWelcome()
        }
    }
    var showExitConfirmation by remember { mutableStateOf(false) }
    // 소셜 로그인 결과 분기(#153): 완료 계정은 약관을 건너뛰고 홈으로, 미완료 계정은 온보딩(약관)을 이어감.
    val onSocialLogin: (SocialProvider) -> Unit = { provider ->
        authVm.signIn(provider, activity) { completed ->
            if (completed) onReroute() else vm.continueAuthenticated()
        }
    }
    // 로딩 중에도 BackHandler를 등록해 시스템 기본 뒤로가기(Activity 종료)로 전파되지 않게 한다.
    BackHandler {
        if (vm.loading || authState.loading != null) return@BackHandler
        if (!vm.goBack()) showExitConfirmation = true
    }
    Box(
        modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        when (vm.step) {
            OnbStep.WELCOME -> WelcomeStep(
                vm = vm,
                authState = authState,
                onGoogleLogin = { onSocialLogin(SocialProvider.GOOGLE) },
                onKakaoLogin = { onSocialLogin(SocialProvider.KAKAO) },
                onSkipToDemo = onBrowseDemo,
            )
            OnbStep.TERMS -> TermsStep(vm)
            OnbStep.PROFILE -> ProfileStep(vm)
            OnbStep.ASSESSMENT -> AssessmentStep(vm)
            OnbStep.RESULT -> ResultStep(vm, onComplete)
        }

        if (vm.loading || authState.loading != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        vm.error?.let { msg ->
            AigoDialog(
                title = "알림",
                message = msg,
                confirmText = "확인",
                onConfirm = vm::dismissError,
                onDismissRequest = vm::dismissError,
            )
        }

        if (showExitConfirmation) {
            AigoDialog(
                title = "앱을 종료할까요?",
                message = if (vm.step == OnbStep.RESULT) {
                    "결과 화면을 닫고 앱을 종료할까요?"
                } else {
                    "입력 중인 온보딩을 나가면 다시 이어서 진행할 수 없어요."
                },
                confirmText = "종료",
                onConfirm = onExit,
                dismissText = "계속하기",
                onDismiss = { showExitConfirmation = false },
                onDismissRequest = { showExitConfirmation = false },
            )
        }
    }
}

/** 공통 단계 레이아웃: 스크롤 본문 + 하단 버튼 영역. */
@Composable
private fun StepScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            Spacer(Modifier.height(Dimens.Space8))
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text("← 이전")
                }
            }
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Dimens.Space8))
            content()
            Spacer(Modifier.height(Dimens.Space16))
        }
        footer()
    }
}

@Composable
private fun WelcomeStep(
    vm: OnboardingViewModel,
    authState: AuthLoginUiState,
    onGoogleLogin: () -> Unit,
    onKakaoLogin: () -> Unit,
    onSkipToDemo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Aigo", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(Dimens.Space12))
        Text(
            "매일 조금씩, 건강하게.\n간단한 몇 가지만 확인하고 시작해요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space32))
        AigoPrimaryButton(
            text = "Google로 시작하기",
            onClick = onGoogleLogin,
            enabled = SocialSignInClients.googleConfigured && authState.loading == null,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoSecondaryButton(
            text = "카카오로 시작하기",
            onClick = onKakaoLogin,
            enabled = SocialSignInClients.kakaoConfigured && authState.loading == null,
        )
        Spacer(Modifier.height(Dimens.Space12))
        AigoTonalButton(text = "체험으로 시작하기", onClick = vm::start, enabled = authState.loading == null)
        authState.message?.let { message ->
            Spacer(Modifier.height(Dimens.Space12))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        // 개발/데모 전용: debug 빌드에서만 노출(리뷰 #63 P1-1 — 목업/우회 진입은 debug 로 제한).
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(Dimens.Space12))
            TextButton(onClick = onSkipToDemo) {
                Text("둘러보기 (데모 화면, 개발용)", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TermsStep(vm: OnboardingViewModel) {
    StepScaffold(
        title = "약관 동의",
        subtitle = "서비스 이용을 위해 아래 약관에 동의해 주세요.",
        onBack = { vm.goBack() },
        content = {
            AigoTonalButton(text = "전체 동의", onClick = vm::agreeAll)
            Spacer(Modifier.height(Dimens.Space8))
            val context = LocalContext.current
            var showTermsOpenError by remember { mutableStateOf(false) }
            vm.terms.forEach { term ->
                val label = (term.title ?: term.termsType) +
                    if (term.isRequired) "  (필수)" else "  (선택)"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AigoCheckboxRow(
                        checked = vm.agreed.contains(term.termsType),
                        onCheckedChange = { vm.toggleAgree(term.termsType) },
                        label = label,
                        modifier = Modifier.weight(1f),
                    )
                    // 약관 전문 열람(#244 §2): 동의 전에 전문을 볼 수단이 없으면 심사·법적 관점 결격.
                    //   서버가 준 버전 URL(자체 호스팅 /terms/<버전>, #268)을 기본 브라우저로 연다.
                    //   버튼 이름은 약관 제목 포함(리뷰 #303: 반복되는 '보기'는 TalkBack 에서 구분 불가).
                    val url = term.url
                    if (!url.isNullOrBlank()) {
                        val a11yLabel = termsViewA11yLabel(term.title, term.termsType)
                        TextButton(
                            onClick = { if (!openTermsUrl(context, url)) showTermsOpenError = true },
                            modifier = Modifier.semantics { contentDescription = a11yLabel },
                        ) {
                            Text("보기", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (showTermsOpenError) {
                // 조용한 실패 금지(리뷰 #303): 이유 모른 채 전문을 못 본 상태로 동의하지 않도록 안내한다.
                AigoDialog(
                    title = "약관 내용을 열 수 없어요",
                    message = "잠시 후 다시 시도해 주세요. 계속 안 되면 네트워크 상태를 확인해 주세요.",
                    confirmText = "확인",
                    onConfirm = { showTermsOpenError = false },
                    onDismissRequest = { showTermsOpenError = false },
                )
            }
        },
        footer = {
            AigoPrimaryButton(
                text = "동의하고 계속",
                onClick = vm::submitAgreements,
                enabled = vm.allRequiredAgreed,
            )
        },
    )
}

/** TalkBack 용 약관 보기 버튼 이름 — 제목을 포함해 어느 약관의 전문인지 구분되게 한다(리뷰 #303). */
internal fun termsViewA11yLabel(title: String?, termsType: String): String =
    "${title ?: termsType} 전문 보기"

/**
 * 약관 URL 허용 검증(리뷰 #303): 서버 환경변수에서 온 단순 문자열이므로 intent:// 등 임의 스킴·
 * 호스트가 올 수 있다 — **https + 운영 약관 호스트**만 외부 인텐트로 넘긴다. 파싱 실패는 거부.
 */
internal fun isAllowedTermsUrl(url: String, allowedHost: String): Boolean =
    try {
        val parsed = java.net.URI(url)
        parsed.scheme?.lowercase() == "https" && parsed.host?.lowercase() == allowedHost.lowercase()
    } catch (_: Exception) {
        false
    }

/** 앱이 신뢰하는 약관 호스트 = API 호스트(BuildConfig 파생 — debug/release 모두 동일 기준). */
private val termsAllowedHost: String by lazy {
    runCatching { java.net.URI(BuildConfig.API_BASE_URL).host }.getOrNull().orEmpty()
}

/**
 * 약관 전문을 기본 브라우저로 연다. 성공 여부를 반환 — 실패(비허용 URL·브라우저 부재·SecurityException 등)
 * 시 호출부가 사용자에게 오류를 표시한다(조용한 실패 금지, 리뷰 #303).
 */
private fun openTermsUrl(context: Context, url: String): Boolean {
    if (!isAllowedTermsUrl(url, termsAllowedHost)) {
        Log.w("Onboarding", "약관 URL 허용 검증 실패(스킴/호스트): $url")
        return false
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Onboarding", "약관 전문 열기 실패(브라우저 없음): $url")
        false
    } catch (e: Exception) {
        Log.w("Onboarding", "약관 전문 열기 실패(${e.javaClass.simpleName}): $url")
        false
    }
}

/** 숫자 입력 + 오른쪽 '모름' 버튼. '모름' 누르면 추정치로 채워지고, 채워졌으면 안내 문구를 보여준다. */
@Composable
private fun FieldWithUnknown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onUnknown: () -> Unit,
    estimated: Boolean,
    unknownEnabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        verticalAlignment = Alignment.Top,
    ) {
        AigoTextField(value, onValueChange, label, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        OutlinedButton(
            onClick = onUnknown,
            enabled = unknownEnabled,
            modifier = Modifier.height(Dimens.ButtonHeight),
        ) {
            Text("모름", style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (estimated) {
        Text(
            "추정치로 입력했어요. 정확한 값을 아시면 직접 입력해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileStep(vm: OnboardingViewModel) {
    StepScaffold(
        title = "건강 프로필",
        subtitle = "맞춤 미션을 위해 기본 정보를 알려주세요.",
        onBack = { vm.goBack() },
        content = {
            Text("생년월일", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
                AigoTextField(vm.birthYear, { vm.birthYear = it }, "년", Modifier.weight(1.3f), keyboardType = KeyboardType.Number)
                AigoTextField(vm.birthMonth, { vm.birthMonth = it }, "월", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                AigoTextField(vm.birthDay, { vm.birthDay = it }, "일", Modifier.weight(1f), keyboardType = KeyboardType.Number)
            }

            Text("성별", style = MaterialTheme.typography.titleMedium)
            AigoSegmentedSelector(
                options = listOf(SegmentOption("male", "남성"), SegmentOption("female", "여성")),
                selected = vm.sex,
                onSelect = { vm.sex = it },
                horizontal = true,
            )

            // 키·몸무게: 정확히 모르면 '모름' → 성별·연령대 추정치(제출 시 최종값으로 계산, has_estimated_value=true).
            //   '모름'은 성별·생년월일 입력 후에만 활성(그 값으로 추정하므로).
            FieldWithUnknown(
                value = vm.heightInput,
                onValueChange = vm::setHeight,
                label = "키 (cm)",
                onUnknown = vm::markHeightUnknown,
                estimated = vm.heightEstimated,
                unknownEnabled = vm.canEstimate,
            )
            FieldWithUnknown(
                value = vm.weightInput,
                onValueChange = vm::setWeight,
                label = "몸무게 (kg)",
                onUnknown = vm::markWeightUnknown,
                estimated = vm.weightEstimated,
                unknownEnabled = vm.canEstimate,
            )
            FieldWithUnknown(
                value = vm.waistCm,
                onValueChange = { vm.waistCm = it },
                label = "허리둘레 (cm, 선택)",
                onUnknown = vm::markWaistUnknown,
                estimated = false,
                unknownEnabled = true,
            )

            Text("일주일에 며칠 걷기 운동을 하세요?", style = MaterialTheme.typography.titleMedium)
            AigoDayStepper(value = vm.walkDays, onValueChange = { vm.walkDays = it }, max = 7)

            Text("일주일에 며칠 근력 운동을 하세요?", style = MaterialTheme.typography.titleMedium)
            AigoDayStepper(
                value = vm.muscDays,
                onValueChange = { vm.muscDays = it },
                max = 5,
                maxLabel = "5일 이상",
            )

            Text("신장 상태", style = MaterialTheme.typography.titleMedium)
            AigoSegmentedSelector(
                options = listOf(
                    SegmentOption("none", "해당 없음"),
                    SegmentOption("kidney_disease", "신장질환 있음"),
                    SegmentOption("dialysis", "투석 중"),
                    SegmentOption("unknown", "잘 모르겠어요"),
                ),
                selected = vm.kidneyStatus,
                onSelect = { vm.kidneyStatus = it },
            )

            Text("단백질 제한", style = MaterialTheme.typography.titleMedium)
            AigoSegmentedSelector(
                options = listOf(
                    SegmentOption("none", "해당 없음"),
                    SegmentOption("restricted", "제한 중"),
                    SegmentOption("unknown", "잘 모르겠어요"),
                ),
                selected = vm.proteinStatus,
                onSelect = { vm.proteinStatus = it },
            )
        },
        footer = {
            AigoPrimaryButton(text = "다음", onClick = vm::submitProfile)
        },
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AssessmentStep(vm: OnboardingViewModel) {
    // 측정 전용(#300): 값의 유일한 출처는 가이드 측정(StsAssessmentScreen). 수동 입력칸은 두지 않는다 —
    //   빈 입력칸이 "직접 재야 하나?" 혼란을 주고, 직접 입력을 열면 대충 값을 넣어 측정 의미가 사라지기 때문.
    var measuring by remember { mutableStateOf(false) }
    if (measuring) {
        StsAssessmentScreen(
            // 측정 완료 시 소요 초를 값에 채운다. 재측정이면 새 측정이 이전 값을 덮어쓴다(취소하면 이전 값 유지).
            onMeasured = { sec -> vm.chairStandSec = formatStsSeconds(sec); measuring = false },
            onCancel = { measuring = false },
        )
        return
    }
    // 측정 완료 = 유효한 측정값 존재. 값이 있으면 읽기 전용으로 보여주고 '검사 완료'를 노출한다.
    val measuredSeconds = parseChairStandSeconds(vm.chairStandSec)
    val measured = measuredSeconds != null
    StepScaffold(
        title = "간단 체력 검사",
        subtitle = "어려우면 건너뛰어도 괜찮아요. 나중에 언제든 할 수 있어요.",
        onBack = { vm.goBack() },
        content = {
            Text("영상을 따라 5번 앉았다 일어서면, 걸린 시간을 재드려요.", style = MaterialTheme.typography.bodyLarge)
            if (measured) {
                Spacer(Modifier.height(Dimens.Space12))
                // 측정 결과 읽기 전용 표시(수동 편집 불가, #300). 값은 측정으로만 바뀐다.
                Text(
                    "측정 결과: ${vm.chairStandSec}초",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                if (measured) {
                    // 측정 후: 다시 측정 · 검사 완료 · 건너뛰기.
                    AigoPrimaryButton(text = "다시 측정하기", onClick = { measuring = true })
                    AigoSecondaryButton(
                        text = "검사 완료",
                        onClick = { measuredSeconds?.let(vm::submitAssessment) },
                    )
                    AigoSecondaryButton(text = "건너뛰기", onClick = vm::skipAssessment)
                } else {
                    // 측정 전: 측정 · 건너뛰기만(검사 완료 없음).
                    AigoPrimaryButton(text = "따라하며 측정하기", onClick = { measuring = true })
                    AigoSecondaryButton(text = "건너뛰기", onClick = vm::skipAssessment)
                }
            }
        },
    )
}

@Composable
private fun ResultStep(vm: OnboardingViewModel, onComplete: (isGuest: Boolean) -> Unit) {
    val r = vm.result
    val (emoji, title) = when (r?.careStage) {
        "good" -> "👍" to "아주 좋아요!"
        "action_needed" -> "💪" to "조금만 더 함께 챙겨봐요"
        else -> "🙂" to "잘 유지하고 있어요"
    }
    StepScaffold(
        title = "$emoji  $title",
        content = {
            Text(
                text = r?.displayMessage ?: "오늘부터 가볍게 시작해 볼까요?",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Dimens.Space16))
            // 결과 화면 필수 고지(§0-3): 서버 disclaimer 있으면 그대로, 없으면 기본 문구.
            MedicalDisclaimer(text = r?.disclaimer ?: MEDICAL_DISCLAIMER_DEFAULT)
        },
        footer = {
            AigoPrimaryButton(text = "홈으로 시작하기", onClick = { onComplete(vm.isGuest) })
        },
    )
}
