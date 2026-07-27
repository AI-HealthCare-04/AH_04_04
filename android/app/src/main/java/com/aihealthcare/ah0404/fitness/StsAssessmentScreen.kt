package com.aihealthcare.ah0404.fitness

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * 기초체력 평가 — 5회 의자 일어서기(5STS) 가이드 측정 화면.
 *
 * 흐름: 방법 안내 → 안전 확인 → 준비 → 측정(스톱워치 + 회당 버튼 + 반응형 TTS) → 결과.
 *  - 회당 [일어섰어요] 탭을 **본인이** 누른다(팀 결정). 5회째 탭에서 자동 종료(stsIsComplete).
 *  - TTS 는 사용자가 한 동작을 확인해주는 **반응형**(메트로놈 금지) — 로직은 [[StsAssessment]].
 *  - 결과는 소요 시간(초)만 돌려준다(onMeasured). 밴드(활동레벨) 산정은 서버가 한다(physical-assessment).
 *  - ⚠️ 비의료: 판정 표현 금지. 결과는 시간 + "전문가 상담 권유"까지만.
 */
@UnstableApi
@Composable
fun StsAssessmentScreen(
    onMeasured: (Double) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stage by remember { mutableStateOf(StsStage.GUIDE) }
    var reps by remember { mutableIntStateOf(0) }
    var startMs by remember { mutableStateOf(0L) }
    var measuringStarted by remember { mutableStateOf(false) }
    var countdownText by remember { mutableStateOf("") }
    var nowMs by remember { mutableStateOf(0L) }
    var resultSeconds by remember { mutableStateOf(0.0) }
    var ttsEnabled by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val tts = remember { StsAssessmentTts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    // 화면 이탈(백그라운드) 시 발화 중단 + 측정 취소.
    //   ⚠️ 측정/카운트다운 중 전화·홈 전환이 일어나면 elapsedRealtime 이 백그라운드 체류 시간까지 포함해
    //     측정값을 오염시킨다(리뷰 #221-3). 그 경우 측정을 버리고 준비 단계로 되돌린다(재시작 필요).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                tts.stop()
                if (stage == StsStage.MEASURING) {
                    measuringStarted = false
                    stage = StsStage.READY
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 시스템 뒤로가기는 이 화면 안에서 먼저 처리한다(리뷰 #221-3): 부모 OnboardingScreen 의 BackHandler 로
    //   전파되면 측정 도중에 온보딩 단계가 ASSESSMENT→PROFILE 로 넘어가 버린다.
    BackHandler {
        tts.stop()
        when (stage) {
            StsStage.MEASURING -> { measuringStarted = false; stage = StsStage.READY } // 측정 취소 → 준비
            StsStage.GUIDE -> onCancel() // 첫 단계에서 뒤로 = 측정 화면 종료(입력 화면 복귀)
            StsStage.SAFETY -> stage = StsStage.GUIDE
            StsStage.READY -> stage = StsStage.SAFETY
            StsStage.RESULT -> stage = StsStage.READY
        }
    }

    // 단계별 안내 발화 + 측정 시작 카운트다운.
    //   ⚠️ stage 에만 결합한다(ttsEnabled 제외) — 음성 토글이 측정 중 이 effect 를 재실행해
    //     카운트·타이머를 초기화하던 문제 방지(리뷰 #221-2). 토글은 발화 여부(if ttsEnabled)만 바꾼다.
    LaunchedEffect(stage) {
        when (stage) {
            StsStage.GUIDE -> if (ttsEnabled) {
                tts.speakGuide("양팔을 가슴에 X자로 안아주세요. 손으로 의자를 짚지 마세요.")
                tts.speakGuide("의자에서 완전히 일어섰다가 앉기를 다섯 번 반복합니다.")
            }
            StsStage.SAFETY -> if (ttsEnabled) tts.speakGuide("어지러우면 언제든지 멈추셔도 됩니다.")
            StsStage.READY -> if (ttsEnabled) tts.speakGuide("준비되면 시작 버튼을 눌러주세요.")
            StsStage.MEASURING -> {
                measuringStarted = false
                reps = 0
                STS_COUNTDOWN_WORDS.forEachIndexed { i, word ->
                    countdownText = word
                    if (ttsEnabled) tts.speakCount(word)
                    if (stsClockStartsAtCountdownIndex(i)) {
                        // ⚠️ "시작하세요!" 발화 시점에 스톱워치 start — 뒤에 delay 를 두면 첫 ~0.8초가
                        //   측정에서 빠져 12초 밴드까지 어긋난다(리뷰 #221-1). 마지막 단어엔 delay 없음.
                        startMs = SystemClock.elapsedRealtime()
                        measuringStarted = true
                    } else {
                        delay(800)
                    }
                }
                countdownText = ""
            }
            StsStage.RESULT -> if (ttsEnabled) tts.speakGuide(stsResultSpeech(resultSeconds))
        }
    }

    // 측정 중 스톱워치 갱신(0.1초 단위 표시). TTS 와 독립 — 발화 완료를 기다리지 않는다(측정값 오염 방지).
    LaunchedEffect(measuringStarted) {
        if (measuringStarted) {
            while (true) {
                nowMs = SystemClock.elapsedRealtime()
                delay(100)
            }
        }
    }
    val elapsedSec = if (measuringStarted) (nowMs - startMs).coerceAtLeast(0L) / 1000.0 else 0.0

    fun onStandUp() {
        if (!measuringStarted) return
        reps += 1
        if (ttsEnabled) tts.speakCount(stsCountWord(reps))
        if (stsIsComplete(reps)) {
            resultSeconds = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L) / 1000.0
            measuringStarted = false
            stage = StsStage.RESULT
        }
    }

    Column(
        // 스크롤 — 작은 화면(320×568dp)·큰 글꼴에서 하단 버튼이 화면 밖으로 밀리지 않게(리뷰 #221-4).
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 음성 안내 On/Off — 청력 저하·조용한 환경·보호자가 직접 셀 때.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("음성 안내", style = MaterialTheme.typography.titleMedium)
            Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it; if (!it) tts.stop() })
        }

        when (stage) {
            StsStage.GUIDE -> GuideStage(onNext = { stage = StsStage.SAFETY }, onCancel = onCancel)
            StsStage.SAFETY -> SafetyStage(onNext = { stage = StsStage.READY }, onBack = { stage = StsStage.GUIDE })
            StsStage.READY -> ReadyStage(onStart = { stage = StsStage.MEASURING }, onBack = { stage = StsStage.SAFETY })
            StsStage.MEASURING -> MeasuringStage(
                measuringStarted = measuringStarted,
                countdownText = countdownText,
                elapsedSec = elapsedSec,
                reps = reps,
                onStandUp = ::onStandUp,
                onStop = { tts.stop(); onCancel() },
            )
            StsStage.RESULT -> ResultStage(
                seconds = resultSeconds,
                onUse = { onMeasured(resultSeconds) },
                onRetry = { stage = StsStage.READY },
            )
        }
    }
}

@UnstableApi
@Composable
private fun GuideStage(onNext: () -> Unit, onCancel: () -> Unit) {
    val seated = rememberAssetImageBitmap("fitness/sts_seated.jpg")
    val standing = rememberAssetImageBitmap("fitness/sts_standing.jpg")
    AigoCard {
        Text("이렇게 해요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text("양팔을 가슴에 X자로 안고, 손으로 의자를 짚지 마세요.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Dimens.Space8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
            seated?.let { Image(it, "앉은 자세", Modifier.weight(1f).aspectRatio(0.75f)) }
            standing?.let { Image(it, "선 자세", Modifier.weight(1f).aspectRatio(0.75f)) }
        }
        Spacer(Modifier.height(Dimens.Space8))
        Text("이만큼 완전히 일어서야 1회예요. 다섯 번 반복합니다.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Dimens.Space8))
        StsLoopVideo(Modifier.fillMaxWidth().height(200.dp))
    }
    AigoPrimaryButton(text = "다음", onClick = onNext)
    AigoSecondaryButton(text = "그만두기", onClick = onCancel)
}

@Composable
private fun SafetyStage(onNext: () -> Unit, onBack: () -> Unit) {
    AigoCard {
        Text("잠깐, 안전 확인", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text("어지러우면 즉시 멈추세요.", style = MaterialTheme.typography.bodyLarge)
        Text("처음에는 보호자와 함께 해주세요.", style = MaterialTheme.typography.bodyLarge)
        Text("바퀴 없는 튼튼한 의자를 벽에 붙여주세요.", style = MaterialTheme.typography.bodyLarge)
    }
    AigoPrimaryButton(text = "확인했어요", onClick = onNext)
    AigoSecondaryButton(text = "이전", onClick = onBack)
}

@Composable
private fun ReadyStage(onStart: () -> Unit, onBack: () -> Unit) {
    AigoCard {
        Text("준비되면 시작을 눌러주세요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text("일어설 때마다 아래 큰 버튼을 눌러주세요.", style = MaterialTheme.typography.bodyLarge)
    }
    AigoPrimaryButton(text = "시작", onClick = onStart)
    AigoSecondaryButton(text = "이전", onClick = onBack)
}

@UnstableApi
@Composable
private fun MeasuringStage(
    measuringStarted: Boolean,
    countdownText: String,
    elapsedSec: Double,
    reps: Int,
    onStandUp: () -> Unit,
    onStop: () -> Unit,
) {
    if (!measuringStarted) {
        Text(
            countdownText.ifBlank { "시작!" },
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        return
    }
    Text(
        "${formatStsSeconds(elapsedSec)}초",
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
    )
    Text("$reps / $STS_TARGET_REPS", style = MaterialTheme.typography.headlineMedium)
    StsLoopVideo(Modifier.fillMaxWidth().height(160.dp))
    Spacer(Modifier.height(Dimens.Space8))
    AigoPrimaryButton(text = "일어섰어요", onClick = onStandUp)
    AigoSecondaryButton(text = "중단", onClick = onStop)
}

@Composable
private fun ResultStage(seconds: Double, onUse: () -> Unit, onRetry: () -> Unit) {
    AigoCard {
        Text("수고하셨어요!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text("${formatStsSeconds(seconds)}초 걸리셨어요.", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Dimens.Space8))
        // 비의료 가드레일 — 판정 표현 없이 안내만.
        Text("결과가 궁금하시면 전문가와 상담해보시는 걸 권해드려요.", style = MaterialTheme.typography.bodyLarge)
    }
    AigoPrimaryButton(text = "이 기록 사용하기", onClick = onUse)
    AigoSecondaryButton(text = "다시 측정", onClick = onRetry)
}
