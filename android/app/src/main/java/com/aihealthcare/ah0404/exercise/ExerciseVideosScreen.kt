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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.media.StreamingVideoPlayer
import com.aihealthcare.ah0404.settings.AppSettings
import com.aihealthcare.ah0404.network.ExerciseVideoItem
import com.aihealthcare.ah0404.routine.RoutinePlayerScreen
import com.aihealthcare.ah0404.settings.TopBar
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
    vm: ExerciseVideosViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    // 선택 탭·루틴·전체화면 상태는 조기반환보다 '먼저' 선언한다 — 그래야 루틴/전체화면 진입으로 아래 UI가
    //   composition에서 빠져도 이 remember들이 폐기되지 않고 유지된다. (전체화면을 열었다 닫으면 근력/서서
    //   탭이 몸풀기(0)로 리셋되던 문제 — selected를 StageTabs 안에 두면 언마운트 시 사라짐. 지영 리뷰 #254 P1)
    var selectedTab by remember(vm.videos) { mutableIntStateOf(0) }
    var routineFile by remember { mutableStateOf<String?>(null) }
    var fullscreenUrl by remember { mutableStateOf<String?>(null) }

    // 번들 루틴(몸풀기·마무리)은 백엔드 목록과 무관하게 오프라인에서도 재생 가능(심사 환경 안정 버전).
    //   여러 동작을 조합한 가이드 루틴이라 단일 스트리밍 영상이 아니라 번들 RoutinePlayer 로 띄운다(#72 스트리밍과 별개).
    routineFile?.let { file ->
        RoutinePlayerScreen(
            routineFile = file,
            onExit = { routineFile = null },
            // durationMin(실제 재생 분)은 #234 운동 완료 배선(정인 레이어 B)에서 vm.submitExercise로 사용. 지금은 미사용.
            onComplete = { _ -> routineFile = null },
        )
        return
    }

    // 스트리밍 운동(근력·서서)은 포스터 탭 시 '가로 전체화면'으로 크게 재생한다(세로 고정 앱에서 이 화면만 가로).
    //   기기 크기가 달라도 fillMaxSize + 가로라 알아서 꽉 찬다(고정 픽셀 없음). 나가면 세로로 복원.
    fullscreenUrl?.let { url ->
        FullscreenLandscapeVideo(url = url, onExit = { fullscreenUrl = null })
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        TopBar(title = "영상 따라 운동하기", onBack = onBack)

        // 번들 루틴(몸풀기·마무리)은 네트워크와 무관하게 '즉시' 시작 가능해야 한다(오프라인/느린망 포함).
        //   서버 목록이 오면 탭으로, 아직이면(로딩/빈/에러) 폴백에서 번들 루틴 버튼들을 바로 보여준다.
        if (vm.videos.isNotEmpty()) {
            StageTabs(
                vm.videos,
                selected = selectedTab,
                onSelect = { selectedTab = it },
                onStartRoutine = { routineFile = it },
                onPlayFullscreen = { fullscreenUrl = it },
            )
        } else {
            RoutineFallback(
                onStart = { routineFile = it },
                loading = vm.loading,
                retry = if (vm.error) vm::load else null,
            )
        }
    }
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
private fun FullscreenLandscapeVideo(url: String, onExit: () -> Unit, onWatched: (Float) -> Unit = {}) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        // 시청 분 측정(#234 운동 완료 배선용, 정인 계약): 진입(재생 시작)~이탈(닫기/뒤로=세로 복원) 벽시계 경과.
        //   목표가 '하루 10분 몸 움직이기'라 배속과 무관한 실제 따라 한 시간=벽시계 분으로 잰다. onDispose에서
        //   onWatched(분) 발화 → 호출부(정인)가 서버 당일 누적에 합산. 0분(순간 열고닫음) 방어는 호출부에서.
        val startedAt = SystemClock.elapsedRealtime()
        val prevOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView) }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.requestedOrientation = prevOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
            onWatched((SystemClock.elapsedRealtime() - startedAt) / 60_000f)
        }
    }
    BackHandler { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        StreamingVideoPlayer(
            url = url,
            modifier = Modifier.fillMaxSize(),
            autoPlay = true, // 포스터 탭 = 재생 의사 → 한 번 탭으로 바로 재생(지영 리뷰 #254 P2)
            speed = AppSettings.exerciseSpeedFor(AppSettings.exerciseDifficulty),
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
