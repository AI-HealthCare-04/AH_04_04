package com.aihealthcare.ah0404.exercise

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlin.math.floor
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.media.PlaybackStatusOverlay
import com.aihealthcare.ah0404.media.StreamingVideoPlayer
import com.aihealthcare.ah0404.media.VideoCache
import com.aihealthcare.ah0404.media.persistNormalizedSpeed
import com.aihealthcare.ah0404.settings.AppSettings
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.routine.RoutinePlayerScreen
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.theme.AigoWarningContainer
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.HdBg
import com.aihealthcare.ah0404.ui.theme.HdCardFill
import com.aihealthcare.ah0404.ui.theme.HdGreen
import com.aihealthcare.ah0404.ui.theme.HdGreenDark
import com.aihealthcare.ah0404.ui.theme.HdGreenTint
import com.aihealthcare.ah0404.ui.theme.HdInk
import com.aihealthcare.ah0404.ui.theme.HdMuted
import com.aihealthcare.ah0404.ui.theme.HdUnselBorder

/**
 * 운동하기 — 4단계(몸풀기·앉아서·서서·마무리) 영상 탭. 백엔드 #72(GET /exercise-videos) 배선.
 *
 *  available=true 단계는 스트리밍 재생, false 는 "준비중" 표시(탭은 숨기지 않음).
 *  현재 서버 업로드 전이라 대부분 준비중 — filename 채워지면 앱 코드 변경 없이 켜진다.
 */
@UnstableApi
@Composable
fun ExerciseVideosScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ExerciseVideosViewModel = exerciseVideosViewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    // 선택 탭·루틴·전체화면 상태는 조기반환보다 '먼저' 선언한다 — 그래야 루틴/전체화면 진입으로 아래 UI가
    //   composition에서 빠져도 이 remember들이 폐기되지 않고 유지된다. (전체화면을 열었다 닫으면 근력/서서
    //   탭이 몸풀기(0)로 리셋되던 문제 — selected를 StageTabs 안에 두면 언마운트 시 사라짐. 지영 리뷰 #254 P1)
    var selectedTab by remember(vm.videos) { mutableIntStateOf(0) }
    var routineFile by remember { mutableStateOf<String?>(null) }
    // 재생 중인 스트리밍 운동(서서·근력). 세로 재생→전체보기 전환은 ExercisePlayer 내부 상태라 여기선 항목만 보유.
    var playingItem by remember { mutableStateOf<ExerciseVideoItem?>(null) }
    // 안전 고지 게이트(#234, 리뷰 P1-C): 운동을 '처음 시작할 때' 1회 확인. 서버는 운동(requires_safety_notice=true)에서
    //   확인 안 되면 시작 POST 를 400 으로 막으므로(services/mission.py), 조작된 true 없이 사용자가 실제로 확인한 값만
    //   완료 전송의 safety_notice_confirmed(=safetyConfirmed, 이 방문 동안 유지)로 넘긴다. pendingStart 는 확인 대기 중 보류된 시작 동작.
    var safetyConfirmed by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 전송 실패로 남은 세션들(키별 보존)을 두 시점에 같은 키로 재시도한다(리뷰 #234 재검토). 같은 자연 키라 서버
    //   중복 없이 안전하고, POST 성공/PATCH 실패로 in_progress 만 남은 경우를 완료로 되살린다(#172). 남은 게 없으면 no-op.
    //   ① ON_RESUME: 앱을 백그라운드 갔다 돌아올 때. ② 목록 복귀: 운동을 마치고 이 화면으로 돌아온 직후(실패는 보통
    //   화면이 이미 RESUMED 인 동안 나므로 ON_RESUME 만으론 그 직후 재시도가 안 됨 → 복귀 시점에도 시도). 영속 아님.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.retryPending()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 루틴/전체화면을 닫고 목록으로 돌아오면(둘 다 null) 남은 전송을 재시도한다. 방금 실패한 건은 in-flight 가드로
    //   걸러지므로 이중 전송되지 않고, 이후(다음 운동 종료·재진입) 복귀 때 안전히 재시도된다.
    LaunchedEffect(routineFile, playingItem) {
        if (routineFile == null && playingItem == null) vm.retryPending()
    }

    // 번들 루틴(몸풀기·마무리)은 백엔드 목록과 무관하게 오프라인에서도 재생 가능(심사 환경 안정 버전).
    //   여러 동작을 조합한 가이드 루틴이라 단일 스트리밍 영상이 아니라 번들 RoutinePlayer 로 띄운다(#72 스트리밍과 별개).
    routineFile?.let { file ->
        RoutinePlayerScreen(
            routineFile = file,
            onExit = { routineFile = null },
            // 실제 진행 분(#234) 저장만 하고 화면은 닫지 않는다 — 완료 화면(시안 4-6)을 띄운 뒤에도 기록이 먼저
            //   안전하게 전송된다. VM 이 0분·미확인·템플릿 부재를 걸러 서버 당일 10분 누적에 합산한다.
            onComplete = { durationMin -> vm.submitExercise(durationMin, safetyConfirmed) },
            // 안전 고지는 시안대로 **시작 화면 위 팝업**이라 이 화면이 아니라 루틴 플레이어가 띄운다(§4-1).
            //   확인 결과는 여기로 되돌려 받아, 완료 전송의 safety_notice_confirmed 근거로 그대로 쓴다.
            needsSafetyConfirm = !safetyConfirmed,
            onSafetyConfirmed = { safetyConfirmed = true },
        )
        return
    }

    // 스트리밍 운동(근력·서서): 포스터 탭 → 세로 재생(출처 상시) → '영상 전체보기'로 가로 전체화면(출처 유지) →
    //   영상 끝나면(STATE_ENDED) 자동으로 포스터로 복귀(스펙 §3-1 진입1). 세로↔전체화면 전환에도 재생 위치 유지.
    playingItem?.let { item ->
        val url = item.videoUrl.orEmpty() // 이어보기 위치 키(항목별). available 단계라 실제로는 비어 있지 않다.
        ExercisePlayer(
            item = item,
            onExit = { playingItem = null },
            // 실제 재생 분(#234, P1-A: 일시정지·버퍼·백그라운드 제외). 게이트 통과 후라 safetyConfirmed=true.
            onWatched = { durationMin -> vm.submitExercise(durationMin, safetyConfirmed) },
            // 이어보기(#235): 직전 위치부터 재생하고, 이탈 시 현재 위치를 VM 에 보관해 다시 열면 이어서 본다.
            startPositionMs = vm.resumePositionFor(url),
            onPositionSaved = { positionMs -> vm.saveResumePosition(url, positionMs) },
        )
        return
    }

    // 확인 전 시작을 누르면 보류(pendingStart)하고 안전 안내를 띄운다. 확인하면 보류된 동작을 실행하고
    //   이후 이 방문 동안엔 다시 묻지 않는다(덜 번거롭게 — 완료 전송엔 safetyConfirmed 로 반영).
    pendingStart?.let { action ->
        ExerciseSafetyDialog(
            onConfirm = { safetyConfirmed = true; pendingStart = null; action() },
            onDismiss = { pendingStart = null },
        )
    }
    val guardedStart: (() -> Unit) -> Unit = { action ->
        // 실제 재생을 시작하는 순간(확인 통과 후) 이 세션의 자연 키를 새로 잡는다(#234-1): 세션마다 새 키라
        //   별개로 합산되고, 완료 전송이 실패해 재시도할 땐 같은 키를 재사용해 중복 집계를 막는다.
        val start = { vm.beginExerciseSession(); action() }
        if (safetyConfirmed) start() else pendingStart = start
    }
    // 번들 루틴(몸풀기·마무리)은 여기서 안내를 띄우지 않는다 — 시안대로 루틴의 '시작 화면 위'에서 팝업이 뜨기
    //   때문(§4-1). 여기서도 띄우면 같은 안내가 두 번 나온다. 세션 키만 잡고 바로 넘긴다.
    val startRoutine: (String) -> Unit = { file -> vm.beginExerciseSession(); routineFile = file }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HdBg)
            .systemBarsPadding(),
    ) {
        TopBar(title = "영상 따라 운동하기", onBack = onBack)

        // 시안 §5 레이아웃: 앱바만 고정하고 요약카드·탭·카드·안내를 하나의 스크롤로 흘린다. 예전엔 콘텐츠 영역을
        //   weight(1f) 로 잡아 남은 높이를 채웠는데(리뷰 #291 블로커2), 큰 글꼴·작은 화면에서는 카드 아래 안내가
        //   들어갈 자리가 없었다 — 스크롤로 두면 어떤 조합에서도 모든 요소에 도달할 수 있다.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            // 전송 못 한 운동 기록이 남아 있으면(영속 outbox, #271) 사용자에게 알리고 수동 재시도를 제공한다.
            //   자동 재시도(ON_RESUME·목록 복귀)가 계속 실패하는 경우의 탈출구 — 눌러도 in-flight 가드로 이중 전송되지 않는다.
            if (vm.pendingResends.isNotEmpty()) {
                PendingSyncBanner(count = vm.pendingResends.size, onRetry = vm::retryPending)
            }

            // 상단 진행 히어로 카드(시안 B): 오늘 운동 N분 / 목표 10분 + 진행바 + 할머니 이미지.
            vm.todayExerciseMin?.let { minutes ->
                ExerciseProgressCard(minutes = minutes, goalReached = vm.todayGoalReached)
            }

            // 번들 루틴(몸풀기·마무리)은 네트워크와 무관하게 '즉시' 시작 가능해야 한다(오프라인/느린망 포함).
            //   서버 목록이 오면 탭으로, 아직이면(로딩/빈/에러) 폴백에서 번들 루틴 버튼들을 바로 보여준다.
            //   시작 동작은 guardedStart 로 감싸 안전 고지 확인(#234) 게이트를 먼저 거친다.
            if (vm.videos.isNotEmpty()) {
                StageTabs(
                    vm.videos,
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    onStartRoutine = startRoutine,
                    onPlay = { item -> guardedStart { playingItem = item } },
                )
            } else {
                RoutineFallback(
                    onStart = startRoutine,
                    loading = vm.loading,
                    retry = if (vm.error) vm::load else null,
                )
            }
        }
    }

}

/** 상단 진행 히어로 카드(시안): 오늘 운동 N분 / 목표 10분 + 진행바 + 할머니 이미지(측정화면 자산 재사용). */
@Composable
private fun ExerciseProgressCard(minutes: Float, goalReached: Boolean) {
    val goal = 10
    val minLabel = if (minutes == minutes.toLong().toFloat()) minutes.toLong().toString() else String.format("%.1f", minutes)
    val frac = (minutes / goal).coerceIn(0f, 1f)
    val green = androidx.compose.ui.graphics.Color(0xFF1F5D3A)
    val muted = androidx.compose.ui.graphics.Color(0xFF6B726D)
    val grandma = com.aihealthcare.ah0404.fitness.rememberAssetImageBitmap("fitness/sts_standing.jpg")
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.Space8),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = androidx.compose.ui.graphics.Color(0xFFEAF3EC),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFCFE6D6)),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "오늘 운동 ${minLabel}분 했어요",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = green,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                Text("목표 ${goal}분 중 ${minLabel}분", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = green)
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { frac },
                    color = green,
                    trackColor = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Text(
                    if (goalReached) "오늘 목표를 채웠어요 🎉" else "조금만 더 하면 오늘 목표를 채울 수 있어요.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = muted,
                )
            }
            if (grandma != null) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                Image(
                    bitmap = grandma,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(0.72f),
                )
            }
        }
    }
}

/**
 * SharedPreferences 기반 영속 outbox(#271)를 주입한 [ExerciseVideosViewModel] 을 만든다.
 *  `viewModel()` 기본 팩토리는 Context 를 넘길 수 없어 실제 저장소를 붙이지 못하므로(→ 미전송 세션이 재시작 시 소실),
 *  applicationContext 로 만든 [SharedPrefsExerciseOutbox] 를 초기화 팩토리로 주입한다. 테스트/프리뷰는 vm 을 직접 넘겨 우회.
 */
@Composable
private fun exerciseVideosViewModel(): ExerciseVideosViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) {
        viewModelFactory {
            initializer { ExerciseVideosViewModel(outbox = SharedPrefsExerciseOutbox(context)) }
        }
    }
    return viewModel(factory = factory)
}

/**
 * 전송하지 못한 운동 기록 안내 배너(#271). 네트워크 장애 등으로 서버 반영이 밀린 세션이 있을 때만 뜨며,
 * 자동 재시도와 별개로 '지금 바로' 보낼 수 있는 수동 탈출구를 제공한다. 안전 안내와 같은 앰버 배경으로 눈에 띄게.
 */
@Composable
private fun PendingSyncBanner(count: Int, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.Space8)
            .clip(MaterialTheme.shapes.large)
            .background(AigoWarningContainer)
            .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        Text(
            "아직 못 보낸 운동 기록이 ${count}개 있어요.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "인터넷 연결이 좋아지면 자동으로 다시 보내요. 지금 바로 보내려면 아래를 눌러 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text("지금 다시 보내기") }
    }
}

/**
 * 오늘 누적 운동 '분' 표시 문자열(#235, 리뷰 #280). 소수 1자리까지 보여주되 정수는 소수점 없이("10"),
 *  목표 미달(goalReached=false)이면 **반올림하지 않고 버림**한다 — 9.9분·미달이 "10분 + 조금만 더"로
 *  모순 표시되던 것 방지(서버 success=목표 도달이므로 미달값이 목표치처럼 보이면 안 됨). 목표 달성(달성 안내가
 *  함께 뜸)일 땐 반올림해 자연스럽게 보여준다. 1e-3 보정으로 9.9f 같은 부동소수 오차가 9.8 로 내려가는 것 흡수.
 */
internal fun formatExerciseMinutes(minutes: Float, goalReached: Boolean): String {
    val scaled = minutes * 10.0 + 1e-3
    val tenths = if (goalReached) Math.round(scaled).toInt() else floor(scaled).toInt()
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0) whole.toString() else "$whole.$frac"
}

/**
 * 오늘 누적 운동시간 안내(#235). 서버가 합산한 당일 운동 '분'과 목표 달성 여부를 보여줘, 여러 단계·여러 세션을
 * 나눠 해도 사용자가 완료(하루 목표)를 확인할 수 있게 한다. 값은 완료 응답의 서버 권위값이라 앱이 더하지 않는다.
 * 달성 시 밝은 녹색(secondaryContainer)으로 축하, 진행 중이면 같은 톤으로 계속 안내.
 */
@Composable
private fun TodayExerciseSummary(minutes: Float, goalReached: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.Space8)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        // 진입 시점엔 대개 0분(아직 운동 전) — "0분 하셨어요"는 어색하니 시작을 권하는 문구로 바꾼다.
        //   이미 했으면(중간에 끊었어도) 누적 분을, 목표를 채웠으면 축하를 보여준다(#235 확장, A2).
        val startedToday = minutes > 0f
        Text(
            if (startedToday) "오늘 운동 ${formatExerciseMinutes(minutes, goalReached)}분 하셨어요"
            else "오늘은 아직 운동 전이에요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            when {
                goalReached -> "🎉 오늘 운동 목표를 채웠어요!"
                startedToday -> "조금만 더 하면 오늘 목표를 채울 수 있어요."
                else -> "영상을 따라 운동을 시작해볼까요?"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 운동 진입 안전 고지 확인 게이트(#234). 서버가 운동 완료에 `safety_notice_confirmed=true`를 요구하므로,
 * 실제 확인을 거친 값만 넘기기 위해 시작 전 1회 안내를 띄운다. 안전 확인용 앰버 배경(디자인 시스템).
 */
@Composable
private fun ExerciseSafetyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AigoDialog(
        title = "🧡 운동 전, 안전하게 준비해요",
        message = "• 무리하지 말고 편한 만큼만 천천히 해요. 어지럽거나 아프면 곧바로 멈춰 주세요.\n" +
            "• 튼튼한 의자를 옆에 준비해 두세요. 서서 하는 동작은 의자나 벽을 잡고 균형을 지켜요.\n" +
            "• 넘어지거나 부딪히지 않게, 주변의 다른 물건은 미리 치워 주세요.",
        confirmText = "네, 확인했어요",
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
        containerColor = AigoWarningContainer,
    )
}

@Composable
private fun StageTabs(
    videos: List<ExerciseVideoItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onStartRoutine: (String) -> Unit,
    onPlay: (ExerciseVideoItem) -> Unit,
) {
    // selected 는 ExerciseVideosScreen 이 보유(전체화면/루틴 진입 후 복귀 시 탭 유지, 지영 리뷰 #254 P1).
    val safeSelected = selected.coerceIn(0, videos.lastIndex)
    val current = videos[safeSelected]

    Column {
        // 폭을 4등분하는 TabRow 는 "유산소 운동"(#333 개명으로 한 글자 길어짐)이 두 줄로 접힌다 — 한 칸에
        //   글자가 쓸 수 있는 폭이 약 71dp 뿐이라 13sp 이하로 줄여야 겨우 들어가는데, 시니어 대상에 그건 너무 작다.
        //   ScrollableTabRow(edgePadding=0)는 탭을 글자 길이에 맞춰 잡으므로 큰 글꼴에서도 접히지 않고,
        //   글자 크기 설정을 '크게'로 올려 폭이 모자라면 가로 스크롤로 넘어간다.
        ScrollableTabRow(
            selectedTabIndex = safeSelected,
            containerColor = HdBg,
            contentColor = HdGreen,
            edgePadding = 0.dp,
        ) {
            videos.forEachIndexed { index, v ->
                Tab(
                    selected = index == safeSelected,
                    onClick = { onSelect(index) },
                    selectedContentColor = HdGreen,
                    unselectedContentColor = HdMuted,
                    text = {
                        Text(
                            displayExerciseLabel(v.label),
                            fontSize = 16.sp,
                            maxLines = 1,
                            fontWeight = if (index == safeSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }
        VideoArea(current, onStartRoutine = onStartRoutine, onPlay = onPlay)
        // 하단 보조 메시지(시안 E) — 카드와 경쟁하지 않게 1~2줄 격려 문구만.
        ExerciseFooterNote(displayExerciseLabel(current.label))
    }
}

/** 카드 아래 잎사귀 안내 카드(시안 E). 선택한 운동 이름을 넣어 지금 무엇을 하는지 한 줄로 짚어 준다. */
@Composable
private fun ExerciseFooterNote(label: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.Space8),
        shape = RoundedCornerShape(16.dp),
        color = HdCardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, HdUnselBorder),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(HdGreenTint),
                contentAlignment = Alignment.Center,
            ) {
                Text("🌿", fontSize = 18.sp)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "오늘 리듬에 맞춰 ${objectParticle(label)} 해봐요.",
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = HdInk,
                )
                Text("천천히 따라 하면 몸이 더 건강해져요.", fontSize = 14.sp, lineHeight = 20.sp, color = HdMuted)
            }
        }
    }
}

/**
 * 뒤에 붙는 목적격 조사(을/를)를 받침에 맞춰 골라 붙인다. 운동 이름이 백엔드에서 오므로("몸풀기"·"근력 운동"…)
 * 조사를 고정하면 "몸풀기를"/"근력 운동을" 중 한쪽이 반드시 어색해진다.
 */
internal fun objectParticle(word: String): String {
    val last = word.trimEnd().lastOrNull() ?: return word
    // 한글 음절이 아니면(영문·숫자) 판단할 수 없으니 '를'로 둔다.
    if (last !in '가'..'힣') return "${word}를"
    val hasFinalConsonant = (last.code - 0xAC00) % 28 != 0
    return word + if (hasFinalConsonant) "을" else "를"
}

/**
 * 표시 라벨 방어 가드(#333 확정 "서서 운동"→"유산소 운동"). 라벨 값은 백엔드(GET /exercise-videos)에서 오는데
 * android 브랜치 백엔드가 stale해서 옛 이름 "서서"를 돌려줄 수 있어, 화면 표시 직전에 치환한다.
 * 내부 stage 키(standing 등)는 건드리지 않고 '보이는 글자'만 바꾼다. 음원명 등 '서서'가 없는 라벨은 그대로.
 */
internal fun displayExerciseLabel(label: String): String =
    if (label.contains("서서")) label.replace("서서", "유산소") else label

/** 플레이어 큰 제목 = 음원명(확정 매핑). 근력(seated)=우요일, 유산소(standing, 구 서서)=어느 봄날의 추억. 그 외 null. */
internal fun exerciseSongTitle(stage: String): String? = when (stage) {
    "seated" -> "우요일"
    "standing" -> "어느 봄날의 추억"
    else -> null
}

/** 번들 루틴(RoutinePlayer로 재생하는 조합형 가이드 운동). 스트리밍 목록과 무관하게 오프라인에서도 항상 재생 가능. */
internal data class BundledRoutine(val stage: String, val label: String, val file: String)

/**
 * 앱에 번들된 루틴들(단일 출처). 탭 경로(stage→file)와 목록 실패 폴백(라벨 버튼) 둘 다 여기서 파생돼,
 * 새 번들 루틴 추가 시 한 곳만 고치면 두 경로에 모두 노출된다(마무리 누락 재발 방지, 지영 리뷰 #240).
 */
internal val BUNDLED_ROUTINES: List<BundledRoutine> = listOf(
    BundledRoutine(stage = "warmup", label = "몸풀기 운동", file = "warmup_common.json"),
    BundledRoutine(stage = "cooldown", label = "마무리 운동", file = "cooldown_common.json"),
)

/** 번들 루틴 단계 → 루틴 JSON 파일(없으면 스트리밍 단계). 탭(VideoArea)이 번들/스트리밍을 가르는 데 쓴다. */
private fun bundledRoutineFile(stage: String): String? =
    BUNDLED_ROUTINES.firstOrNull { it.stage == stage }?.file

/**
 * 스트리밍 운동 단계의 포스터(선택 이미지). 있으면 바로 재생하지 않고 포스터를 먼저 보여주고, 탭하면 재생한다.
 * 없으면 종전대로 바로 재생. (근력=seated, 서서=standing)
 */
private fun exercisePosterRes(stage: String): Int? = when (stage) {
    "seated" -> R.drawable.exercise_poster_seated
    "standing" -> R.drawable.exercise_poster_standing
    else -> null
}

@UnstableApi
@Composable
private fun VideoArea(
    item: ExerciseVideoItem,
    onStartRoutine: (String) -> Unit,
    onPlay: (ExerciseVideoItem) -> Unit,
) {
    // 카드 자체는 재디자인하지 않고(시안 §4) 바깥 프레임만 정돈한다 — 원본 카드 비율(16:9) 유지, 잘림 없음.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .background(HdCardFill),
        contentAlignment = Alignment.Center,
    ) {
        val url = item.videoUrl
        val bundledRoutine = bundledRoutineFile(item.stage)
        when {
            // 몸풀기·마무리: 번들 루틴(따라 하는 실제 운동). 스트리밍 준비중과 별개로 오프라인에서도 지금 재생 가능.
            bundledRoutine != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🤸", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "따라 하는 ${item.label} 운동이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onStartRoutine(bundledRoutine) }) { Text("운동 시작하기") }
                }
            }
            // 스트리밍 단계: 포스터가 있으면 포스터→탭→재생(바로 재생 대신 선택 화면), 없으면 종전대로 바로 재생.
            item.available && url != null -> {
                val poster = exercisePosterRes(item.stage)
                if (poster != null) {
                    // 포스터(세로 선택 카드) → 탭하면 세로 재생 화면으로(출처 상시), 거기서 '전체보기' 시 가로 전체화면.
                    //   16:9 포스터를 16:9 박스에 Fit — 잘림 없이 카드 전체가 보인다.
                    Image(
                        painter = painterResource(poster),
                        contentDescription = "${displayExerciseLabel(item.label)} 시작하기",
                        contentScale = ContentScale.Fit,
                        // TalkBack에서 버튼 역할로 안내(지영 리뷰 #254 비차단). 포스터에 그려진 문구 외 역할을 명확히.
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClickLabel = "재생", role = Role.Button) { onPlay(item) },
                    )
                } else {
                    // 포스터 없는 스트리밍 단계(방어적) — 종전대로 인라인 재생(속도는 영상 톱니로 조절).
                    StreamingVideoPlayer(
                        url = url,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // 준비중(서버 업로드 전) — 탭은 유지하되 안내.
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🎬", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "${displayExerciseLabel(item.label)} 영상은 준비 중이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 스트리밍 운동(서서·근력) 재생기 — 스펙 §3-1 진입1.
 *   ① 세로 재생(가로영상 레터박스) + 출처 상시 노출 → ② '영상 전체보기'로 가로 전체화면(출처 유지) →
 *   ③ 영상 끝(STATE_ENDED) 또는 닫기 시 자동으로 포스터 화면 복귀.
 *
 *   단일 ExoPlayer 를 세로/전체화면이 공유하므로 전환 시 재생 위치가 유지된다. 속도는 컨트롤러 톱니(⚙)로
 *   조절(전역 [AppSettings.playbackSpeed], 기본 1.0). 실제 재생 분(isPlaying 구간)만 [onWatched] 로 발화(#234 P1-A).
 */
@UnstableApi
@Composable
private fun ExercisePlayer(
    item: ExerciseVideoItem,
    onExit: () -> Unit,
    onWatched: (Float) -> Unit = {},
    startPositionMs: Long = 0L,
    onPositionSaved: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val url = item.videoUrl ?: return
    val stopwatch = remember { PlaybackStopwatch() }
    var fullscreen by remember { mutableStateOf(false) }
    // 출처 팝업(시안 확정): 출처 전문은 영상 위에 상시 노출하지 않고 이 팝업에서만 보여준다.
    var showCredits by remember { mutableStateOf(false) }
    // 실제 영상 길이(정보 칩 "약 N분"). 준비되기 전엔 0 → 스펙 기본값("약 3분")으로 표시한다.
    var durationMs by remember(url) { mutableLongStateOf(0L) }
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnWatched by rememberUpdatedState(onWatched)
    val currentOnPositionSaved by rememberUpdatedState(onPositionSaved)

    // 재생 실패·버퍼링 상태(#345). retryKey 증가 → 플레이어 재생성(재시도), retryPositionMs 는 실패 지점 이어재생용.
    var playbackError by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    var retryKey by remember(url) { mutableIntStateOf(0) }
    var retryPositionMs by remember(url) { mutableLongStateOf(startPositionMs) }

    // 세로↔전체화면이 공유하는 단일 플레이어(캐시·전역속도). url 이 바뀌거나 재시도(#345)면 새로 만든다.
    val player = remember(url, retryKey) {
        val cacheFactory = CacheDataSource.Factory()
            .setCache(VideoCache.get(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                // 이어보기(#235) + 재시도 이어재생(#345): 최초엔 startPositionMs, 재시도면 실패 시점 위치.
                if (retryPositionMs > 0L) seekTo(retryPositionMs)
                prepare()
                // ★ 시안: 세로 화면은 '재생 화면'이 아니라 **운동 시작 전 준비 화면**이다 → 여기서 자동재생하지
                //   않고 대표 프레임만 보여준다. 실제 재생은 '운동 시작'으로 가로 전체화면에 들어갈 때 시작한다.
                playWhenReady = false
                volume = AppSettings.soundScale
                setPlaybackSpeed(AppSettings.playbackSpeed)
            }
    }

    // 리스너: 실재생 구간 누적(#234 P1-A) + 톱니 속도 전역 영속 + 영상 끝나면 자동 복귀(STATE_ENDED, 스펙 §3-3).
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) =
                stopwatch.onIsPlayingChanged(isPlaying, SystemClock.elapsedRealtime())
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // 컨트롤러 톱니로 옵션 밖 속도(2.0 등)를 골라도 전역 저장·실제 재생 모두 확정 4옵션으로 정규화(지영 리뷰, #288 공용).
                player.persistNormalizedSpeed(context, playbackParameters.speed)
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // READY 가 되어야 duration 을 알 수 있다(그 전엔 TIME_UNSET). 정보 칩의 "약 N분"에 쓴다.
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) { player.pause(); currentOnExit() }
            }
            override fun onPlayerError(error: PlaybackException) {
                // 네트워크 단절·서버 무응답(#345): 검은 화면 방치 대신 안내 + 재시도(아래 오버레이).
                playbackError = true
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 실패 시 전체화면을 접는다(#345) — 안내·재시도 오버레이는 세로 화면(PortraitPlay)에 있다.
    LaunchedEffect(playbackError) { if (playbackError) fullscreen = false }

    // 백그라운드 시 일시정지, 화면 이탈 시 release + 실제 재생 분 1회 발화. (전체화면 토글로는 재실행 안 됨 — player 안정)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 이어보기(#235): 완주(STATE_ENDED)면 0(다음 진입 처음부터), 아니면 현재 위치를 보관.
            val savedMs = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            currentOnPositionSaved(savedMs)
            player.release()
        }
    }

    // 실제 재생 분 발화는 '화면 이탈' 1회만(#345 주의): 위 블록은 재시도(플레이어 재생성)마다 dispose 되므로
    //   거기 두면 스톱워치 누적값이 중복 발화돼 시청 분이 이중 집계된다. 화면 수명(Unit) 에서만 알린다.
    DisposableEffect(Unit) {
        onDispose { currentOnWatched(stopwatch.elapsedMinutes(SystemClock.elapsedRealtime())) }
    }

    // 뒤로가기: 전체화면이면 시작 화면으로 접고, 시작 화면이면 목록으로 나간다(중간 이탈도 안전 복귀, 스펙 §3-3).
    BackHandler { if (fullscreen) fullscreen = false else onExit() }

    // 출처 팝업은 세로·가로 어느 쪽에서 열어도 같은 내용(시안 §6). 전체화면 위에도 그대로 뜬다.
    if (showCredits) {
        ExerciseCreditDialog(stage = item.stage, onDismiss = { showCredits = false })
    }

    if (fullscreen) {
        FullscreenLandscapeStage(
            player = player,
            stage = item.stage,
            // 전체화면을 나가면 시작 화면(준비 화면)으로 돌아오므로 재생을 멈춘다 — 보지 않는 영상이
            //   뒤에서 계속 흐르면 시청 시간(#234)까지 부풀려진다. 다시 '운동 시작'하면 그 자리에서 이어진다.
            onCollapse = { player.pause(); fullscreen = false },
            onShowCredits = { showCredits = true },
            buffering = buffering,
        )
    } else {
        ExerciseStartScreen(
            player = player,
            item = item,
            durationMs = durationMs,
            // 운동 시작 = 재생 시작 + 가로 전체화면 전환(시안 §3 흐름).
            onStart = { player.play(); fullscreen = true },
            onExit = onExit,
            onShowCredits = { showCredits = true },
            buffering = buffering,
            playbackError = playbackError,
            onRetry = {
                retryPositionMs = player.currentPosition.coerceAtLeast(0L)
                playbackError = false
                buffering = true
                retryKey++
            },
        )
    }
}

/**
 * 세로형 **운동 시작 화면**(시안 §4) — 재생 화면이 아니라 '준비 화면'이다.
 *   분류 라벨 → 음원명 제목 → 한 줄 안내 → 16:9 대표 영상(정지 프레임) → 정보 칩 2개 → 준비 안내 카드 →
 *   `운동 시작`(→ 가로 전체화면) → 가로 안내 → `영상·음원 출처 보기`(팝업).
 *
 *   시안 확정에 따라 '미리보기'라는 표현은 쓰지 않고, 출처 전문도 여기 본문에 늘어놓지 않는다(팝업으로).
 *   작은 화면(320dp·큰 글꼴)에서도 CTA 가 잘리지 않게 위쪽은 스크롤 영역, 아래 버튼·링크는 고정으로 둔다.
 */
@UnstableApi
@Composable
private fun ExerciseStartScreen(
    player: ExoPlayer,
    item: ExerciseVideoItem,
    durationMs: Long,
    onStart: () -> Unit,
    onExit: () -> Unit,
    onShowCredits: () -> Unit,
    buffering: Boolean = false,
    playbackError: Boolean = false,
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().background(HdBg).systemBarsPadding().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) {
                Text("✕  닫기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HdInk)
            }
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            // 분류는 작은 라벨, 큰 제목은 음원명(시안 §2 확정 매핑). 라벨은 "서서"→"유산소" 표시 가드를 거친다.
            Text(displayExerciseLabel(item.label), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HdGreen)
            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
            Text(
                exerciseSongTitle(item.stage) ?: displayExerciseLabel(item.label),
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                color = HdInk,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
            Text("음악에 맞춰 천천히 따라 해보세요", fontSize = 17.sp, lineHeight = 24.sp, color = HdMuted)
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))

            // 가로 원본 비율(16:9) 그대로 — 인물이 좌우로 움직이므로 중앙 크롭하지 않는다(시안 §4 영상 영역 원칙).
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                // 준비 화면이라 컨트롤러는 숨긴다 — 여기서 세로로 재생하지 않고 '운동 시작'으로만 들어간다.
                PlayerSurface(player, Modifier.fillMaxSize(), useController = false)
                // 실패·버퍼링 표시(#345): 검은 화면과 구분되는 안내 + 재시도.
                PlaybackStatusOverlay(buffering = buffering, error = playbackError, onRetry = onRetry)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoChip("🕐", exerciseDurationChipText(durationMs), Modifier.weight(1f))
                InfoChip("🧍", "서서 하는 운동", Modifier.weight(1f))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))

            // 준비 안내 카드 — 카드 fill 은 바탕색이 아니라 연한 초록(시안의 '팁' 블록). 다른 탭 카드 규칙과 달리
            //   여기선 안내를 눈에 띄게 하는 게 목적이라 초록 틴트를 쓴다(시안 01/03 참조 이미지 동일).
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = HdGreenTint,
                border = androidx.compose.foundation.BorderStroke(1.dp, HdGreen.copy(alpha = 0.25f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🌿  팁", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HdGreen)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    Text(
                        "주변을 정리하고 편하게 서서\n충분히 스트레칭한 후 시작하세요.",
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = HdInk,
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = HdGreenDark),
        ) {
            Text("운동 시작", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        Text(
            "가로 화면으로 재생돼요",
            fontSize = 15.sp,
            color = HdMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // 출처는 링크 하나로만 노출하고 전문은 팝업에서(시안 §6 확정).
        TextButton(onClick = onShowCredits, modifier = Modifier.fillMaxWidth()) {
            Text("영상·음원 출처 보기  ›", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HdGreen)
        }
    }
}

/** 시작 화면 정보 칩(약 N분 / 서서 하는 운동). 카드 규칙대로 바탕색 fill + 테두리. */
@Composable
private fun InfoChip(emoji: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        color = HdCardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, HdUnselBorder),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(emoji, fontSize = 15.sp)
            Text("  $label", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HdInk, maxLines = 1)
        }
    }
}

/**
 * 정보 칩의 소요시간 문구. 영상이 준비되면(duration 확보) 실제 길이를 올림해 "약 N분"으로 보여주고,
 * 아직 모르면 스펙 기본값("약 3분")을 쓴다 — 숫자가 잠깐 비거나 "약 0분"으로 보이지 않게.
 */
internal fun exerciseDurationChipText(durationMs: Long): String {
    if (durationMs <= 0L) return "약 3분"
    val minutes = kotlin.math.ceil(durationMs / 60_000.0).toInt().coerceAtLeast(1)
    return "약 ${minutes}분"
}

/**
 * 영상·음원 출처 팝업(시안 §6 확정). 출처 전문은 영상 위에 상시 노출하지 않고 여기서만 보여준다 —
 * 시작 화면의 '영상·음원 출처 보기' 링크와 전체화면 우상단 ⓘ 두 곳에서 열린다.
 * 문구는 리소스로만 관리(임의 제거 금지).
 */
@Composable
private fun ExerciseCreditDialog(stage: String, onDismiss: () -> Unit) {
    val creditRes = when (stage) {
        "standing" -> listOf(R.string.credit_standing_video, R.string.credit_music_suno)
        "seated" -> listOf(R.string.credit_strength_video, R.string.credit_music_suno)
        else -> return
    }
    // stringResource 는 @Composable 이라 joinToString 람다 안에서 부를 수 없다 — 먼저 읽어 두고 잇는다.
    val credits = creditRes.map { stringResource(it) }
    AigoDialog(
        title = "영상·음원 출처",
        message = credits.joinToString("\n\n"),
        confirmText = "확인",
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
    )
}

/**
 * 가로 전체화면 스테이지 — 세로 고정 앱에서 '이 화면만' 가로로 눕히고 시스템바를 숨겨 크게 보여준다.
 *   나가면(닫기/뒤로) 세로·시스템바 복원. MainActivity 의 configChanges(orientation|screenSize) 덕에
 *   액티비티 재생성 없이 이 화면만 가로가 된다(#122 보존).
 *
 *   ★시안 확정(§5): 출처 **전문을 영상 위에 상시 노출하지 않는다**. 대신 우상단 ⓘ 로 언제든 팝업을 열 수 있게
 *   해 운동 인물과 발 움직임이 글자에 가리지 않게 한다.
 */
@UnstableApi
@Composable
private fun FullscreenLandscapeStage(
    player: ExoPlayer,
    stage: String,
    onCollapse: () -> Unit,
    onShowCredits: () -> Unit,
    buffering: Boolean = false,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val prevOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView) }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.requestedOrientation = prevOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        PlayerSurface(player, Modifier.fillMaxSize())
        // 버퍼링 표시(리뷰 #348 2차): 전체화면에서도 느린 연결·서버 무응답이 검은 화면으로 보이지 않게.
        //   오류(playbackError)는 세로로 접혀 PortraitPlay 오버레이가 안내하므로 여기선 로딩만 겹친다.
        PlaybackStatusOverlay(buffering = buffering, error = false, onRetry = {})

        // ★ 상단 바(시안 §5): 좌측 닫기 · 중앙 음원명 · 우측 ⓘ(출처 팝업). Media3 기본 컨트롤러(시크바·재생·
        //   속도 톱니)는 '하단'에 뜨므로 상단은 이 세 가지만 두면 서로 가리지 않는다.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 아이콘 버튼(IconButton = 48dp 터치영역)으로 둬 TalkBack 이 역할·이름을 읽게 한다.
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.Close, contentDescription = "전체화면 닫기", tint = Color.White)
            }
            Text(
                exerciseSongTitle(stage).orEmpty(),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShowCredits) {
                Icon(Icons.Outlined.Info, contentDescription = "영상·음원 출처 보기", tint = Color.White)
            }
        }
    }
}

/**
 * 하나의 ExoPlayer 를 PlayerView 에 바인딩. 시작 화면/전체화면 양쪽이 같은 player 를 재사용한다.
 *  [useController] = 전체화면에서만 true — Media3 기본 컨트롤러(재생·시크바·시간·속도 톱니)가 시안이 요구하는
 *  하단 조작부를 그대로 제공한다. 시작 화면은 '준비 화면'이라 컨트롤러를 숨겨 세로 재생을 유도하지 않는다.
 */
@UnstableApi
@Composable
private fun PlayerSurface(player: ExoPlayer, modifier: Modifier, useController: Boolean = true) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx) },
        update = {
            it.useController = useController
            it.player = player
        },
        onRelease = { it.player = null },
    )
}

/**
 * ExoPlayer 의 isPlaying 구간만 합산해 '실제 재생 시간'을 재는 스톱워치(#234 P1-A).
 *   재생 시작(true)에 구간을 열고, 일시정지·버퍼링·끝·백그라운드 정지(false)에 구간을 닫아 누적한다 →
 *   멈춰 둔 시간은 빠진다. 이탈 시 [elapsedMinutes] 로 열린 구간을 닫아 총 재생 '분'을 얻는다.
 *   시각(now)은 호출부가 SystemClock.elapsedRealtime() 로 주입(테스트·단조증가 보장).
 */
internal class PlaybackStopwatch {
    private var accumulatedMs = 0L
    private var resumedAtMs = -1L // 현재 열린 재생 구간 시작(없으면 -1)

    fun onIsPlayingChanged(isPlaying: Boolean, now: Long) {
        if (isPlaying) {
            if (resumedAtMs < 0) resumedAtMs = now // 재생 시작 — 구간 열기(이미 열려 있으면 무시)
        } else {
            close(now) // 정지/버퍼/끝 — 구간 닫아 누적
        }
    }

    /** 열린 재생 구간을 닫아 누적에 반영(중복 호출 안전 — 열린 구간이 없으면 무시). */
    private fun close(now: Long) {
        if (resumedAtMs >= 0) {
            accumulatedMs += now - resumedAtMs
            resumedAtMs = -1
        }
    }

    /** 지금까지의 총 재생 시간(분). 열린 구간이 있으면 먼저 닫는다. */
    fun elapsedMinutes(now: Long): Float {
        close(now)
        return accumulatedMs / 60_000f
    }
}

/**
 * 백엔드 목록이 아직 없어도(로딩/오프라인/오류) 번들 루틴(몸풀기·마무리)은 '즉시' 시작할 수 있게 하는 폴백.
 * 목록 실패 상태에서도 [BUNDLED_ROUTINES] 를 모두 노출한다 — 오프라인에서 마무리에 진입 못 하던 문제 해소(지영 #240).
 * 서버 로딩은 이 버튼들을 막지 않고 "다른 운동 불러오는 중"으로만 별도 표시한다.
 */
@Composable
private fun RoutineFallback(onStart: (String) -> Unit, loading: Boolean, retry: (() -> Unit)?) {
    // 스크롤 부모 안이라 fillMaxSize 를 쓸 수 없다(높이 무한) — 카드 자리만큼 최소 높이를 확보한다.
    Box(
        Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(Dimens.ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Text("🤸", style = MaterialTheme.typography.headlineLarge)
            Text(
                "따라 하는 운동을 지금 할 수 있어요.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            BUNDLED_ROUTINES.forEach { routine ->
                Button(onClick = { onStart(routine.file) }) { Text("${routine.label} 시작하기") }
            }
            when {
                loading -> Text(
                    "다른 운동을 불러오는 중…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                retry != null -> TextButton(onClick = retry) { Text("다른 운동 다시 불러오기") }
            }
        }
    }
}
