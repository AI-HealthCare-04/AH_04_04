package com.aihealthcare.ah0404.exercise

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import kotlin.math.floor
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.media.StreamingVideoPlayer
import com.aihealthcare.ah0404.settings.AppSettings
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.routine.RoutinePlayerScreen
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoDialog
import com.aihealthcare.ah0404.ui.theme.AigoWarningContainer
import com.aihealthcare.ah0404.ui.theme.Dimens

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
    var fullscreenUrl by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(routineFile, fullscreenUrl) {
        if (routineFile == null && fullscreenUrl == null) vm.retryPending()
    }

    // 번들 루틴(몸풀기·마무리)은 백엔드 목록과 무관하게 오프라인에서도 재생 가능(심사 환경 안정 버전).
    //   여러 동작을 조합한 가이드 루틴이라 단일 스트리밍 영상이 아니라 번들 RoutinePlayer 로 띄운다(#72 스트리밍과 별개).
    routineFile?.let { file ->
        RoutinePlayerScreen(
            routineFile = file,
            onExit = { routineFile = null },
            // 완주 시 실제 진행 분(#234): 확인 게이트를 통과해야만 여기 도달하므로 safetyConfirmed=true.
            //   VM 이 0분·미확인·템플릿 부재를 걸러 서버 당일 10분 누적에 합산한다.
            onComplete = { durationMin ->
                vm.submitExercise(durationMin, safetyConfirmed)
                routineFile = null
            },
        )
        return
    }

    // 스트리밍 운동(근력·서서)은 포스터 탭 시 '가로 전체화면'으로 크게 재생한다(세로 고정 앱에서 이 화면만 가로).
    //   기기 크기가 달라도 fillMaxSize + 가로라 알아서 꽉 찬다(고정 픽셀 없음). 나가면 세로로 복원.
    fullscreenUrl?.let { url ->
        FullscreenLandscapeVideo(
            url = url,
            onExit = { fullscreenUrl = null },
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        TopBar(title = "영상 따라 운동하기", onBack = onBack)

        // 전송 못 한 운동 기록이 남아 있으면(영속 outbox, #271) 사용자에게 알리고 수동 재시도를 제공한다.
        //   자동 재시도(ON_RESUME·목록 복귀)가 계속 실패하는 경우의 탈출구 — 눌러도 in-flight 가드로 이중 전송되지 않는다.
        if (vm.pendingResends.isNotEmpty()) {
            PendingSyncBanner(count = vm.pendingResends.size, onRetry = vm::retryPending)
        }

        // 오늘 누적 운동시간(#235): 서버가 합산한 당일 '분'을 보여줘 사용자가 완료(하루 목표 달성) 여부를 확인할 수 있게 한다.
        //   세션을 하나라도 완료해 서버 값이 오면 표시(그 전엔 숨김). 여러 단계·여러 세션이 합산돼 목표를 채우면 달성 안내.
        vm.todayExerciseMin?.let { minutes ->
            TodayExerciseSummary(minutes = minutes, goalReached = vm.todayGoalReached)
        }

        // 번들 루틴(몸풀기·마무리)은 네트워크와 무관하게 '즉시' 시작 가능해야 한다(오프라인/느린망 포함).
        //   서버 목록이 오면 탭으로, 아직이면(로딩/빈/에러) 폴백에서 번들 루틴 버튼들을 바로 보여준다.
        //   시작 동작은 guardedStart 로 감싸 안전 고지 확인(#234) 게이트를 먼저 거친다.
        if (vm.videos.isNotEmpty()) {
            StageTabs(
                vm.videos,
                selected = selectedTab,
                onSelect = { selectedTab = it },
                onStartRoutine = { file -> guardedStart { routineFile = file } },
                onPlayFullscreen = { url -> guardedStart { fullscreenUrl = url } },
            )
        } else {
            RoutineFallback(
                onStart = { file -> guardedStart { routineFile = file } },
                loading = vm.loading,
                retry = if (vm.error) vm::load else null,
            )
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
        Text(
            "오늘 운동 ${formatExerciseMinutes(minutes, goalReached)}분 하셨어요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            if (goalReached) "🎉 오늘 운동 목표를 채웠어요!" else "조금만 더 하면 오늘 목표를 채울 수 있어요.",
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
    onPlayFullscreen: (String) -> Unit,
) {
    // selected 는 ExerciseVideosScreen 이 보유(전체화면/루틴 진입 후 복귀 시 탭 유지, 지영 리뷰 #254 P1).
    val safeSelected = selected.coerceIn(0, videos.lastIndex)
    val current = videos[safeSelected]

    Column {
        TabRow(selectedTabIndex = safeSelected) {
            videos.forEachIndexed { index, v ->
                Tab(
                    selected = index == safeSelected,
                    onClick = { onSelect(index) },
                    text = { Text(v.label, style = MaterialTheme.typography.bodyLarge) },
                )
            }
        }
        VideoArea(current, onStartRoutine = onStartRoutine, onPlayFullscreen = onPlayFullscreen)
    }
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
    onPlayFullscreen: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding)
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                    // 포스터(세로 선택 카드) → 탭하면 가로 전체화면으로 크게 재생(포스터에 ▶·안내가 그려져 있어 오버레이 생략).
                    //   16:9 포스터를 16:9 박스에 Fit — 잘림 없이 카드 전체가 보인다.
                    Image(
                        painter = painterResource(poster),
                        contentDescription = "${item.label} 시작하기",
                        contentScale = ContentScale.Fit,
                        // TalkBack에서 버튼 역할로 안내(지영 리뷰 #254 비차단). 포스터에 그려진 문구 외 역할을 명확히.
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClickLabel = "재생", role = Role.Button) { onPlayFullscreen(url) },
                    )
                } else {
                    // 포스터 없는 스트리밍 단계(방어적) — 종전대로 인라인 재생.
                    StreamingVideoPlayer(
                        url = url,
                        modifier = Modifier.fillMaxSize(),
                        speed = AppSettings.exerciseSpeedFor(AppSettings.exerciseDifficulty), // 운동 난이도별 재생 속도
                    )
                }
            }
            // 준비중(서버 업로드 전) — 탭은 유지하되 안내.
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🎬", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "${item.label} 영상은 준비 중이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 가로 전체화면 영상 재생 — 세로 고정 앱에서 '이 화면만' 가로로 눕히고 시스템바를 숨겨 영상을 크게 보여준다.
 *   기기 크기가 달라도 fillMaxSize라 알아서 꽉 찬다(고정 픽셀 없음). 나가면(뒤로/닫기) 세로·시스템바를 복원한다.
 *   MainActivity의 configChanges(orientation|screenSize) 덕에 이 가로 전환이 액티비티를 재생성하지 않는다
 *   (screenOrientation=portrait는 유지 → 자동회전은 여전히 막힘, 여기서 강제한 가로만 처리. #122 세로 고정 의도 보존).
 */
@UnstableApi
@Composable
private fun FullscreenLandscapeVideo(
    url: String,
    onExit: () -> Unit,
    onWatched: (Float) -> Unit = {},
    startPositionMs: Long = 0L,
    onPositionSaved: (Long) -> Unit = {},
) {
    val activity = LocalContext.current as? Activity
    // 실제 재생 시간만 적립(#234, 리뷰 P1-A): ExoPlayer 의 isPlaying 구간만 합산 → 일시정지·버퍼링·백그라운드
    //   정지 시간은 빠진다. (종전 벽시계 방식은 멈춰 둔 시간까지 세어 하루 10분 목표가 과대 계상됐다.)
    val stopwatch = remember { PlaybackStopwatch() }
    DisposableEffect(Unit) {
        // 진입~이탈 사이 상태를 관리: 가로 강제·시스템바 숨김, 이탈 시 복원 + 실제 재생 분을 onWatched 로 발화.
        //   0분(순간 열고닫음/한 번도 재생 안 됨) 방어는 호출부(VM)에서. 배속과 무관하게 '실제 따라 한 시간'을 잰다.
        val prevOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView) }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.requestedOrientation = prevOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
            onWatched(stopwatch.elapsedMinutes(SystemClock.elapsedRealtime()))
        }
    }
    BackHandler { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        StreamingVideoPlayer(
            url = url,
            modifier = Modifier.fillMaxSize(),
            autoPlay = true, // 포스터 탭 = 재생 의사 → 한 번 탭으로 바로 재생(지영 리뷰 #254 P2)
            speed = AppSettings.exerciseSpeedFor(AppSettings.exerciseDifficulty),
            startPositionMs = startPositionMs, // 이어보기(#235): 직전 위치부터
            // 실재생 구간만 스톱워치에 반영(P1-A). 재생 시작=true 구간만 누적한다.
            onIsPlayingChanged = { isPlaying -> stopwatch.onIsPlayingChanged(isPlaying, SystemClock.elapsedRealtime()) },
            onPositionSaved = onPositionSaved, // 이어보기(#235): 이탈 시 현재 위치 보관(완주면 0)
        )
        // 닫기(세로 복귀) — 어르신용으로 크게, 반투명 배경으로 밝은 영상 위에서도 잘 보이게. 좌상단.
        //   가로에서 노치/펀치홀에 안 가리게 displayCutout inset 적용(지영 리뷰 #254 비차단).
        TextButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(Dimens.Space16)
                .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.large),
        ) {
            Text(
                "✕  닫기",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
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
    Box(Modifier.fillMaxSize().padding(Dimens.ScreenPadding), contentAlignment = Alignment.Center) {
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
