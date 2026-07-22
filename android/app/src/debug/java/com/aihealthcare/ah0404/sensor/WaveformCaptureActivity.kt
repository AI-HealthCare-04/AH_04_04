package com.aihealthcare.ah0404.sensor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme

/**
 * '보행 직후 앉기' 분리(#131)를 위한 원시 3축 파형 수집 화면의 호스트 Activity. **디버그 소스셋 전용.**
 *
 * 설정 화면의 BuildConfig.DEBUG 게이트 버튼에서 명시 인텐트(문자열 ComponentName)로 실행된다.
 * 릴리스 APK 에는 이 Activity 자체가 포함되지 않으므로(debug 소스셋 + debug 매니페스트 등록),
 * 시니어 사용자 경로에는 절대 노출되지 않는다.
 */
class WaveformCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WaveformCaptureScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    )
                }
            }
        }
    }
}
