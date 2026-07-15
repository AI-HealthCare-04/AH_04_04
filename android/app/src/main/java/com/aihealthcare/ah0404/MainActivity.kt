package com.aihealthcare.ah0404

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aihealthcare.ah0404.home.HomeScreen
import com.aihealthcare.ah0404.mission.MissionScreen
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.onboarding.OnboardingScreen
import com.aihealthcare.ah0404.profile.ProfileScreen
import com.aihealthcare.ah0404.record.RecordScreen
import com.aihealthcare.ah0404.sensor.SensorScreen
import com.aihealthcare.ah0404.settings.SettingsScreen
import com.aihealthcare.ah0404.settings.SupportScreen
import com.aihealthcare.ah0404.voice.VoiceProbeScreen
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 저장된 토큰 복원(리뷰 #63 P1-2). 완료 사용자는 온보딩을 건너뛴다.
        SessionStore.restore(this)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                // 완료 상태는 로컬에 영속화된 값으로 초기화 → 재시작 후에도 온보딩 반복 안 함.
                var onboarded by remember { mutableStateOf(SessionStore.isOnboarded(context)) }
                if (!onboarded) {
                    OnboardingScreen(onComplete = {
                        SessionStore.markOnboarded(context)
                        onboarded = true
                    })
                    return@MyApplicationTheme
                }

                // 탭 위에 얹히는 서브 화면(설정/고객센터). null = 탭 화면.
                var subScreen by remember { mutableStateOf<String?>(null) }
                when (subScreen) {
                    "settings" -> {
                        SettingsScreen(
                            onBack = { subScreen = null },
                            onOpenSupport = { subScreen = "support" },
                            onOpenProfile = { subScreen = "profile" },
                        )
                        return@MyApplicationTheme
                    }
                    "profile" -> {
                        ProfileScreen(onBack = { subScreen = "settings" })
                        return@MyApplicationTheme
                    }
                    "support" -> {
                        SupportScreen(onBack = { subScreen = "settings" })
                        return@MyApplicationTheme
                    }
                    "records" -> {
                        RecordScreen(onBack = { subScreen = null })
                        return@MyApplicationTheme
                    }
                }

                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("홈") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                label = { Text("미션") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) },
                                label = { Text("센서") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                label = { Text("음성") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        0 -> HomeScreen(
                            onGoMissions = { selectedTab = 1 },
                            onOpenSettings = { subScreen = "settings" },
                            onOpenRecords = { subScreen = "records" },
                            modifier = Modifier.padding(innerPadding),
                        )
                        1 -> MissionScreen(modifier = Modifier.padding(innerPadding))
                        2 -> SensorScreen(modifier = Modifier.padding(innerPadding))
                        3 -> VoiceProbeScreen(modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
