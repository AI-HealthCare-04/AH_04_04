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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aihealthcare.ah0404.mission.MissionScreen
import com.aihealthcare.ah0404.onboarding.OnboardingScreen
import com.aihealthcare.ah0404.sensor.SensorScreen
import com.aihealthcare.ah0404.voice.VoiceProbeScreen
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // 온보딩 완료 전까지 온보딩 흐름을 먼저 보여준다(역할분담 §4-②).
                var onboarded by remember { mutableStateOf(false) }
                if (!onboarded) {
                    OnboardingScreen(onComplete = { onboarded = true })
                    return@MyApplicationTheme
                }

                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                label = { Text("미션") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) },
                                label = { Text("센서") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                label = { Text("음성") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        0 -> MissionScreen(modifier = Modifier.padding(innerPadding))
                        1 -> SensorScreen(modifier = Modifier.padding(innerPadding))
                        2 -> VoiceProbeScreen(modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
