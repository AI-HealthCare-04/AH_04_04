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
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme

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
                        onboardingCompleted = SessionStore.isOnboarded(context),
                        tokenStatus = tokenStatus,
                        networkAvailable = networkAvailable,
                        failure = authFailure,
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
                        onComplete = {
                            SessionStore.markOnboarded(context)
                            sessionRevision++
                        },
                        onBrowseDemo = {
                            demoMode = true
                        },
                    )
                    AppRoute.LOGIN_REQUIRED -> LoginRequiredScreen(
                        onGoogleLogin = {
                            authLoginViewModel.signIn(SocialProvider.GOOGLE, activity) { sessionRevision++ }
                        },
                        onKakaoLogin = {
                            authLoginViewModel.signIn(SocialProvider.KAKAO, activity) { sessionRevision++ }
                        },
                        onRetry = {
                            AuthFailureCoordinator.retryTransientFailure()
                            sessionRevision++
                        },
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
                    )
                    AppRoute.MAIN -> MainContent()
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

// 걷기 오버레이 진입 상태(어느 미션인지)를 구성 변경(회전 등)에 보존하기 위한 Saver.
// Mission 은 @Serializable(kotlinx) 이라 JSON 문자열로 저장/복원한다(Parcelable 불필요).
private val MissionJson = Json { ignoreUnknownKeys = true }
private val MissionStateSaver: Saver<Mission?, String> = Saver(
    save = { mission -> mission?.let { MissionJson.encodeToString(it) } },
    restore = { MissionJson.decodeFromString<Mission>(it) },
)

@androidx.media3.common.util.UnstableApi
@Composable
private fun MainContent() {
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }

    // 걷기 측정 화면(전체 화면 오버레이). 미션 목록에서 걷기 미션을 고르면 진입. null = 목록.
    // rememberSaveable: 구성 변경(회전·폰트 크기 변경) 후에도 측정 화면을 유지한다(세션 값은 VM 이 보존).
    var walkingMission by rememberSaveable(stateSaver = MissionStateSaver) {
        mutableStateOf<Mission?>(null)
    }
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

    BackHandler(enabled = selectedTab != MainTab.HOME) {
        selectedTab = MainTab.HOME
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
                // 유형별 라우팅(#93): 걷기→측정 화면, 그 외→'준비 중'. 기록 POST는 여기서 하지 않는다(#91 단일 지점).
                onMissionClick = { mission ->
                    when (missionDestination(mission.missionType)) {
                        MissionDestination.WALKING -> walkingMission = mission
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
                modifier = contentModifier,
            )
        }
    }
}
