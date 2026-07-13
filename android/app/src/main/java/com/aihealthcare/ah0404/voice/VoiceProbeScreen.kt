package com.aihealthcare.ah0404.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aihealthcare.ah0404.network.MissionApi
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.network.VoiceApi
import com.aihealthcare.ah0404.network.VoiceField
import com.aihealthcare.ah0404.network.VoiceParseRequest
import com.aihealthcare.ah0404.network.VoiceParseResponse
import com.aihealthcare.ah0404.network.retrofit

/**
 * STT 실기기 인식률 프로브 화면.
 *
 *  사용법: 필드 선택 → 🎤 버튼 → ko-KR 로 발화 → 인식 원문이 뜨면 자동으로
 *  /voice/parse 를 호출해 value / needs_confirmation 을 보여준다.
 *  (백엔드가 172.30.1.56:8001 로 떠 있어야 하며 폰과 같은 Wi-Fi 여야 함)
 */
@Composable
fun VoiceProbeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val api = remember { retrofit.create(VoiceApi::class.java) }
    val missionApi = remember { retrofit.create(MissionApi::class.java) }
    val stt = remember { SpeechToTextController(context) }

    DisposableEffect(Unit) {
        onDispose { stt.destroy() }
    }

    var selectedField by remember { mutableStateOf(VoiceField.HEIGHT_CM) }
    var fieldMenuOpen by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var result by remember { mutableStateOf<VoiceParseResponse?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) stt.start()
    }

    // 최종 인식 원문이 확정되면 자동으로 서버 파서를 호출한다.
    LaunchedEffect(stt.finalText, selectedField) {
        val transcript = stt.finalText
        if (transcript.isBlank()) return@LaunchedEffect
        parsing = true
        parseError = null
        result = null
        runCatching {
            // /voice/parse 는 인증이 필요하다(get_request_user). 토큰이 없으면 게스트 로그인부터.
            if (TokenHolder.token.isBlank()) {
                TokenHolder.token = missionApi.guestLogin().accessToken
            }
            api.parseVoice(
                VoiceParseRequest(field = selectedField.serverKey, rawTranscript = transcript)
            )
        }.onSuccess { result = it }
            .onFailure { parseError = it.message ?: it.toString() }
        parsing = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("음성 입력 테스트", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        // ── 필드 선택 ──────────────────────────────────────────────
        Text("무엇을 말할까요?", fontSize = 18.sp)
        OutlinedButton(
            onClick = { fieldMenuOpen = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(selectedField.label, fontSize = 20.sp)
        }
        DropdownMenu(expanded = fieldMenuOpen, onDismissRequest = { fieldMenuOpen = false }) {
            VoiceField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.label, fontSize = 18.sp) },
                    onClick = {
                        selectedField = field
                        fieldMenuOpen = false
                    },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── 마이크 버튼 ────────────────────────────────────────────
        Button(
            onClick = {
                if (stt.isListening) {
                    stt.stop()
                } else if (micGranted) {
                    stt.start()
                } else {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = stt.isAvailable,
            modifier = Modifier.fillMaxWidth().height(88.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (stt.isListening) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            ),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text(
                text = if (stt.isListening) "  듣는 중… (탭하면 멈춤)" else "  눌러서 말하기",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!stt.isAvailable) {
            Text(
                "이 기기에서 음성 인식을 사용할 수 없습니다.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 16.sp,
            )
        }

        // ── 인식 원문 ──────────────────────────────────────────────
        InfoCard(title = "인식된 말") {
            val shown = stt.partialText.ifBlank { stt.finalText }
            Text(
                text = shown.ifBlank { "아직 없음 — 마이크를 눌러 말해 보세요." },
                fontSize = 22.sp,
                fontWeight = if (shown.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
            )
            stt.error?.let {
                Spacer(Modifier.height(8.dp))
                Text("⚠️ $it", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
            }
        }

        // ── 서버 파싱 결과 ─────────────────────────────────────────
        InfoCard(title = "서버 파싱 결과 (/voice/parse)") {
            when {
                parsing -> Text("파싱 중…", fontSize = 18.sp)
                parseError != null -> Text(
                    "요청 실패: $parseError\n(백엔드 실행/네트워크 확인)",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                )
                result != null -> {
                    val r = result!!
                    val valueText = r.value?.toString() ?: "null (수동입력 폼으로 폴백)"
                    Text("value = $valueText", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("needs_confirmation = ${r.needsConfirmation}", fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("field = ${r.field}", fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
                }
                else -> Text("아직 없음", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
