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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay
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
    onComplete: () -> Unit = {},
) {
    val context = LocalContext.current
    val routine = remember { RoutineLoader.load(context, routineFile) }

    // BGM: 루틴 전체(251초) 동안 끊김 없이 1회 재생. 251초 전용 트랙이라 루프하지 않는다.
    val bgmPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            rawUri(context, routine.bgm)?.let { uri ->
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 0.4f
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true, // 전화 오면 자동 일시정지
                )
                prepare()
            }
        }
    }

    // 동작 클립: 단계마다 교체. 1~3.3초 루프를 sec 동안 반복(REPEAT_MODE_ALL). 오디오 이중 방어(volume 0).
    val clipPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
        }
    }

    var stepIndex by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }

    val step = routine.steps.getOrNull(stepIndex)

    // BGM은 루틴 시작과 동시에 1회 play.
    LaunchedEffect(Unit) { bgmPlayer.playWhenReady = true }

    // 일시정지/재개 — 타이머·클립·BGM 동시 제어(동기 유지).
    LaunchedEffect(paused) {
        if (paused) {
            bgmPlayer.pause(); clipPlayer.pause()
        } else if (!finished) {
            bgmPlayer.play()
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
        val totalMs = st.sec * 1000L
        while (elapsedMs < totalMs) {
            delay(50)
            if (!paused) elapsedMs += 50
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
            onComplete()
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

        // 영상이 주인공 → 헤더·타이머는 절제(동작명 30sp/안내 20sp/타이머 100dp)해 weight 미디어에 세로를 몰아줌.
        Text(step.name, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = InkColor, textAlign = TextAlign.Center)
        if (step.guide.isNotEmpty()) {
            Text(step.guide, fontSize = 20.sp, lineHeight = 28.sp, color = InkColor, textAlign = TextAlign.Center, maxLines = 2)
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
                    else -> Text(step.name, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = InkColor, textAlign = TextAlign.Center)
                }
            }
        }

        // 타이머(원형 게이지) 또는 카운트(횟수)
        val totalMs = step.sec * 1000f
        val progress = (elapsedMs / totalMs).coerceIn(0f, 1f)
        when (step.mode) {
            StepMode.TIMER -> {
                val remainSec = ceil((step.sec * 1000L - elapsedMs) / 1000.0).toInt().coerceAtLeast(0)
                CircularTimer(progress = progress, centerText = "$remainSec")
            }
            StepMode.COUNT -> {
                val count = step.count ?: 0
                val cur = if (count > 0) min(count, (progress * count).toInt() + 1) else 0
                Text("$cur / $count", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            StepMode.NONE -> {
                if (step.type == StepType.INTRO) {
                    Text("♪ Music by Suno AI", fontSize = 16.sp, color = Color(0xFFAAAAAA))
                }
            }
        }

        // 컨트롤 — 3개를 weight로 나눠 좁은 폭에도 들어가게. 어르신용으로 크게(높이 72dp·22sp).
        //   레이블을 2~3자(정지/다음/나가기)로 줄이고 내부 패딩·글자(20sp)도 축소 →
        //   320dp + 큰 글꼴 배율에서도 안 잘리게(지영 리뷰: 가용 ~82dp > 3자 필요폭).
        val ctrlPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            // 나가기 = 2차 강조(아웃라인 녹색): 브랜드 색 통일 + 실수 이탈 방지로 덜 튀게.
            OutlinedButton(
                onClick = { showExit = true },
                modifier = Modifier.weight(1f).height(72.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = ctrlPadding,
            ) { Text("나가기", fontSize = 20.sp, maxLines = 1) }
        }
    }

    // 나가기 — 실수 이탈 방지 확인 다이얼로그
    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            confirmButton = { TextButton(onClick = { showExit = false; onExit() }) { Text("나가기", fontSize = 22.sp) } },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("계속하기", fontSize = 22.sp) } },
            title = { Text("운동을 그만할까요?", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
            text = { Text("지금 나가면 진행 상황이 저장되지 않아요.", fontSize = 22.sp) },
        )
    }
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
