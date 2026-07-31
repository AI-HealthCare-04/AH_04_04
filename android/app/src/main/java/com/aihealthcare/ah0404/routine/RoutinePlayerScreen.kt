package com.aihealthcare.ah0404.routine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

// 시니어 접근성: 밝은 회색 배경 + 진한 남색 텍스트 고대비
private val BgColor = Color(0xFFF0F0F5)
private val InkColor = Color(0xFF1A2340)
private val SafetyColor = Color(0xFFC62828)

/**
 * 운동 루틴 플레이어 — 화면 1개 + JSON 루틴으로 data-driven 재생.
 *  - BGM/클립을 별도 ExoPlayer로 분리(단계 전환 시 음악 리셋 방지).
 *  - 에셋(res/raw 영상·assets/exercise 이미지)이 없으면 텍스트+타이머로 진행(그레이스풀).
 *  - "운동하기"의 몸풀기 탭에서 진입(ExerciseVideosScreen). 스트리밍(#72)과 별개인 번들 루틴.
 */
@Composable
fun RoutinePlayerScreen(
    routineFile: String = "warmup_common.json",
    onExit: () -> Unit = {},
    onComplete: (durationMin: Float) -> Unit = {},
) {
    val context = LocalContext.current
    val routine = remember { RoutineLoader.load(context, routineFile) }

    // BGM: 루틴 전체(251초) 동안 끊김 없이 1회 재생. 251초 전용 트랙이라 루프하지 않는다.
    //   배경음악 끄기(music_enabled=false)면 미디어 준비·재생·오디오포커스 요청을 '아예 하지 않는다'(리뷰 #87):
    //   무음 재생조차 안 해 디코딩 자원을 안 쓰고, 다른 앱(음악/라디오)의 오디오 포커스를 뺏거나 덕킹하지 않는다.
    //   (설정은 루틴 진입 시점 값으로 고정 — 재생 중엔 설정 화면에 못 가므로 1회 읽기로 충분)
    val musicOn = com.aihealthcare.ah0404.settings.AppSettings.musicEnabled
    val bgmSoundScale = com.aihealthcare.ah0404.settings.AppSettings.soundScale
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

    var stepIndex by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    // 실제 진행 시간(#234 P1-B): 단계별 elapsedMs 와 달리 루틴 전체에서 '실제로 흐른' 시간만 누적한다(일시정지
    //   제외, 각 단계 리셋 안 됨). '다음'으로 스킵하면 그 단계는 스킵 시점까지만 반영 → 완료 전송이 실제 수행 분만 싣는다.
    var playedMs by remember { mutableLongStateOf(0L) }
    // 재생 속도(전역, 톱니로 조절). 영상·타이머·BGM 3곳에 동일 적용해 항상 동기(스펙 §4-4). 기본 1.0.
    var speed by remember { mutableFloatStateOf(AppSettings.playbackSpeed) }
    var showSpeed by remember { mutableStateOf(false) }

    val step = routine.steps.getOrNull(stepIndex)

    // 속도 변경을 영상(클립)·BGM 에 즉시 반영. 타이머는 아래 루프가 speed 를 실시간으로 읽어 반영한다.
    //   (재생 파라미터는 media item 과 무관한 player 속성이라 단계가 바뀌어도 유지된다.)
    LaunchedEffect(speed) {
        clipPlayer.setPlaybackSpeed(speed)
        bgmPlayer.setPlaybackSpeed(speed)
    }

    // BGM은 루틴 시작과 동시에 1회 play. (끄기면 준비 자체를 안 했으므로 재생도 안 한다)
    LaunchedEffect(Unit) { if (musicOn) bgmPlayer.playWhenReady = true }

    // 일시정지/재개 — 타이머·클립·BGM 동시 제어(동기 유지).
    LaunchedEffect(paused) {
        if (paused) {
            bgmPlayer.pause(); clipPlayer.pause()
        } else if (!finished) {
            if (musicOn) bgmPlayer.play()
            if (routine.steps.getOrNull(stepIndex)?.type == StepType.VIDEO) clipPlayer.play()
        }
    }

    // 단계 진입 시 클립 세팅. 자원 유무·단계 종류와 무관하게 '먼저' 이전 클립을 정지·비운다 →
    //   직전 VIDEO 단계의 영상이 이미지/텍스트 단계나 영상 누락 단계에 남아 재생되는 것을 차단(지영 리뷰 #82).
    //   (로더가 영상 누락을 로드 단계에서 이미 막지만, 방어적으로 여기서도 항상 초기화한다.)
    LaunchedEffect(stepIndex) {
        clipPlayer.stop()
        clipPlayer.clearMediaItems()
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
    LaunchedEffect(stepIndex) {
        elapsedMs = 0L
        val st = routine.steps.getOrNull(stepIndex) ?: run { finished = true; return@LaunchedEffect }
        // elapsedMs 는 '동작 기준(콘텐츠) 시간'이라 sec*1000 과 직접 비교한다. 배속이면 한 틱(50ms 실시간)에
        //   50*speed 만큼 콘텐츠 시간이 흘러 단계가 speed 배 빨리/느리게 끝난다(영상·타이머 동기). speed 를 루프에서
        //   실시간으로 읽으므로 재생 중 속도 변경 시 남은 시간이 즉시 재계산된다(스펙 §4-4 주의). playedMs 는 실제
        //   흐른 벽시계 시간(분 적립용)이라 배속과 무관하게 50ms 씩 누적한다.
        val totalMs = st.sec * 1000L
        while (elapsedMs < totalMs) {
            delay(50)
            if (!paused) { elapsedMs += scaledContentTickMs(50, speed); playedMs += 50 }
        }
        if (stepIndex + 1 < routine.steps.size) stepIndex++ else finished = true
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

    LaunchedEffect(finished) {
        if (finished) {
            bgmPlayer.pause(); clipPlayer.stop()
            // 완료 시 '실제로 진행한 분'(playedMs)을 넘긴다 — 전체 step 합이 아니라 실제 흐른 시간이라 '다음' 스킵·
            //   일시정지가 반영된다(리뷰 P1-B). #234 운동 완료 배선이 이 분을 서버 당일 10분 누적에 합산.
            onComplete(playedMs / 60_000f)
        }
    }

    // ---------------- UI ----------------
    //   헤더(고정) + 미디어(weight: 남는 세로 공간) + 타이머(고정) + 컨트롤(고정, 항상 보임).
    //   고정 9:16이 폭을 채우면 너무 높아 하단 버튼이 화면 밖으로 밀리므로 미디어를 weight로 둔다(리뷰 #78).
    Column(
        modifier = Modifier.fillMaxSize().background(BgColor).systemBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (step == null) return@Column

        // 재생 속도 톱니 — 상단 우측에 '자체 행'으로 배치(동작명과 겹치지 않게 공간 확보, 스펙 §4-3, 48dp+).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SpeedGearButton(speed = speed, onClick = { showSpeed = true })
        }

        // 전체 진행 표시(#335): 얇은 진행바 + "N개 동작 중 M번째". 단계 수(23)가 아니라 동작 '종류'(9)로 세어
        //   부담을 줄인다. 안내 단계(intro/notice/outro)는 카운트에서 빠지고 진행바만 흐른다.
        val progressLabel = remember(stepIndex) { routineProgressLabel(routine.steps, stepIndex) }
        val barFraction = routineElapsedFraction(routine.steps, stepIndex, elapsedMs)
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD8D8E0)),
        ) {
            Box(Modifier.fillMaxWidth(barFraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        Text(
            routineProgressCaption(progressLabel) ?: " ", // 안내 단계는 캡션 없이 진행바만(높이 유지용 공백)
            fontSize = 16.sp,
            color = InkColor.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

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

        // 영상이 주인공 → 헤더·타이머는 절제(동작명 30sp/안내 20sp/타이머 100dp)해 weight 미디어에 세로를 몰아줌.
        Text(step.name, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = InkColor, textAlign = TextAlign.Center)
        if (displayGuide.isNotEmpty()) {
            Text(displayGuide, fontSize = 20.sp, lineHeight = 28.sp, color = InkColor, textAlign = TextAlign.Center, maxLines = 2)
        }
        step.safety?.let {
            Text("⚠ $it", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SafetyColor, textAlign = TextAlign.Center)
        }

        // 미디어 — weight로 남는 공간 차지. 안쪽에서 9:16 비율 유지하며 가용 높이에 맞춤(잘리지 않게).
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val mirror = if (step.mirror) -1f else 1f
            Box(
                modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
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
                            Text("[이미지: ${step.asset}]", fontSize = 20.sp, color = Color.Gray)
                        }
                    }
                    StepType.IMAGE_TOGGLE -> {
                        // 두 정지 이미지를 200ms 크로스페이드로 부드럽게 교차(딱 끊기면 시니어가 놀람, 명세 §3-0).
                        val assets = step.assets.orEmpty()
                        val currentAsset = assets.getOrNull(toggleFrame % assets.size.coerceAtLeast(1))
                        Crossfade(targetState = currentAsset, animationSpec = tween(200), label = "poseToggle") { a ->
                            val bmp = rememberAssetImage(context, a)
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = step.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text("[이미지: $a]", fontSize = 20.sp, color = Color.Gray)
                            }
                        }
                    }
                    else -> Text(step.name, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = InkColor, textAlign = TextAlign.Center)
                }
            }
        }

        // 타이머(원형 게이지) 또는 카운트(횟수)
        val totalMs = step.sec * 1000f
        val progress = (elapsedMs / totalMs).coerceIn(0f, 1f)
        val remainSec = ceil((step.sec * 1000L - elapsedMs) / 1000.0).toInt().coerceAtLeast(0)
        when (step.mode) {
            StepMode.TIMER -> CircularTimer(progress = progress, centerText = "$remainSec")
            StepMode.COUNT -> {
                val count = step.count ?: 0
                val cur = if (count > 0) min(count, (progress * count).toInt() + 1) else 0
                Text("$cur / $count", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            StepMode.NONE -> when (step.type) {
                StepType.INTRO -> Text("♪ Music by Suno AI", fontSize = 16.sp, color = Color(0xFFAAAAAA))
                // 자세 전환 안내(#317): 예고 없이 넘어가지 않게 다른 단계와 동일한 원형 카운트다운을 표시한다
                //   ('의자에 앉아주세요' 등은 실제로 몸을 움직이는 순간 — 남은 시간을 보여야 서두르지 않는다).
                StepType.NOTICE -> CircularTimer(progress = progress, centerText = "$remainSec")
                else -> {}
            }
        }

        // 컨트롤(#335): '이전' 추가로 4개가 되면 320dp + 큰 글꼴에서 한 줄에 안 들어간다(기존 3개도 가용 ~82dp
        //   > 3자 필요폭으로 빠듯) → 2행 배치. 1행에 이동·재생 컨트롤 [이전][정지/재개][다음]을 모으고,
        //   2행에 2차 액션 [나가기]를 단독 배치한다. 어르신용으로 크게(72dp·20sp).
        val ctrlPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 이전 동작: stepIndex 1 감소 → LaunchedEffect(stepIndex)가 타이머·영상·카운트를 처음부터 재시작한다.
                //   첫 단계(인트로)에선 되돌아갈 곳이 없어 비활성(눈으로도 상태를 알 수 있게).
                Button(
                    onClick = { if (stepIndex > 0) stepIndex-- },
                    enabled = stepIndex > 0,
                    modifier = Modifier.weight(1f).height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = ctrlPadding,
                ) { Text("이전", fontSize = 20.sp, maxLines = 1) }

                Button(
                    onClick = { paused = !paused },
                    modifier = Modifier.weight(1f).height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = ctrlPadding,
                ) { Text(if (paused) "재개" else "정지", fontSize = 20.sp, maxLines = 1) }

                Button(
                    onClick = { if (stepIndex + 1 < routine.steps.size) stepIndex++ else finished = true },
                    modifier = Modifier.weight(1f).height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = ctrlPadding,
                ) { Text("다음", fontSize = 20.sp, maxLines = 1) }
            }

            // 나가기 = 2차 강조(아웃라인 녹색): 브랜드 색 통일 + 실수 이탈 방지로 덜 튀게. 단독 행이라 폭이 넉넉하다.
            OutlinedButton(
                onClick = { showExit = true },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = ctrlPadding,
            ) { Text("나가기", fontSize = 20.sp, maxLines = 1) }
        }
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
                    //   단 [MIN_ROUTINE_PLAYED_MS] 미만은 실수 진입으로 보고 버린다. onComplete 가 전송 후 화면을
                    //   닫으므로 이 경로에선 onExit 를 따로 부르지 않는다(0분 등은 VM 이 한 번 더 거른다).
                    if (playedMs >= MIN_ROUTINE_PLAYED_MS) onComplete(playedMs / 60_000f) else onExit()
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

/** 재생 속도 톱니(우상단, 48dp+). 현재 배속을 함께 보여줘 어르신이 상태를 알기 쉽게. */
@Composable
private fun SpeedGearButton(speed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.large,
        color = Color.White,
        border = BorderStroke(1.dp, InkColor.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚙", fontSize = 20.sp)
            Text(" ${speedLabel(speed)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkColor)
        }
    }
}

/** 재생 속도 선택 시트 — 0.75/1.0/1.25/1.5, 현재 선택 강조. 고르면 영상·타이머·BGM 에 즉시 동일 적용. */
@Composable
private fun SpeedPickerDialog(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 20.sp) } },
        title = { Text("재생 속도", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.SPEED_OPTIONS.forEach { opt ->
                    val selected = abs(opt - current) < 0.001f
                    if (selected) {
                        Button(
                            onClick = { onSelect(opt) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("${speedLabel(opt)}  ✓", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(opt) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        ) { Text(speedLabel(opt), fontSize = 22.sp) }
                    }
                }
            }
        },
    )
}

/** 원형 카운트다운 게이지 + 가운데 남은 초. 어르신이 숫자만으론 놓치므로 게이지 병행. */
@Composable
private fun CircularTimer(progress: Float, centerText: String) {
    val accent = MaterialTheme.colorScheme.primary // 브랜드 테마색(DrawScope 밖에서 읽어 arc에 전달)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            drawArc(
                color = Color(0xFFD8D8E0),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = -90f, sweepAngle = 360f * (1f - progress), useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(centerText, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = InkColor)
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
