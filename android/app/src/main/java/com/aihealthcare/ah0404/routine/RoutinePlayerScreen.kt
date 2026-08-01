package com.aihealthcare.ah0404.routine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aihealthcare.ah0404.settings.AppSettings
import com.aihealthcare.ah0404.ui.theme.HdBg
import com.aihealthcare.ah0404.ui.theme.HdCardBorder
import com.aihealthcare.ah0404.ui.theme.HdCardFill
import com.aihealthcare.ah0404.ui.theme.HdGreen
import com.aihealthcare.ah0404.ui.theme.HdGreenDark
import com.aihealthcare.ah0404.ui.theme.HdGreenTint
import com.aihealthcare.ah0404.ui.theme.HdInk
import com.aihealthcare.ah0404.ui.theme.HdMuted
import com.aihealthcare.ah0404.ui.theme.HdUnselBorder
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

private val SafetyColor = Color(0xFFC62828)

/** 스트레칭 상세 흐름의 단계(시안 §3). 안전고지 팝업 → 시작 → 진행/자세전환 → 완료. */
private enum class RoutinePhase { SAFETY, START, RUNNING, DONE }

/**
 * 몸풀기·마무리 스트레칭 상세 플레이어 — 화면 1개 + JSON 루틴으로 data-driven 재생.
 *
 *  ★ 디자인 고도화(시안 확정)로 흐름이 4단계가 됐다:
 *    ① 안전 고지 **팝업**(시작 화면 위에 dim 처리로 뜬다 — 별도 페이지 아님)
 *    ② **시작 화면**(총 N동작 · 약 N분 / 시작하기 · 나가기)
 *    ③ **진행 화면**(시간형=원형 초 카운트 / 반복형=현재·목표 횟수, 자세 전환은 전용 화면)
 *    ④ **완료 화면**(확인 버튼 1개)
 *
 *  - BGM/클립을 별도 ExoPlayer로 분리(단계 전환 시 음악 리셋 방지).
 *  - 에셋(res/raw 영상·assets/exercise 이미지)이 없으면 텍스트+타이머로 진행(그레이스풀).
 *  - "운동하기"의 몸풀기·마무리 탭에서 진입(ExerciseVideosScreen). 스트리밍(#72)과 별개인 번들 루틴.
 *
 * @param onComplete 진행한 '분'을 **저장만** 한다(화면은 닫지 않음). 완료 화면·중도 이탈 양쪽에서 호출된다.
 * @param onExit 화면을 닫는다. 저장과 분리돼 있어 완료 화면을 띄운 뒤에도 기록이 먼저 안전하게 전송된다.
 * @param needsSafetyConfirm true 면 시작 화면 위에 안전 고지 팝업을 먼저 띄운다(#234 게이트를 이 화면이 수행).
 * @param onSafetyConfirmed 사용자가 실제로 안전 고지를 확인했을 때 1회 호출(완료 전송의 safety_notice_confirmed 근거).
 */
@Composable
fun RoutinePlayerScreen(
    routineFile: String = "warmup_common.json",
    onExit: () -> Unit = {},
    onComplete: (durationMin: Float) -> Unit = {},
    needsSafetyConfirm: Boolean = false,
    onSafetyConfirmed: () -> Unit = {},
) {
    val context = LocalContext.current
    val routine = remember { RoutineLoader.load(context, routineFile) }

    // 동작 '종류' 수(#335 기준: 좌우 변형을 한 종류로 묶음). 시안의 "총 9동작"이 이 값이다.
    val motionGroups = remember(routine) { exerciseGroups(routine.steps) }
    val motionTotal = motionGroups.size

    // BGM: 루틴 전체(251초) 동안 끊김 없이 1회 재생. 251초 전용 트랙이라 루프하지 않는다.
    //   배경음악 끄기(music_enabled=false)면 미디어 준비·재생·오디오포커스 요청을 '아예 하지 않는다'(리뷰 #87):
    //   무음 재생조차 안 해 디코딩 자원을 안 쓰고, 다른 앱(음악/라디오)의 오디오 포커스를 뺏거나 덕킹하지 않는다.
    //   (설정은 루틴 진입 시점 값으로 고정 — 재생 중엔 설정 화면에 못 가므로 1회 읽기로 충분)
    val musicOn = AppSettings.musicEnabled
    val bgmSoundScale = AppSettings.soundScale
    val bgmPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            if (musicOn) {
                rawUri(context, routine.bgm)?.let { uri ->
                    setMediaItem(MediaItem.fromUri(uri))
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = 0.4f * bgmSoundScale // 기준 0.4 × 설정 소리 크기(#86-2)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        /* handleAudioFocus = */ true, // 전화 오면 자동 일시정지 (켜기일 때만 요청)
                    )
                    setPlaybackSpeed(AppSettings.playbackSpeed) // 영상·타이머와 동일 배속(3중 동기, 스펙 §4-4)
                    prepare()
                }
            }
        }
    }

    // 동작 클립: 단계마다 교체. 1~3.3초 루프를 sec 동안 반복(REPEAT_MODE_ALL). 오디오 이중 방어(volume 0).
    val clipPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            setPlaybackSpeed(AppSettings.playbackSpeed) // 전역 재생 속도(톱니로 조절, 기본 1.0)
        }
    }

    var phase by remember { mutableStateOf(if (needsSafetyConfirm) RoutinePhase.SAFETY else RoutinePhase.START) }
    // 시작 화면이 인트로 안내를 대신하므로 INTRO 단계는 건너뛴다(누르자마자 10초 대기 화면이 뜨지 않게).
    //   '이전'의 하한도 이 값이다 — 건너뛴 인트로로 되돌아가면 타이머만 도는 빈 화면이 된다.
    val firstStepIndex = remember(routine) {
        routine.steps.indexOfFirst { it.type != StepType.INTRO }.coerceAtLeast(0)
    }
    var stepIndex by remember { mutableIntStateOf(firstStepIndex) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var showExit by remember { mutableStateOf(false) }
    // 실제 진행 시간(#234 P1-B): 단계별 elapsedMs 와 달리 루틴 전체에서 '실제로 흐른' 시간만 누적한다(일시정지
    //   제외, 각 단계 리셋 안 됨). '다음'으로 스킵하면 그 단계는 스킵 시점까지만 반영 → 완료 전송이 실제 수행 분만 싣는다.
    var playedMs by remember { mutableLongStateOf(0L) }
    // 재생 속도(전역, 톱니로 조절). 영상·타이머·BGM 3곳에 동일 적용해 항상 동기(스펙 §4-4). 기본 1.0.
    var speed by remember { mutableFloatStateOf(AppSettings.playbackSpeed) }
    var showSpeed by remember { mutableStateOf(false) }

    val step = routine.steps.getOrNull(stepIndex)
    val running = phase == RoutinePhase.RUNNING

    // 속도 변경을 영상(클립)·BGM 에 즉시 반영. 타이머는 아래 루프가 speed 를 실시간으로 읽어 반영한다.
    //   (재생 파라미터는 media item 과 무관한 player 속성이라 단계가 바뀌어도 유지된다.)
    LaunchedEffect(speed) {
        clipPlayer.setPlaybackSpeed(speed)
        bgmPlayer.setPlaybackSpeed(speed)
    }

    // BGM은 '시작하기'로 실제 진행이 시작될 때 1회 play. (끄기면 준비 자체를 안 했으므로 재생도 안 한다)
    LaunchedEffect(running) { if (running && musicOn) bgmPlayer.playWhenReady = true }

    // 일시정지/재개 — 타이머·클립·BGM 동시 제어(동기 유지).
    LaunchedEffect(paused, running) {
        if (paused || !running) {
            bgmPlayer.pause(); clipPlayer.pause()
        } else {
            if (musicOn) bgmPlayer.play()
            if (routine.steps.getOrNull(stepIndex)?.type == StepType.VIDEO) clipPlayer.play()
        }
    }

    // 단계 진입 시 클립 세팅. 자원 유무·단계 종류와 무관하게 '먼저' 이전 클립을 정지·비운다 →
    //   직전 VIDEO 단계의 영상이 이미지/텍스트 단계나 영상 누락 단계에 남아 재생되는 것을 차단(지영 리뷰 #82).
    //   (로더가 영상 누락을 로드 단계에서 이미 막지만, 방어적으로 여기서도 항상 초기화한다.)
    LaunchedEffect(stepIndex, running) {
        clipPlayer.stop()
        clipPlayer.clearMediaItems()
        if (!running) return@LaunchedEffect
        val st = routine.steps.getOrNull(stepIndex)
        if (st?.type == StepType.VIDEO && st.asset != null) {
            rawUri(context, st.asset)?.let { uri ->
                clipPlayer.setMediaItem(MediaItem.fromUri(uri))
                clipPlayer.prepare()
                clipPlayer.playWhenReady = !paused
            }
        }
    }

    // 단계 타이머: sec 동안 진행 후 자동으로 다음 step. 일시정지 중엔 시간 안 흐름.
    LaunchedEffect(stepIndex, running) {
        if (!running) return@LaunchedEffect
        elapsedMs = 0L
        val st = routine.steps.getOrNull(stepIndex) ?: run { phase = RoutinePhase.DONE; return@LaunchedEffect }
        // 마무리 안내(OUTRO)는 완료 화면이 대신하므로 여기까지 오면 끝난 것으로 본다.
        if (st.type == StepType.OUTRO) { phase = RoutinePhase.DONE; return@LaunchedEffect }
        // elapsedMs 는 '동작 기준(콘텐츠) 시간'이라 sec*1000 과 직접 비교한다. 배속이면 한 틱(50ms 실시간)에
        //   50*speed 만큼 콘텐츠 시간이 흘러 단계가 speed 배 빨리/느리게 끝난다(영상·타이머 동기). speed 를 루프에서
        //   실시간으로 읽으므로 재생 중 속도 변경 시 남은 시간이 즉시 재계산된다(스펙 §4-4 주의). playedMs 는 실제
        //   흐른 벽시계 시간(분 적립용)이라 배속과 무관하게 50ms 씩 누적한다.
        val totalMs = st.sec * 1000L
        while (elapsedMs < totalMs) {
            delay(50)
            if (!paused) { elapsedMs += scaledContentTickMs(50, speed); playedMs += 50 }
        }
        if (stepIndex + 1 < routine.steps.size) stepIndex++ else phase = RoutinePhase.DONE
    }

    // 생명주기: onPause 시 일시정지. onDispose 시 두 플레이어 release(누수 방지).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bgmPlayer.release()
            clipPlayer.release()
        }
    }

    // 완료 즉시 '실제로 진행한 분'(playedMs)을 저장한다 — 전체 step 합이 아니라 실제 흐른 시간이라 '다음' 스킵·
    //   일시정지가 반영된다(리뷰 P1-B). #234 운동 완료 배선이 이 분을 서버 당일 10분 누적에 합산.
    //   ★완료 화면을 띄우기 '전'에 보내므로, 사용자가 확인을 누르지 않고 앱을 떠나도 기록이 남는다.
    LaunchedEffect(phase) {
        if (phase == RoutinePhase.DONE) {
            bgmPlayer.pause(); clipPlayer.stop()
            onComplete(playedMs / 60_000f)
        }
    }

    // 뒤로가기: 진행 중이면 이탈 확인, 그 외(시작·완료·안전고지)에는 바로 닫는다.
    BackHandler {
        when (phase) {
            RoutinePhase.RUNNING -> showExit = true
            RoutinePhase.DONE -> onExit()
            else -> onExit()
        }
    }

    // ---------------- UI ----------------
    when (phase) {
        // 안전 고지 팝업은 '시작 화면 위에' 뜬다(시안 4-1) — 뒤에 시작 화면이 dim 처리로 비쳐야 하므로
        //   START 와 같은 화면을 그리고 그 위에 Dialog 를 얹는다.
        RoutinePhase.SAFETY, RoutinePhase.START -> {
            StretchStartScreen(
                routine = routine,
                motionTotal = motionTotal,
                speed = speed,
                onSpeedClick = { showSpeed = true },
                onStart = { phase = RoutinePhase.RUNNING },
                onExit = onExit,
            )
            if (phase == RoutinePhase.SAFETY) {
                StretchSafetyDialog(
                    onConfirm = { onSafetyConfirmed(); phase = RoutinePhase.START },
                    // 확인 없이 이탈 = 운동을 하지 않은 것 — 팝업만 닫지 않고 화면을 나간다(시안 4-1).
                    onDismiss = onExit,
                )
            }
        }

        RoutinePhase.RUNNING -> {
            if (step == null) {
                phase = RoutinePhase.DONE
            } else {
                val progress = (elapsedMs / (step.sec * 1000f)).coerceIn(0f, 1f)
                val remainSec = ceil((step.sec * 1000L - elapsedMs) / 1000.0).toInt().coerceAtLeast(0)
                val goNext: () -> Unit = {
                    if (stepIndex + 1 < routine.steps.size) stepIndex++ else phase = RoutinePhase.DONE
                }

                if (step.type == StepType.NOTICE) {
                    // 자세 전환(시안 4-4): 남은 시간 안내 + 바로 넘길 수 있는 CTA. 자동 진행은 타이머가 계속 돈다.
                    //   전환 단계 자체는 자산이 없으므로 **다음 동작의 대표 이미지**를 미리 보여준다 — 곧 할 자세를
                    //   그림으로 먼저 보여주는 게 목적이고, 없을 때만 자리표시자로 떨어진다.
                    val nextAsset = routine.steps.drop(stepIndex + 1)
                        .firstOrNull { it.type == StepType.IMAGE && it.asset != null }?.asset
                    StretchTransitionScreen(
                        step = step,
                        remainSec = remainSec,
                        previewAsset = nextAsset,
                        onReady = goNext,
                    )
                } else {
                    StretchProgressScreen(
                        step = step,
                        stepIndex = stepIndex,
                        steps = routine.steps,
                        motionTotal = motionTotal,
                        clipPlayer = clipPlayer,
                        elapsedMs = elapsedMs,
                        progress = progress,
                        remainSec = remainSec,
                        paused = paused,
                        speed = speed,
                        onSpeedClick = { showSpeed = true },
                        onTogglePause = { paused = !paused },
                        canGoPrev = stepIndex > firstStepIndex,
                        onPrev = { if (stepIndex > firstStepIndex) stepIndex-- },
                        onNext = goNext,
                        onExit = { showExit = true },
                    )
                }
            }
        }

        RoutinePhase.DONE -> StretchCompletionScreen(
            routine = routine,
            motionTotal = motionTotal,
            playedMinutes = playedMs / 60_000f,
            onConfirm = onExit,
        )
    }

    // 재생 속도 선택 — 0.75/1.0/1.25/1.5. 고르면 영상·타이머·BGM 에 동일 적용되고 전역 저장(다음 영상에도 이어짐).
    if (showSpeed) {
        SpeedPickerDialog(
            current = speed,
            onSelect = { picked ->
                speed = picked
                AppSettings.setPlaybackSpeed(context, picked)
                showSpeed = false
            },
            onDismiss = { showSpeed = false },
        )
    }

    // 나가기 — 실수 이탈 방지 확인 다이얼로그
    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    // 중도 종료도 '지금까지 실제로 진행한 분'(playedMs)을 저장한다(리뷰 #234-3: 스트리밍과 동일 기준 —
                    //   스트리밍은 닫아도 실재생분을 보내는데 루틴만 완주해야 인정하면 5분 하다 나갈 때 다 사라짐).
                    //   단 [MIN_ROUTINE_PLAYED_MS] 미만은 실수 진입으로 보고 버린다(0분 등은 VM 이 한 번 더 거른다).
                    if (playedMs >= MIN_ROUTINE_PLAYED_MS) onComplete(playedMs / 60_000f)
                    onExit()
                }) { Text("나가기", fontSize = 22.sp) }
            },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("계속하기", fontSize = 22.sp) } },
            title = { Text("운동을 그만할까요?", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
            text = { Text("지금까지 진행한 시간은 저장돼요.", fontSize = 22.sp) },
        )
    }
}

/** 루틴 중도 종료 시 이보다 짧으면(실수 진입 등) 적립하지 않는다(리뷰 #234-3: 5~10초 미만 무시). */
private const val MIN_ROUTINE_PLAYED_MS = 5_000L

// =====================================================================================
//  화면들 (시안 §4)
// =====================================================================================

/**
 * 안전 고지 팝업(시안 4-1). 독립 화면이 아니라 시작 화면 위에 뜨는 Dialog 이며, 체크 문구 3개 + CTA 1개로
 * 짧게 유지한다. 바깥을 눌러 닫는 대신 [onDismiss](=화면 나가기)로만 이탈한다 — 확인 없이 운동이 시작되면
 * 서버가 완료를 400 으로 막는다(#234, safety_notice_confirmed).
 */
@Composable
private fun StretchSafetyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HdBg,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Box(Modifier.size(52.dp).background(HdGreen, CircleShape), contentAlignment = Alignment.Center) {
                Text("🛡", fontSize = 24.sp)
            }
        },
        title = {
            Text(
                "운동 전 확인해요",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = HdInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SafetyCheckRow("❤️", "무리하지 말고\n천천히 해요")
                SafetyCheckRow("❗", "어지럽거나 아프면\n바로 멈춰요")
                SafetyCheckRow("🪑", "의자나 벽을 가까이 두고\n안전하게 해요")
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HdGreenDark),
            ) { Text("네, 시작할게요", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        },
    )
}

/** 안전 고지 체크 항목 한 줄 — 아이콘 + 1~2줄 짧은 문장(장문 설명 금지, 시안 4-1). */
@Composable
private fun SafetyCheckRow(emoji: String, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = HdCardFill,
        border = BorderStroke(1.dp, HdCardBorder),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = HdInk)
        }
    }
}

/**
 * 시작 화면(시안 4-2) — 제목 · 보조 문구 · 큰 프리뷰 · 정보 칩 2개 · 시작하기/나가기 · 우상단 속도 칩.
 * 복잡한 설명은 넣지 않는다. 프리뷰는 첫 운동 단계의 대표 이미지를 그대로 쓴다(자산 재제작 없음).
 */
@Composable
private fun StretchStartScreen(
    routine: Routine,
    motionTotal: Int,
    speed: Float,
    onSpeedClick: () -> Unit,
    onStart: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // 인트로 단계의 안내 문구가 곧 시안의 보조 문구("편안하게 시작해볼까요")다. 없으면 루틴 subtitle.
    val subtitle = routine.steps.firstOrNull { it.type == StepType.INTRO }?.guide?.takeIf { it.isNotBlank() }
        ?: routine.subtitle
    val previewAsset = routine.steps.firstOrNull { it.type == StepType.IMAGE && it.asset != null }?.asset

    Column(
        modifier = Modifier.fillMaxSize().background(HdBg).systemBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            SpeedGearButton(speed = speed, onClick = onSpeedClick)
        }
        Spacer(Modifier.height(4.dp))
        Text(routine.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = HdInk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 17.sp, color = HdMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))

        MediaFrame(Modifier.fillMaxWidth().weight(1f)) {
            val bmp = rememberAssetImage(context, previewAsset)
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text("🤸", fontSize = 56.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoutineChip("👍", "총 ${motionTotal}동작", Modifier.weight(1f))
            RoutineChip("🕐", "약 ${(routine.totalSec / 60f).roundToInt().coerceAtLeast(1)}분", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HdGreenDark),
        ) { Text("시작하기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, HdUnselBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HdInk),
        ) { Text("나가기", fontSize = 18.sp) }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 진행 화면(시안 4-3 시간형 / 4-5 반복형) — 동작명 · 짧은 설명 · 큰 동작 영역 · 진행 표시 · 조작 버튼.
 *  진행은 분수(3/9)가 아니라 **"N번째 동작 / 총 N동작" + 점형 인디케이터**로 보여준다(시안 확정).
 */
@Composable
private fun StretchProgressScreen(
    step: Step,
    stepIndex: Int,
    steps: List<Step>,
    motionTotal: Int,
    clipPlayer: ExoPlayer,
    elapsedMs: Long,
    progress: Float,
    remainSec: Int,
    paused: Boolean,
    speed: Float,
    onSpeedClick: () -> Unit,
    onTogglePause: () -> Unit,
    canGoPrev: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val label = remember(stepIndex) { routineProgressLabel(steps, stepIndex) }

    // image_toggle: 자세 이미지(toggleFrame)를 카운트와 '같은 시계'인 elapsedMs에서 파생한다(지영 리뷰 #250).
    //   별도 delay 타이머로 두면 정지/재개 때 카운트(elapsedMs 기준)와 자세가 서로 어긋나(재개 직후 카운트만
    //   먼저 오르고 자세는 늦게 바뀜) 세트가 어긋나 보이고, 끝에서 한 컷 더 돌아 첫 이미지로 깜빡였다.
    //   elapsedMs 파생이면 정지 시 elapsedMs가 멈추므로 자세도 함께 멈추고, 카운트 증가 시점(낙타→고양이)과
    //   항상 일치한다(interval=3.5s·count=3이면 2컷=1카운트로 딱 맞물림). maxFrame 상한으로 마지막 컷(낙타)에서
    //   멈춰 끝 깜빡임을 없앤다.
    val toggleFrame = if (step.type == StepType.IMAGE_TOGGLE) {
        val interval = step.interval ?: 3.5
        val maxFrame = ((step.sec / interval).toInt() - 1).coerceAtLeast(0)
        (elapsedMs / (interval * 1000).toLong()).toInt().coerceIn(0, maxFrame)
    } else 0
    val displayGuide = step.guideByFrame
        ?.takeIf { step.type == StepType.IMAGE_TOGGLE && it.isNotEmpty() }
        ?.let { it[toggleFrame % it.size] }
        ?: step.guide

    Column(
        modifier = Modifier.fillMaxSize().background(HdBg).systemBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 속도 칩은 항상 우상단 고정(시안 §5-A).
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            SpeedGearButton(speed = speed, onClick = onSpeedClick)
        }
        Spacer(Modifier.height(2.dp))
        Text(step.name, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = HdInk, textAlign = TextAlign.Center)
        if (displayGuide.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(displayGuide, fontSize = 17.sp, lineHeight = 24.sp, color = HdMuted, textAlign = TextAlign.Center, maxLines = 2)
        }
        step.safety?.let {
            Spacer(Modifier.height(4.dp))
            Text("⚠ $it", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SafetyColor, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))

        // 동작 영역 — 시니어 사용성 때문에 남는 세로를 전부 준다(시안 §5-C).
        MediaFrame(Modifier.fillMaxWidth().weight(1f)) {
            StepMedia(step = step, context = context, clipPlayer = clipPlayer, toggleFrame = toggleFrame)
        }
        Spacer(Modifier.height(10.dp))

        when (step.mode) {
            // 시간형: 좌측 큰 원형 타이머 + 우측 동작 진행 정보(시안 4-3).
            StepMode.TIMER -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularTimer(progress = progress, seconds = remainSec)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    MotionCountRow(label.groupIndex, motionTotal)
                    Spacer(Modifier.height(8.dp))
                    MotionProgressDots(total = motionTotal, current = label.groupIndex)
                }
            }
            // 반복형: 횟수 카운트가 핵심 정보 — 숫자를 크게(시안 4-5).
            StepMode.COUNT -> {
                val target = step.count ?: 0
                val current = if (target > 0) min(target, (progress * target).toInt() + 1) else 0
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("$current", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = HdGreen)
                    Text(" / ${target}회", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HdInk, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp), color = HdGreenTint) {
                        Text("반복", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HdGreen, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("천천히 ${target}번 반복해요", fontSize = 14.sp, color = HdMuted)
                }
                Spacer(Modifier.height(10.dp))
                MotionCountRow(label.groupIndex, motionTotal)
                Spacer(Modifier.height(8.dp))
                MotionProgressDots(total = motionTotal, current = label.groupIndex)
            }
            StepMode.NONE -> Unit
        }

        Spacer(Modifier.height(12.dp))
        // 버튼 위계(시안 §5-B): 가장 중요한 '다음'만 짙은 녹색 채움, '이전·정지'는 연한 톤, '나가기'는 가장 약한 외곽선.
        //   시안은 한 줄(정지·다음·나가기)이지만 '이전'(#335)을 유지해야 해서 2행으로 둔다 — 320dp·큰 글꼴에서
        //   4개는 한 줄에 들어가지 않는다(#335 에서 확인된 제약).
        val ctrlPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftControlButton("이전", enabled = canGoPrev, modifier = Modifier.weight(1f), onClick = onPrev, contentPadding = ctrlPadding)
            SoftControlButton(if (paused) "재개" else "정지", modifier = Modifier.weight(1f), onClick = onTogglePause, contentPadding = ctrlPadding)
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HdGreenDark),
                contentPadding = ctrlPadding,
            ) { Text("다음", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, HdGreen.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HdGreen),
            contentPadding = ctrlPadding,
        ) { Text("나가기", fontSize = 18.sp, maxLines = 1) }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 자세 전환 화면(시안 4-4) — 서서 하던 흐름에서 앉아서 하는 흐름으로 넘어갈 준비 시간을 준다.
 * 자동 진행이 돌고 있어도 `준비됐어요`로 바로 넘길 수 있게 CTA를 유지한다.
 */
@Composable
private fun StretchTransitionScreen(step: Step, remainSec: Int, previewAsset: String?, onReady: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().background(HdBg).systemBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(step.name, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = HdInk, textAlign = TextAlign.Center)
        if (step.guide.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(step.guide, fontSize = 17.sp, color = HdMuted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        MediaFrame(Modifier.fillMaxWidth().weight(1f)) {
            val bmp = rememberAssetImage(context, previewAsset)
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text("🪑", fontSize = 72.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            color = HdCardFill,
            border = BorderStroke(1.dp, HdCardBorder),
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("⏳", fontSize = 18.sp)
                Text("  ${remainSec}초 뒤 자동 진행", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = HdInk)
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onReady,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HdGreenDark),
        ) { Text("준비됐어요", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * 완료 화면(시안 4-6) — 큰 체크 · 수고하셨어요 · 요약 칩 2개 · `확인` 버튼 1개.
 * '다시 보기'는 확정안에서 제거됐고, 정보가 많아지지 않게 짧게 유지한다.
 */
@Composable
private fun StretchCompletionScreen(routine: Routine, motionTotal: Int, playedMinutes: Float, onConfirm: () -> Unit) {
    val minutes = playedMinutes.roundToInt().coerceAtLeast(1)
    Column(
        modifier = Modifier.fillMaxSize().background(HdBg).systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(92.dp).background(HdGreen, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(20.dp))
        Text("수고하셨어요", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = HdInk)
        Spacer(Modifier.height(10.dp))
        Text("잘하셨어요!", fontSize = 17.sp, color = HdMuted)
        Text("${routine.title} ${motionTotal}동작을 마쳤어요", fontSize = 17.sp, color = HdMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoutineChip("🕐", "총 ${minutes}분")
            RoutineChip("🌿", "${motionTotal}동작 완료")
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HdGreenDark),
        ) { Text("확인", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
    }
}

// =====================================================================================
//  공용 조각
// =====================================================================================

/** 동작/프리뷰가 들어가는 공통 프레임 — 9:16 비율을 유지하며 가용 높이에 맞춘다(잘리지 않게). */
@Composable
private fun MediaFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f),
            shape = RoundedCornerShape(20.dp),
            color = HdCardFill,
            border = BorderStroke(1.dp, HdCardBorder),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** 단계 종류별 동작 표시(영상·이미지·2컷 교차·텍스트). 자산이 없으면 자리표시자로 그레이스풀하게 넘어간다. */
@Composable
private fun StepMedia(step: Step, context: Context, clipPlayer: ExoPlayer, toggleFrame: Int) {
    val mirror = if (step.mirror) -1f else 1f
    when (step.type) {
        StepType.VIDEO -> AndroidView(
            // PlayerView(SurfaceView)는 scaleX가 안 먹어 거울상이 안 됨 → TextureView로 반전(mirror).
            factory = { ctx -> TextureView(ctx).also { clipPlayer.setVideoTextureView(it) } },
            update = { it.scaleX = mirror },
            modifier = Modifier.fillMaxSize(),
        )
        StepType.IMAGE -> {
            val bmp = rememberAssetImage(context, step.asset)
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = step.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = mirror),
                )
            } else {
                Text("[이미지: ${step.asset}]", fontSize = 18.sp, color = HdMuted)
            }
        }
        StepType.IMAGE_TOGGLE -> {
            // 두 정지 이미지를 200ms 크로스페이드로 부드럽게 교차(딱 끊기면 시니어가 놀람, 명세 §3-0).
            val assets = step.assets.orEmpty()
            val currentAsset = assets.getOrNull(toggleFrame % assets.size.coerceAtLeast(1))
            Crossfade(targetState = currentAsset, animationSpec = tween(200), label = "poseToggle") { a ->
                val bmp = rememberAssetImage(context, a)
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = step.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text("[이미지: $a]", fontSize = 18.sp, color = HdMuted)
                }
            }
        }
        else -> Text(step.name, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = HdInk, textAlign = TextAlign.Center)
    }
}

/** "N번째 동작 / 총 N동작" 한 줄(시안 4-3 우측 정보). 안내 단계면 순번을 비운다. */
@Composable
private fun MotionCountRow(currentIndex: Int?, total: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            currentIndex?.let { "${it}번째 동작" } ?: " ",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = HdInk,
            modifier = Modifier.weight(1f),
        )
        Text("총 ${total}동작", fontSize = 14.sp, color = HdMuted)
    }
}

/**
 * 점형 동작 진행 인디케이터(시안 확정: 분수형 대신 점형). 현재 동작은 진한 초록 채움,
 * 이미 지난 동작은 연한 초록, 아직 안 한 동작은 바탕색+테두리로 3단계 차등한다.
 */
@Composable
private fun MotionProgressDots(total: Int, current: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..total).forEach { n ->
            val isCurrent = n == current
            val isDone = current != null && n < current
            Box(
                Modifier
                    .size(24.dp)
                    .background(
                        color = when {
                            isCurrent -> HdGreenDark
                            isDone -> HdGreenTint
                            else -> HdCardFill
                        },
                        shape = CircleShape,
                    )
                    .then(
                        if (isCurrent) Modifier
                        else Modifier.border(1.dp, if (isDone) HdGreen.copy(alpha = 0.4f) else HdUnselBorder, CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    fontSize = 11.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) Color.White else HdMuted,
                )
            }
        }
    }
}

/** 시작·완료 화면의 정보 칩(총 N동작 / 약 N분 등). 카드 규칙대로 바탕색 fill + 테두리. */
@Composable
private fun RoutineChip(emoji: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(14.dp),
        color = HdCardFill,
        border = BorderStroke(1.dp, HdUnselBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(emoji, fontSize = 15.sp)
            Text("  $label", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HdInk, maxLines = 1)
        }
    }
}

/** 보조 위계 버튼(이전·정지) — 연한 톤 채움(시안 §5-B). */
@Composable
private fun SoftControlButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HdCardFill,
            contentColor = HdInk,
            disabledContainerColor = HdCardFill,
            disabledContentColor = HdMuted.copy(alpha = 0.5f),
        ),
        border = BorderStroke(1.dp, HdUnselBorder),
        contentPadding = contentPadding,
    ) { Text(text, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/**
 * 배속 재생 시 한 실시간 틱(realTickMs)에 흐르는 '콘텐츠(동작 기준) 시간'(ms). speed 배 빨리 진행한다.
 *   타이머 elapsedMs 는 콘텐츠 시간이라 sec*1000 과 직접 비교 → 배속이면 단계가 speed 배 빨리 끝난다(영상·BGM 동기).
 */
internal fun scaledContentTickMs(realTickMs: Long, speed: Float): Long = (realTickMs * speed).toLong()

/** 배속 speed 에서 sec 초 단계가 끝나는 데 걸리는 실시간(ms). (검증·문서용) */
internal fun realStepDurationMs(sec: Int, speed: Float): Long = (sec * 1000L / speed).toLong()

/** 배속 표시 라벨(예: 1.0 → "1.0배"). 정수처럼 딱 떨어지는 값도 소수 한 자리로 통일. */
private fun speedLabel(speed: Float): String {
    val s = if (speed % 1f == 0f) "${speed.toInt()}.0" else speed.toString()
    return "${s}배"
}

/** 재생 속도 칩(우상단 고정, 48dp+). 현재 배속을 함께 보여줘 어르신이 상태를 알기 쉽게. */
@Composable
private fun SpeedGearButton(speed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        color = HdCardFill,
        border = BorderStroke(1.dp, HdUnselBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚙", fontSize = 18.sp)
            Text(" ${speedLabel(speed)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HdInk)
        }
    }
}

/** 재생 속도 선택 시트 — 0.75/1.0/1.25/1.5, 현재 선택 강조. 고르면 영상·타이머·BGM 에 즉시 동일 적용. */
@Composable
private fun SpeedPickerDialog(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HdBg,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 20.sp, color = HdGreen) } },
        title = { Text("재생 속도", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HdInk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.SPEED_OPTIONS.forEach { opt ->
                    val selected = abs(opt - current) < 0.001f
                    if (selected) {
                        Button(
                            onClick = { onSelect(opt) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HdGreenDark),
                        ) { Text("${speedLabel(opt)}  ✓", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(opt) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, HdUnselBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HdInk),
                        ) { Text(speedLabel(opt), fontSize = 22.sp) }
                    }
                }
            }
        },
    )
}

/** 원형 카운트다운 게이지 + 가운데 남은 초. 숫자는 크게, 단위('초')는 작게(시안 4-3 타이머 규칙). */
@Composable
private fun CircularTimer(progress: Float, seconds: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawArc(
                color = HdUnselBorder,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = HdGreenDark,
                startAngle = -90f, sweepAngle = 360f * (1f - progress), useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%02d".format(seconds), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = HdInk)
            Text("초", fontSize = 12.sp, color = HdMuted)
        }
    }
}

/** res/raw 리소스를 이름으로 찾아 android.resource:// URI 문자열로(없으면 null). 정인 펫 뷰와 동일 방식. */
//   JSON 루틴의 동적 자산 이름으로 조회해야 해서 getIdentifier가 맞는 방법(의도적 — lint 억제).
@SuppressLint("DiscouragedApi")
private fun rawUri(context: Context, name: String): String? {
    val resId = context.resources.getIdentifier(name, "raw", context.packageName)
    return if (resId != 0) "android.resource://${context.packageName}/$resId" else null
}

/** assets/exercise/{name}.jpg 이미지를 로드(없으면 null → 플레이스홀더 표시). */
@Composable
private fun rememberAssetImage(context: Context, name: String?): ImageBitmap? =
    remember(name) {
        if (name == null) return@remember null
        runCatching {
            context.assets.open("exercise/$name.jpg").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }.getOrNull()
    }