package com.aihealthcare.ah0404.onboarding

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.BuildConfig
import com.aihealthcare.ah0404.ui.components.AigoCheckboxRow
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
 *  완료 시 onComplete() 로 메인 화면 진입.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onBrowseDemo: () -> Unit,
    modifier: Modifier = Modifier,
    vm: OnboardingViewModel = viewModel(),
) {
    Box(
        modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        when (vm.step) {
            OnbStep.WELCOME -> WelcomeStep(vm, onBrowseDemo)
            OnbStep.TERMS -> TermsStep(vm)
            OnbStep.PROFILE -> ProfileStep(vm)
            OnbStep.ASSESSMENT -> AssessmentStep(vm)
            OnbStep.RESULT -> ResultStep(vm, onComplete)
        }

        if (vm.loading) {
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
    }
}

/** 공통 단계 레이아웃: 스크롤 본문 + 하단 버튼 영역. */
@Composable
private fun StepScaffold(
    title: String,
    subtitle: String? = null,
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
private fun WelcomeStep(vm: OnboardingViewModel, onSkipToDemo: () -> Unit) {
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
        AigoPrimaryButton(text = "시작하기", onClick = vm::start)
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
        content = {
            AigoTonalButton(text = "전체 동의", onClick = vm::agreeAll)
            Spacer(Modifier.height(Dimens.Space8))
            vm.terms.forEach { term ->
                val label = (term.title ?: term.termsType) +
                    if (term.isRequired) "  (필수)" else "  (선택)"
                AigoCheckboxRow(
                    checked = vm.agreed.contains(term.termsType),
                    onCheckedChange = { vm.toggleAgree(term.termsType) },
                    label = label,
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
    val yesNo = listOf(SegmentOption(true, "예"), SegmentOption(false, "아니요"))
    StepScaffold(
        title = "건강 프로필",
        subtitle = "맞춤 미션을 위해 기본 정보를 알려주세요.",
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

            Text("최근 걷기 운동을 하고 있나요?", style = MaterialTheme.typography.titleMedium)
            AigoSegmentedSelector(yesNo, vm.walkingPractice, { vm.walkingPractice = it }, horizontal = true)

            Text("최근 근력 운동을 하고 있나요?", style = MaterialTheme.typography.titleMedium)
            AigoSegmentedSelector(yesNo, vm.strengthExercise, { vm.strengthExercise = it }, horizontal = true)

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

@Composable
private fun AssessmentStep(vm: OnboardingViewModel) {
    var chairStand by remember { mutableStateOf("") }
    StepScaffold(
        title = "간단 체력 검사",
        subtitle = "어려우면 건너뛰어도 괜찮아요. 나중에 언제든 할 수 있어요.",
        content = {
            Text("의자에서 5번 앉았다 일어서기 (초)", style = MaterialTheme.typography.titleMedium)
            AigoTextField(chairStand, { chairStand = it }, "예: 12.5", keyboardType = KeyboardType.Decimal)
        },
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                AigoPrimaryButton(
                    text = "검사 완료",
                    onClick = {
                        vm.submitAssessment(chairStand.toDoubleOrNull())
                    },
                )
                AigoSecondaryButton(text = "건너뛰기", onClick = vm::skipAssessment)
            }
        },
    )
}

@Composable
private fun ResultStep(vm: OnboardingViewModel, onComplete: () -> Unit) {
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
            AigoPrimaryButton(text = "홈으로 시작하기", onClick = onComplete)
        },
    )
}
