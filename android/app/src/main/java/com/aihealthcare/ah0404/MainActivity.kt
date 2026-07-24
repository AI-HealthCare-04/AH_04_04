package com.aihealthcare.ah0404

import android.os.Bundle
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.auth.AuthLoginViewModel
import com.aihealthcare.ah0404.auth.SocialProvider
import com.aihealthcare.ah0404.auth.SocialSignInClients
import com.aihealthcare.ah0404.auth.LoginRequiredScreen
import com.aihealthcare.ah0404.auth.OfflineModeScreen
import com.aihealthcare.ah0404.exercise.ExerciseVideosScreen
import com.aihealthcare.ah0404.home.HomeScreen
import com.aihealthcare.ah0404.mission.ComingSoonScreen
import com.aihealthcare.ah0404.mission.MissionDestination
import com.aihealthcare.ah0404.mission.MissionScreen
import com.aihealthcare.ah0404.mission.WalkingMeasureScreen
import com.aihealthcare.ah0404.mission.missionDestination
import com.aihealthcare.ah0404.network.AppRoute
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.AppRouteResolver
import com.aihealthcare.ah0404.network.AuthFailureCoordinator
import com.aihealthcare.ah0404.network.JwtTokenInspector
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.network.rememberNetworkAvailable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.aihealthcare.ah0404.onboarding.OnboardingScreen
import com.aihealthcare.ah0404.profile.ProfileScreen
import com.aihealthcare.ah0404.record.RecordScreen
import com.aihealthcare.ah0404.settings.SettingsScreen
import com.aihealthcare.ah0404.settings.SupportScreen
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme

/**
 * 걷기 측정 오버레이가 열려 있는가(#188). 열려 있는 동안엔 네트워크가 끊겨도 OFFLINE 로 튕기지 않는다:
 * 걷기 측정은 센서만 쓰므로 오프라인에서도 이어져야 하고(야외 음영지역), 인터넷은 마지막 저장에만 필요해
 * 저장 실패만 재시도(#91)로 처리한다. 오버레이 진입/이탈에 맞춰 MainContent 가 갱신한다.
 */
private object WalkingOverlay {
    var active by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 저장된 토큰 복원(리뷰 #63 P1-2). 완료 사용자는 온보딩을 건너뛴다.
        SessionStore.restore(this)
        // 마지막으로 고른 글자·소리 크기를 시작 즉시 전역 적용(묶음 C-2).
        com.aihealthcare.ah0404.settings.AppSettings.load(this)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val activity = context as Activity
                val authLoginViewModel: AuthLoginViewModel = viewModel()
                val authLoginState by authLoginViewModel.state.collectAsState()
                var demoMode by remember { mutableStateOf(false) }
                var sessionRevision by remember { mutableIntStateOf(0) }
                val networkAvailable by rememberNetworkAvailable()
                val authFailure by AuthFailureCoordinator.failure.collectAsState()
                val tokenStatus = remember(sessionRevision) {
                    JwtTokenInspector.inspect(TokenHolder.token)
                }
                val route = if (demoMode) {
                    AppRoute.MAIN
                } else {
                    AppRouteResolver.resolve(
                        // 라우팅 게이트는 메모리 세션(#153): 게스트 완주는 이번 세션만 MAIN(디스크엔 무영속),
                        //   소셜 완료만 재실행에도 유지된다. 시작 시 restore() 가 디스크값으로 초기화한다.
                        onboardingCompleted = SessionStore.sessionOnboarded,
                        tokenStatus = tokenStatus,
                        networkAvailable = networkAvailable,
                        failure = authFailure,
                        // #188: 걷기 측정 중이면 네트워크 단절로 OFFLINE 로 안 튕긴다(로직은 resolver 가 처리 — 테스트 가능).
                        walkingActive = WalkingOverlay.active,
                    )
                }

                LaunchedEffect(route) {
                    if (route == AppRoute.LOGIN_REQUIRED && TokenHolder.token.isNotBlank()) {
                        SessionStore.clearAuthentication(context)
                        sessionRevision++
                    }
                }

                when (route) {
                    AppRoute.ONBOARDING -> OnboardingScreen(
                        // 완주(결과 → 홈): 게스트면 디스크 무영속, 소셜이면 토큰+완료 저장(#153).
                        onComplete = { isGuest ->
                            SessionStore.markOnboarded(context, isGuest)
                            sessionRevision++
                        },
                        // 이미 완료된 소셜 계정 로그인 → applyLogin 이 게이트를 세웠으니 라우팅만 재평가(→ 홈).
                        onReroute = { sessionRevision++ },
                        onBrowseDemo = {
                            demoMode = true
                        },
                        onExit = activity::finish,
                    )
                    AppRoute.LOGIN_REQUIRED -> LoginRequiredScreen(
                        onGoogleLogin = {
                            // 재로그인은 완료된 소셜 계정만 도달 → applyLogin 이 저장·게이트 처리, 라우팅만 재평가.
                            authLoginViewModel.signIn(SocialProvider.GOOGLE, activity) { _ -> sessionRevision++ }
                        },
                        onKakaoLogin = {
                            authLoginViewModel.signIn(SocialProvider.KAKAO, activity) { _ -> sessionRevision++ }
                        },
                        onExit = activity::finish,
                        onResetSession = {
                            SessionStore.resetSession(context)
                            sessionRevision++
                        },
                        loading = authLoginState.loading,
                        message = authLoginState.message,
                        googleEnabled = SocialSignInClients.googleConfigured,
                        kakaoEnabled = SocialSignInClients.kakaoConfigured,
                    )
                    AppRoute.OFFLINE -> OfflineModeScreen(
                        onRetry = {
                            AuthFailureCoordinator.retryTransientFailure()
                            sessionRevision++
                        },
                        onExit = activity::finish,
                    )
                    // 로그아웃(#154, #187): 앱 토큰 + 공급자(Google/Kakao) credential 을 해제한다.
                    //   공급자 해제는 viewModelScope 에서 돌아 리라우팅으로 화면이 떠나도 완료된다(#187).
                    //   토큰만 지우고 온보딩 완료 플래그는 보존 → 같은 계정 재로그인 시 온보딩을 반복하지 않는다.
                    //   해제가 끝나면 sessionRevision++ 로 라우팅을 재평가(→ LOGIN_REQUIRED)시킨다.
                    //   ⚠️ 위 LaunchedEffect(L103)의 stale-token 자동정리는 같은 사용자 재로그인 편의를 위해
                    //      공급자 credential 을 일부러 유지한다(명시적 로그아웃일 때만 공급자까지 해제).
                    AppRoute.MAIN -> MainContent(
                        onLogout = {
                            authLoginViewModel.signOut { sessionRevision++ }
                        },
                        onExit = activity::finish,
                    )
                }
            }
        }
    }
}

internal enum class MainTab(val label: String) {
    HOME("홈"),
    MISSIONS("미션"),
    RECORDS("기록"),
    SETTINGS("설정"),
}

private val MainTab.icon: ImageVector
    get() = when (this) {
        MainTab.HOME -> Icons.Default.Home
        MainTab.MISSIONS -> Icons.AutoMirrored.Filled.List
        MainTab.RECORDS -> Icons.Default.History
        MainTab.SETTINGS -> Icons.Default.Settings
    }

// 걷기 오버레이 진입 상태(어느 미션인지)를 구성 변경에 보존하기 위한 Saver.
// Mission 은 @Serializable(kotlinx) 이라 JSON 문자열로 저장/복원한다(Parcelable 불필요).
// ⚠️ "구성 변경"은 회전이 아니다: #135 에서 screenOrientation=portrait 로 세로 고정해 기기를
//    돌려도 Activity 재생성이 없다. 실제 재생성 트리거는 글꼴 크기·다크모드·멀티윈도우·폴더블
//    접기/펴기·언어 변경 등이며, 이 Saver 는 그때 진입 상태를 유지한다.
private val MissionJson = Json { ignoreUnknownKeys = true }
private val MissionStateSaver: Saver<Mission?, String> = Saver(
    save = { mission -> mission?.let { MissionJson.encodeToString(it) } },
    restore = { MissionJson.decodeFromString<Mission>(it) },
)

// 하단 탭 선택도 구성 변경에 보존한다. remember 로 두면 재생성 시 HOME 으로 초기화돼,
// 측정 오버레이가 복원된 상태에서 이탈(leave)하는 순간 미션 탭이 아니라 홈이 나타난다(#144 리뷰
// 블로커 2). 준비중 오버레이(#147 comingSoonMission)도 같은 증상이라 이 Saver 로 함께 해결된다.
// enum 은 Bundle 자동 저장 대상이 아니므로 이름 문자열로 명시 저장/복원한다.
private val MainTabSaver: Saver<MainTab, String> = Saver(
    save = { it.name },
    restore = { MainTab.valueOf(it) },
)

internal enum class MainBackAction {
    RETURN_HOME,
    CONFIRM_EXIT,
}

internal fun mainBackAction(selectedTab: MainTab): MainBackAction =
    if (selectedTab == MainTab.HOME) {
        MainBackAction.CONFIRM_EXIT
    } else {
        MainBackAction.RETURN_HOME
    }

@androidx.media3.common.util.UnstableApi
@Composable
private fun MainContent(
    onLogout: () -> Unit,
    onExit: () -> Unit,
) {
    // rememberSaveable: 재생성(글꼴 크기·다크모드 등) 후에도 이탈 시 복귀할 탭을 보존한다.
    // 오버레이(walkingMission)만 복원하고 이 탭을 remember 로 두면 홈으로 튄다(#144 블로커 2).
    var selectedTab by rememberSaveable(stateSaver = MainTabSaver) { mutableStateOf(MainTab.HOME) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }

    // 걷기 측정 화면(전체 화면 오버레이). 미션 목록에서 걷기 미션을 고르면 진입. null = 목록.
    // rememberSaveable: 재생성(글꼴 크기·다크모드 등) 후에도 측정 화면을 유지한다(세션 값은 VM 이
    // 보존). 회전은 #135 세로 고정으로 재생성이 없어 이 경로가 실행되지 않는다.
    var walkingMission by rememberSaveable(stateSaver = MissionStateSaver) {
        mutableStateOf<Mission?>(null)
    }
    // #188: 걷기 측정 오버레이가 열려 있는 동안 라우팅이 OFFLINE 로 튕기지 않게 상태를 알린다.
    //   (구성 변경으로 walkingMission 이 복원돼도 여기서 다시 동기화된다.)
    LaunchedEffect(walkingMission) { WalkingOverlay.active = walkingMission != null }
    walkingMission?.let { mission ->
        WalkingMeasureScreen(
            mission = mission,
            onBack = { walkingMission = null },
        )
        return
    }

    // '준비 중' 오버레이(#93). 수행 화면이 아직 없는 유형(운동·식사·게임)을 누르면 진입.
    var comingSoonMission by rememberSaveable(stateSaver = MissionStateSaver) {
        mutableStateOf<Mission?>(null)
    }
    comingSoonMission?.let { mission ->
        ComingSoonScreen(
            mission = mission,
            onBack = { comingSoonMission = null },
        )
        return
    }

    // 탭 위에 얹히는 서브 화면(프로필/고객센터/운동 영상). null = 탭 화면.
    var subScreen by remember { mutableStateOf<String?>(null) }
    when (subScreen) {
        "profile" -> {
            BackHandler { subScreen = null }
            ProfileScreen(onBack = { subScreen = null })
            return
        }
        "support" -> {
            BackHandler { subScreen = null }
            SupportScreen(onBack = { subScreen = null })
            return
        }
        "exercise" -> {
            BackHandler { subScreen = null }
            ExerciseVideosScreen(onBack = { subScreen = null })
            return
        }
    }

    BackHandler {
        when (mainBackAction(selectedTab)) {
            MainBackAction.RETURN_HOME -> selectedTab = MainTab.HOME
            MainBackAction.CONFIRM_EXIT -> showExitConfirmation = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)

        when (selectedTab) {
            MainTab.HOME -> HomeScreen(
                onGoMissions = { selectedTab = MainTab.MISSIONS },
                onOpenSettings = { selectedTab = MainTab.SETTINGS },
                onOpenRecords = { selectedTab = MainTab.RECORDS },
                onOpenExercise = { subScreen = "exercise" },
                modifier = contentModifier,
            )
            MainTab.MISSIONS -> MissionScreen(
                modifier = contentModifier,
                // 유형별 라우팅(#93): 걷기→측정 화면, 운동→영상 화면(홈과 동일), 그 외→'준비 중'.
                //   기록 POST는 여기서 하지 않는다(#91 단일 지점).
                onMissionClick = { mission ->
                    when (missionDestination(mission.missionType)) {
                        MissionDestination.WALKING -> walkingMission = mission
                        // 홈의 '영상 따라 운동하기'와 같은 목적지 — 미션 탭만 '준비 중'으로 막던 문제 해소(#162).
                        MissionDestination.EXERCISE_VIDEOS -> subScreen = "exercise"
                        MissionDestination.COMING_SOON -> comingSoonMission = mission
                    }
                },
            )
            MainTab.RECORDS -> RecordScreen(
                modifier = contentModifier,
            )
            MainTab.SETTINGS -> SettingsScreen(
                onOpenSupport = { subScreen = "support" },
                onOpenProfile = { subScreen = "profile" },
                // 로그아웃(#154): 재로그인 후 설정 탭으로 튀지 않게 홈으로 되돌린 뒤,
                //   상위(MAIN 라우팅 스코프)에 위임한다. 실제 세션 정리·라우팅 재평가는 거기서 한다.
                onLogout = {
                    selectedTab = MainTab.HOME
                    onLogout()
                },
                modifier = contentModifier,
            )
        }
    }

    if (showExitConfirmation) {
        AigoDialog(
            title = "앱을 종료할까요?",
            message = "앱을 종료해도 다음에 다시 이용할 수 있어요.",
            confirmText = "종료",
            onConfirm = onExit,
            dismissText = "계속하기",
            onDismiss = { showExitConfirmation = false },
            onDismissRequest = { showExitConfirmation = false },
        )
    }
}
