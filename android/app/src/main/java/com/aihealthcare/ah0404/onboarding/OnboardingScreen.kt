package com.aihealthcare.ah0404.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import com.aihealthcare.ah0404.network.Term
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
    //   이전 온보딩 step(예: ASSESSMENT)이나 완주 신호가 남아 있으면 WELCOME 으로 되돌린다. 안 그러면 토큰 없는
    //   완료로 처리돼 LOGIN_REQUIRED ↔ 리셋 사이를 도는 무한루프가 생긴다.
    LaunchedEffect(Unit) {
        if (TokenHolder.token.isBlank() && vm.step != OnbStep.WELCOME) {
            vm.resetToWelcome()
        }
    }
    // 온보딩 완주(#299): 체력검사 제출/스킵 → 예측 생성이 끝나면 별도 결과화면 없이 곧장 홈으로. 완료는 step 이
    //   아니라 finished 플래그로 알린다. false→true 전이에 한 번만 홈 라우팅(onComplete).
    LaunchedEffect(vm.finished) {
        if (vm.finished) onComplete(vm.isGuest)
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
                message = "입력 중인 온보딩을 나가면 다시 이어서 진행할 수 없어요.",
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
    // 로그인 화면 디자인 고도화: 브랜드 헤더 + 강아지 히어로 카드 + 버튼 + 문의(시안 반영). 기능 배선은 그대로 유지.
    val bg = Color(0xFFF5F6F2)
    val titleGreen = Color(0xFF2E6B45)
    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        // 브랜드 헤더는 Compose 텍스트로 구현: 시안 워드마크의 크기·색을 맞춘다. 이미지가 아니라 텍스트라
        //   큰글꼴(fontScale) 접근성이 유지된다(시니어 UI). 잎·하트는 텍스트와 함께 확대됨.
        // 브랜드 헤더: 점선 아치·좌우 반짝임(✦) 장식을 Canvas 로 배경에 깔고 그 위에 워드마크 텍스트를 얹는다(시안 반영).
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HeaderDecoration(Modifier.matchParentSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌿", fontSize = 28.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "아직도 이렇게 고우시네",
                    color = Color(0xFF3B4A40),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                // '아이고'는 화면의 주인공 — 설명 문구(19sp)의 ~3.5배(66sp)로 크게 + 세로 그라데이션(고급감).
                //   두께는 Black 웨이트만 사용(짙은 스트로크 테두리는 안 예뻐서 제거). 줄간격은 Trim.Both 로 촘촘히.
                Text(
                    "아이고",
                    fontSize = 66.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    lineHeight = 66.sp,
                    style = TextStyle(
                        brush = Brush.verticalGradient(listOf(Color(0xFF43976A), Color(0xFF1E5A38))),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "함께 시작하는 즐거운 건강 습관 💚",
                    color = Color(0xFF5C8A6C),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 22.sp, // 큰글꼴(fontScale)에서 2줄로 줄바꿈될 때 줄 간격 확보(시니어 UI)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Card(
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_login_hero),
                contentDescription = "아이고 로그인 대표 이미지 — 산책하는 강아지",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
        // 구글: 녹색 세로 그라데이션 pill(입체감). 흰 원 안에 4색 공식 'G'. Material3 Button 대신 Box 사용 —
        //   Button+elevation 은 표면 오버레이가 겹쳐 가운데 밝은 띠(선)가 생겼다. Box 는 그라데이션 한 겹만 그린다.
        SocialLoginButton(
            text = "구글로 시작하기",
            enabled = SocialSignInClients.googleConfigured && authState.loading == null,
            onClick = onGoogleLogin,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        // 카카오: 버튼은 녹색 그대로, 공식 심볼은 '노란 원(#FEE500) + 갈색 말풍선' 뱃지로만 넣는다(브랜드 준수).
        SocialLoginButton(
            text = "카카오로 시작하기",
            enabled = SocialSignInClients.kakaoConfigured && authState.loading == null,
            onClick = onKakaoLogin,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFFEE500)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_kakao),
                    contentDescription = null,
                    tint = Color(0xFF3C1E1E),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = vm::start,
            enabled = authState.loading == null,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.5.dp, titleGreen),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = titleGreen),
        ) { Text("체험으로 시작하기", fontSize = 20.sp) }
        authState.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text("🛡 안전하고 간편하게 시작하세요", color = Color(0xFF9AA59D), fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Text("처음이신가요?", color = titleGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "회원가입/로그인 관련 문의",
            color = titleGreen,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        )
        // 개발/데모 전용: debug 빌드에서만 노출(리뷰 #63 P1-1 — 목업/우회 진입은 debug 로 제한).
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSkipToDemo) {
                Text("둘러보기 (데모 화면, 개발용)", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 소셜 로그인 버튼(구글·카카오 공용): 녹색 세로 그라데이션 pill + 그림자로 입체감, 왼쪽에 브랜드 뱃지.
 * Material3 Button 을 쓰지 않는 이유 — Button 의 Surface(elevation) 오버레이가 그라데이션 위에 겹쳐
 * 버튼 가운데에 밝은 가로 띠(선)가 보였다. Box 로 그라데이션을 한 겹만 그려 그 아티팩트를 없앤다.
 */
@Composable
private fun SocialLoginButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF4FA672), Color(0xFF2E6B45))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart)) { badge() }
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/**
 * 로그인 헤더 배경 장식(시안 반영): 워드마크 뒤로 지나가는 완만한 점선 아치 + 좌우 반짝임(✦).
 * 은은한 세이지 톤이라 텍스트 가독성을 해치지 않는다. 크기는 헤더 Box 에 matchParentSize 로 맞춘다.
 */
@Composable
private fun HeaderDecoration(modifier: Modifier = Modifier) {
    val deco = Color(0xFFBBD6C2)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // 점선 아치(위로 볼록한 완만한 곡선). 점 느낌을 위해 짧은 dash + Round cap.
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 8.dp.toPx()), 0f)
        drawArc(
            color = deco,
            startAngle = 184f,
            sweepAngle = 172f,
            useCenter = false,
            topLeft = Offset(w * 0.05f, h * 0.16f),
            size = Size(w * 0.90f, h * 0.66f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash),
        )
        // 좌우 반짝임(✦): 아치 끝단 근처.
        val sy = h * 0.52f
        drawSparkle(Offset(w * 0.05f, sy), 9.dp.toPx(), deco)
        drawSparkle(Offset(w * 0.95f, sy), 9.dp.toPx(), deco)
    }
}

/** 4갈래 반짝임(✦) 채움 도형. center·radius(px) 기준으로 별 모양 Path 를 그린다. */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.30f
    val pts = listOf(
        Offset(center.x, center.y - radius),
        Offset(center.x + inner, center.y - inner),
        Offset(center.x + radius, center.y),
        Offset(center.x + inner, center.y + inner),
        Offset(center.x, center.y + radius),
        Offset(center.x - inner, center.y + inner),
        Offset(center.x - radius, center.y),
        Offset(center.x - inner, center.y - inner),
    )
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
    drawPath(path, color)
}

// 약관 화면 디자인 고도화 팔레트(시안 토큰). 기능 배선(vm.terms/agreed/agreeAll/toggleAgree/submitAgreements)은 그대로.
private val TermsBg = Color(0xFFF7F6F0)
private val TermsGreen = Color(0xFF1F5D3A)
private val TermsInk = Color(0xFF202321)
private val TermsMuted = Color(0xFF6B726D)
// 필드·드롭다운·카드 공통: 흰색 대신 바탕색(TermsBg)에 맞추고 테두리로 구분(통일감 — 사용자 피드백).
private val TermsFieldBorder = Color(0xFFC9CFC4)

@Composable
private fun TermsStep(vm: OnboardingViewModel) {
    val context = LocalContext.current
    var showTermsOpenError by remember { mutableStateOf(false) }
    val required = vm.terms.filter { it.isRequired }
    val optional = vm.terms.filterNot { it.isRequired }
    val allChecked = vm.terms.isNotEmpty() && vm.terms.all { vm.agreed.contains(it.termsType) }
    // 약관 전문 열기(#244 §2 / #303): url 이 있으면 https·운영 호스트만 외부 인텐트로. 실패는 조용히 넘기지 않고 안내.
    val openTerm: (Term) -> Unit = { term ->
        val url = term.url
        if (!url.isNullOrBlank() && !openTermsUrl(context, url)) showTermsOpenError = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TermsBg),
    ) {
        // 상단 바: ← 약관 동의
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 화면", tint = TermsGreen)
            }
            Text("약관 동의", color = TermsGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "서비스 이용을 위해\n아래 약관에 동의해 주세요.",
                fontSize = 26.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                color = TermsInk,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "필수 약관에 동의해야 다음 단계로 이동할 수 있어요.",
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = TermsMuted,
            )
            Spacer(Modifier.height(24.dp))
            TermsSelectAllCard(
                checked = allChecked,
                onToggle = { checked ->
                    // vm 에 '전체 해제' API 가 없어 UI 에서 처리(기능 변경 없이 기존 toggleAgree 로 해제).
                    if (checked) vm.agreeAll()
                    else vm.terms.forEach { if (vm.agreed.contains(it.termsType)) vm.toggleAgree(it.termsType) }
                },
            )
            if (required.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                TermsGroupCard(required, vm, openTerm)
            }
            if (optional.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TermsGroupCard(optional, vm, openTerm)
            }
            Spacer(Modifier.height(18.dp))
            TermsSecurityNotice()
            Spacer(Modifier.height(24.dp))
        }
        // 하단 CTA
        Column(
            Modifier
                .background(TermsBg)
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = vm::submitAgreements,
                enabled = vm.allRequiredAgreed,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TermsGreen,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFE4E4E2),
                    disabledContentColor = Color(0xFFAAAFAA),
                ),
            ) {
                Text("동의하고 계속", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("🔒 안전한 연결로 보호됩니다.", color = Color(0xFF9AA59D), fontSize = 13.sp)
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
}

/** '전체 동의' 강조 카드(연녹 배경 + 우하단 잎 장식). 탭 시 전체 동의/해제 토글. */
@Composable
private fun TermsSelectAllCard(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Checkbox }
            .clickable { onToggle(!checked) },
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFE7F3EA),
        border = BorderStroke(1.dp, Color(0xFFCEE5D3)),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "🌿",
                fontSize = 40.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 8.dp)
                    .alpha(0.30f),
            )
            Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onToggle,
                    colors = CheckboxDefaults.colors(checkedColor = TermsGreen),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("전체 동의", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TermsGreen)
                    Spacer(Modifier.height(4.dp))
                    Text("필수 및 선택 약관에 모두 동의합니다.", fontSize = 14.sp, lineHeight = 20.sp, color = TermsMuted)
                }
            }
        }
    }
}

/** 약관 그룹(필수/선택) 카드 — 흰 카드 안에 항목 행들을 구분선으로 나눠 담는다. */
@Composable
private fun TermsGroupCard(
    items: List<Term>,
    vm: OnboardingViewModel,
    onOpen: (Term) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TermsBg,
        border = BorderStroke(1.dp, TermsFieldBorder),
    ) {
        Column {
            items.forEachIndexed { index, term ->
                TermsRow(
                    term = term,
                    checked = vm.agreed.contains(term.termsType),
                    onCheckedChange = { vm.toggleAgree(term.termsType) },
                    onOpen = { onOpen(term) },
                )
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        color = Color(0xFFE5E7E3),
                    )
                }
            }
        }
    }
}

/** 약관 한 줄: 체크박스 + 제목 + 필수/선택 배지 + 설명 + 전문 열기(>). */
@Composable
private fun TermsRow(
    term: Term,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val title = term.title ?: term.termsType
    val hasDoc = !term.url.isNullOrBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = TermsGreen),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .then(if (hasDoc) Modifier.clickable(onClick = onOpen) else Modifier),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2E302F),
                )
                Spacer(Modifier.width(8.dp))
                TermsRequirementBadge(required = term.isRequired)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                termDescription(term),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = TermsMuted,
            )
        }
        if (hasDoc) {
            IconButton(
                onClick = onOpen,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = termsViewA11yLabel(term.title, term.termsType) },
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TermsMuted)
            }
        }
    }
}

/** 필수/선택 배지(필: 연녹, 선택: 회색). */
@Composable
private fun TermsRequirementBadge(required: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (required) Color(0xFFE7F3EA) else Color(0xFFF0F1EF),
    ) {
        Text(
            if (required) "필수" else "선택",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = if (required) TermsGreen else TermsMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 하단 보안 안내(방패 문구). */
@Composable
private fun TermsSecurityNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TermsBg,
        border = BorderStroke(1.dp, TermsFieldBorder),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🛡", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                "고객님의 정보는 안전하게 보호되며,\n동의 내용은 언제든지 변경할 수 있습니다.",
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = TermsMuted,
            )
        }
    }
}

/** 약관 항목 설명문(서버 Term 엔 설명 필드가 없어 termsType 로 시안 문구를 매핑). */
private fun termDescription(term: Term): String = when (term.termsType) {
    "service" -> "서비스 이용과 관련된 필수 약관입니다."
    "privacy" -> "개인정보 처리와 보호에 관한 내용입니다."
    "sensitive_health" -> "건강정보 수집·이용에 관한 필수 동의입니다."
    "marketing" -> "유용한 정보와 혜택을 받아보실 수 있어요."
    else -> if (term.isRequired) "서비스 이용에 필요한 필수 약관입니다." else "선택 동의 항목입니다."
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

/**
 * 숫자 입력 + 오른쪽 '모름' 버튼.
 *  - [error]: 값이 현실 범위를 벗어나면 그 자리에서 인라인 경고(#298 A-2).
 *  - [unknownReason]: '모름'이 비활성일 때 **왜 못 누르는지** 안내(#298 B).
 *  - [unknownNote]: '모름'을 누른 뒤 무슨 일이 일어났는지 안내. null 이면 표시하지 않는다.
 *
 * ⚠️ [unknownNote] 는 원래 `estimated: Boolean` 이었고 "추정치로 입력했어요" 한 문구만 낼 수 있었다.
 *   '모름'의 의미가 항목마다 다르기 때문에 문구를 호출부가 정하도록 바꿨다 —
 *   키·몸무게는 '추정치를 채운다', 허리둘레는 '이 항목을 빼고 넘어간다' 로 동작이 정반대다.
 *   허리둘레는 값이 비어 있는 게 보통이라, 안내가 없으면 눌러도 화면이 전혀 바뀌지 않아
 *   버튼이 고장 난 것처럼 보였다.
 */
@Composable
private fun FieldWithUnknown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onUnknown: () -> Unit,
    unknownEnabled: Boolean,
    unknownNote: String? = null,
    error: String? = null,
    unknownReason: String? = null,
) {
    val focusManager = LocalFocusManager.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        verticalAlignment = Alignment.Top,
    ) {
        AigoTextField(value, onValueChange, label, Modifier.weight(1f), keyboardType = KeyboardType.Number, isError = error != null)
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                onUnknown()
            },
            enabled = unknownEnabled,
            modifier = Modifier.height(Dimens.ButtonHeight),
        ) {
            Text("모름", style = MaterialTheme.typography.bodyLarge)
        }
    }
    error?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    unknownNote?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!unknownEnabled) {
        unknownReason?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileStep(vm: OnboardingViewModel) {
    // 디자인 고도화: 한 화면에 있던 프로필 입력을 시안대로 3단계 위저드로 '페이지네이션'만 한다(입력·검증·제출 배선 동일).
    //   1) 기본정보(생년월일·성별·키/몸무게/허리) 2) 활동습관(걷기·근력) 3) 건강확인(신장·단백질).
    var subStep by remember { mutableStateOf(0) }
    // 서브스텝 사이 뒤로가기는 이전 페이지로. 첫 페이지에선 비활성 → 부모 BackHandler(약관으로) 가 처리.
    BackHandler(enabled = subStep > 0) { subStep-- }

    // 제목은 외래어 없이 우리말로 쓴다(시니어 대상). '프로필'은 설정에서 같은 데이터를 이미 '내 정보'로
    //   부르고 있어 용어가 갈리기도 했다. 1·3단계가 모두 '건강'으로 시작해 구분이 약하던 것도 함께 정리.
    val (title, subtitle) = when (subStep) {
        0 -> "내 몸 정보" to "맞춤 미션을 위해 기본 정보를 알려주세요."
        1 -> "활동 습관" to "평소 운동 습관을 알려주세요."
        else -> "건강 상태" to "식사와 건강 상태를 확인할게요."
    }
    // 페이지별 '다음' 활성 조건(마지막은 submitProfile 이 전체 검증).
    val step0Valid = vm.birthDateError == null &&
        vm.birthYear.isNotBlank() && vm.birthMonth.isNotBlank() && vm.birthDay.isNotBlank() &&
        vm.sex != null &&
        vm.heightError == null && (vm.heightInput.isNotBlank() || vm.heightEstimatedValid) &&
        vm.weightError == null && (vm.weightInput.isNotBlank() || vm.weightEstimatedValid)
    val step1Valid = vm.walkDays != null && vm.muscDays != null
    val ctaEnabled = when (subStep) {
        0 -> step0Valid
        1 -> step1Valid
        else -> true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TermsBg),
    ) {
        // 진행 표시(1/3 · 2/3 · 3/3) + 서브스텝>0 이면 뒤로.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (subStep > 0) {
                IconButton(onClick = { subStep-- }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 단계", tint = TermsGreen)
                }
                Spacer(Modifier.width(8.dp))
            }
            ProfileProgress(current = subStep, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = TermsInk)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, fontSize = 16.sp, lineHeight = 22.sp, color = TermsMuted)
            Spacer(Modifier.height(22.dp))
            when (subStep) {
                0 -> ProfileBasicInfo(vm)
                1 -> ProfileActivity(vm)
                else -> ProfileHealthCheck(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier
                .background(TermsBg)
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 8.dp),
        ) {
            Button(
                onClick = { if (subStep < 2) subStep++ else vm.submitProfile() },
                enabled = ctaEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TermsGreen,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFE4E4E2),
                    disabledContentColor = Color(0xFFAAAFAA),
                ),
            ) {
                Text(if (subStep < 2) "다음" else "완료", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 3단계 진행 표시: 연결된 점 3개(현재까지 초록) + 'N / 3' 라벨. */
@Composable
private fun ProfileProgress(current: Int, total: Int = 3, modifier: Modifier = Modifier) {
    val gray = Color(0xFFD4DAD3)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until total) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (i <= current) TermsGreen else gray),
                )
                if (i < total - 1) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .padding(horizontal = 6.dp)
                            .background(if (i < current) TermsGreen else gray),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Text("${current + 1} / $total", color = TermsGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** 1단계: 생년월일(드롭다운) · 성별(알약) · 키/몸무게/허리(모름). */
@Composable
private fun ProfileBasicInfo(vm: OnboardingViewModel) {
    val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    val years = remember(currentYear) { (currentYear - 13 downTo currentYear - 100).map { it.toString() to it.toString() } }
    val months = remember { (1..12).map { it.toString() to "${it}월" } }
    val days = remember { (1..31).map { it.toString() to "${it}일" } }
    val birthError = vm.birthDateError

    Text("생년월일", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TermsInk)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileDropdown(vm.birthYear, "년", years, { vm.birthYear = it }, Modifier.weight(1.3f))
        ProfileDropdown(vm.birthMonth, "월", months, { vm.birthMonth = it }, Modifier.weight(1f))
        ProfileDropdown(vm.birthDay, "일", days, { vm.birthDay = it }, Modifier.weight(1f))
    }
    birthError?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
    }
    vm.underAgeNotice?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = TermsMuted)
    }

    Spacer(Modifier.height(22.dp))
    Text("성별", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TermsInk)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SexPill("남성", vm.sex == "male", { vm.sex = "male" }, Modifier.weight(1f))
        SexPill("여성", vm.sex == "female", { vm.sex = "female" }, Modifier.weight(1f))
    }

    Spacer(Modifier.height(18.dp))
    // 이 안내는 '모름'이 평균치를 채운다고 말한다 — 키·몸무게에만 해당한다. 허리둘레의 '모름'은
    //   값을 채우지 않고 항목을 생략하므로, 대상을 문장 앞에 못박아 오해를 막는다.
    Text(
        "키·몸무게는 '모름'을 누르면 평균치가 자동으로 입력돼요. 정확한 예측을 위해 가급적 직접 입력해 주세요.",
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = TermsMuted,
    )
    Spacer(Modifier.height(12.dp))
    FieldWithUnknown(
        value = vm.heightInput,
        onValueChange = vm::setHeight,
        label = "키 (cm)",
        onUnknown = vm::markHeightUnknown,
        unknownEnabled = vm.canEstimate,
        unknownNote = ESTIMATE_FILLED_NOTE.takeIf { vm.heightEstimatedValid },
        error = vm.heightError,
        unknownReason = vm.estimateUnavailableReason,
    )
    FieldWithUnknown(
        value = vm.weightInput,
        onValueChange = vm::setWeight,
        label = "몸무게 (kg)",
        onUnknown = vm::markWeightUnknown,
        unknownEnabled = vm.canEstimate,
        unknownNote = ESTIMATE_FILLED_NOTE.takeIf { vm.weightEstimatedValid },
        error = vm.weightError,
        unknownReason = vm.estimateUnavailableReason,
    )
    FieldWithUnknown(
        value = vm.waistCm,
        onValueChange = { vm.waistCm = it; vm.waistSkipped = false },
        label = "허리둘레 (cm, 선택)",
        onUnknown = vm::markWaistUnknown,
        unknownEnabled = true,
        unknownNote = WAIST_SKIPPED_NOTE.takeIf { vm.waistSkipped },
    )
}

/** 키·몸무게 '모름' → 추정치를 채운 뒤의 안내. */
private const val ESTIMATE_FILLED_NOTE = "추정치로 입력했어요. 정확한 값을 아시면 직접 입력해 주세요."

/**
 * 허리둘레 '모름' → 항목을 빼고 넘어간 뒤의 안내.
 *
 * 허리둘레는 예측 모델의 주요 입력이라(같은 BMI 라도 허리둘레로 근육/지방이 갈린다) 있고 없고에 따라
 * 다른 모델을 쓴다. 다만 검증 결과의 신뢰구간이 겹치므로 "훨씬 정확해진다"고 말하지 않는다.
 * 추정으로 채우는 선택지는 없다 — 대치한 허리값은 허리 제외 모델보다 나은 성능을 보이지 못했다
 * (docs/ml/sarcopenia_validation_awgs2025_summary.md).
 */
private const val WAIST_SKIPPED_NOTE =
    "나중에 입력해도 괜찮아요. 지금은 이대로 넘어갈게요. " +
        "줄자로 재서 입력하시면 더 정확한 예측에 도움이 돼요(설정 → 내 정보에서 언제든 추가할 수 있어요)."

/** 2단계: 걷기·근력 주당 일수(스텝퍼 카드). */
@Composable
private fun ProfileActivity(vm: OnboardingViewModel) {
    ActivityStepperCard(
        question = "일주일에 며칠 걷기 운동을 하세요?",
        hint = "가벼운 산책이나 걷기 운동을 포함해요.",
        value = vm.walkDays,
        onValueChange = { vm.walkDays = it },
        max = 7,
    )
    Spacer(Modifier.height(16.dp))
    ActivityStepperCard(
        question = "일주일에 며칠 근력 운동을 하세요?",
        hint = "아령, 밴드, 기구 운동 등을 포함해요.",
        value = vm.muscDays,
        onValueChange = { vm.muscDays = it },
        max = 5,
        maxLabel = "5일 이상",
    )
}

/** 3단계: 신장 상태(4지) · 단백질 제한(3지) 라디오. '투석 중'은 백엔드 지원 값(dialysis) — 시안대로 노출. */
@Composable
private fun ProfileHealthCheck(vm: OnboardingViewModel) {
    ProfileRadioSection(
        title = "신장 건강 상태",
        options = listOf(
            "none" to "해당 없음",
            "kidney_disease" to "신장질환 있음",
            "dialysis" to "투석 중",
            "unknown" to "잘 모르겠어요",
        ),
        selected = vm.kidneyStatus,
        onSelect = { vm.kidneyStatus = it },
    )
    Spacer(Modifier.height(16.dp))
    ProfileRadioSection(
        title = "단백질 제한",
        options = listOf(
            "none" to "해당 없음",
            "restricted" to "제한 중",
            "unknown" to "잘 모르겠어요",
        ),
        selected = vm.proteinStatus,
        onSelect = { vm.proteinStatus = it },
    )
}

/** 성별 알약 버튼(선택 시 초록 강조). */
@Composable
private fun SexPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color(0xFFE7F3EA) else Color.Transparent,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) TermsGreen else TermsFieldBorder),
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) TermsGreen else TermsInk,
        )
    }
}

/** 드롭다운(생년월일용): 흰 필드 + 아래 화살표, 탭하면 항목 메뉴. value=저장값, items=(값,표시). */
@Composable
private fun ProfileDropdown(
    value: String,
    placeholder: String,
    items: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = items.firstOrNull { it.first == value }?.second
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = TermsBg,
            border = BorderStroke(1.dp, TermsFieldBorder),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label ?: placeholder,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    color = if (label == null) TermsMuted else TermsInk,
                    maxLines = 1,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TermsMuted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); expanded = false })
            }
        }
    }
}

/** 활동 스텝퍼 카드: 질문 + (− 값 +) + 보조문구. 스텝 로직은 기존 AigoDayStepper 재사용. */
@Composable
private fun ActivityStepperCard(
    question: String,
    hint: String,
    value: Int?,
    onValueChange: (Int) -> Unit,
    max: Int,
    maxLabel: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TermsBg,
        border = BorderStroke(1.dp, TermsFieldBorder),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                question,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Bold,
                color = TermsInk,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            AigoDayStepper(value = value, onValueChange = onValueChange, max = max, maxLabel = maxLabel)
            Spacer(Modifier.height(12.dp))
            Text(hint, fontSize = 13.sp, color = TermsMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** 라디오 섹션(신장/단백질): 흰 카드 안에 제목 + 라디오 행들. 선택 행은 초록 강조. */
@Composable
private fun ProfileRadioSection(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = TermsBg,
        border = BorderStroke(1.dp, TermsFieldBorder),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TermsInk)
            Spacer(Modifier.height(10.dp))
            options.forEach { (value, label) ->
                val sel = selected == value
                Surface(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (sel) Color(0xFFE7F3EA) else Color.Transparent,
                    // 통일성: 선택 안 한 항목에도 테두리(사용자 피드백).
                    border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) TermsGreen else TermsFieldBorder),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = sel,
                            onClick = { onSelect(value) },
                            colors = RadioButtonDefaults.colors(selectedColor = TermsGreen),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontSize = 16.sp,
                            color = if (sel) TermsGreen else TermsInk,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
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
    // 5STS '시작/준비' 화면 디자인 고도화(시안). 실제 측정 화면(StsAssessmentScreen)은 규칙대로 현행 유지.
    Column(Modifier.fillMaxSize().background(TermsBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.goBack() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전", tint = TermsGreen)
            Spacer(Modifier.width(6.dp))
            Text("이전", color = TermsGreen, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text("간단 체력 검사", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TermsInk)
            Spacer(Modifier.height(10.dp))
            Text("의자에서 5번 앉았다 일어나는 시간을 재요.", fontSize = 16.sp, lineHeight = 22.sp, color = TermsMuted)
            Spacer(Modifier.height(22.dp))
            if (measured) {
                // 측정 완료 결과 카드(시안 after).
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = TermsBg,
                    border = BorderStroke(1.dp, TermsFieldBorder),
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("측정 결과", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TermsGreen)
                        Spacer(Modifier.height(8.dp))
                        Text("${vm.chairStandSec}초", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = TermsGreen)
                        Spacer(Modifier.height(8.dp))
                        Text("측정이 완료되었어요.", fontSize = 15.sp, color = TermsMuted)
                    }
                }
            } else {
                // 측정 전 안내 카드(시안 before).
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = TermsBg,
                    border = BorderStroke(1.dp, TermsFieldBorder),
                ) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("의자에서\n5번 일어나요", fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, color = TermsGreen)
                            Spacer(Modifier.height(10.dp))
                            Text("영상을 따라 천천히 해보세요.", fontSize = 15.sp, lineHeight = 21.sp, color = TermsMuted)
                            Spacer(Modifier.height(14.dp))
                            Surface(color = Color(0xFFE7F3EA), shape = RoundedCornerShape(50)) {
                                Text("약 1분", color = TermsGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        // 측정 화면(StsAssessmentScreen)에서 쓰는 '선 자세' 사진을 그대로 사용(사용자 요청).
                        val standingPose = com.aihealthcare.ah0404.fitness.rememberAssetImageBitmap("fitness/sts_standing.jpg")
                        if (standingPose != null) {
                            Image(
                                bitmap = standingPose,
                                contentDescription = "일어서는 자세",
                                modifier = Modifier
                                    .width(104.dp)
                                    .aspectRatio(0.75f)
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text("👵", fontSize = 64.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Column(
            Modifier
                .background(TermsBg)
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (measured) {
                AssessPrimaryButton("검사 완료") { measuredSeconds?.let(vm::submitAssessment) }
                AssessOutlineButton("다시 측정하기") { measuring = true }
                TextButton(onClick = vm::skipAssessment) {
                    Text("건너뛰기", fontSize = 16.sp, color = TermsMuted, textDecoration = TextDecoration.Underline)
                }
            } else {
                AssessPrimaryButton("따라하며 측정하기") { measuring = true }
                AssessOutlineButton("건너뛰기", vm::skipAssessment)
            }
        }
    }
}

@Composable
private fun AssessPrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TermsGreen, contentColor = Color.White),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AssessOutlineButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, TermsGreen),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TermsGreen),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

