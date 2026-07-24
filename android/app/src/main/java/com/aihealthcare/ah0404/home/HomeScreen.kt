package com.aihealthcare.ah0404.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.pet.PetIdle
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.theme.Dimens
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 홈 화면(_3) — 화면 API 계약(재란) `GET /home` 필드 기준.
 *
 *  동적 항목은 전부 백엔드 바인딩(하드코딩 금지). 지금은 계약 필드명으로 mock 바인딩하고,
 *  실제 GET /home 매핑은 나중에 스위치한다(역할분담 §3-정인, 계약 초안 원칙).
 *  ⚠️ 비노출 계약: latest_prediction 은 care_stage + display_message 만. 위험도 점수/등급 없음.
 *  위험도 관련 카드에는 MedicalDisclaimer 필수(§0-3).
 */

/** GET /home 응답을 화면에 바로 뿌릴 형태로 정리한 UI 상태. */
data class HomeUi(
    val nickname: String,
    val points: Int,                    // point_balance.current_points
    val activityLevel: String,          // activity_profile.current_level (easy/normal/hard)
    val careStage: String?,             // latest_prediction.care_stage (good/maintain/action_needed)
    val predictionMessage: String?,     // latest_prediction.display_message
    val disclaimer: String?,
    val completedToday: Int,            // today_summary.counted_mission_count
    val availableMeal: Int,
    val availableExercise: Int,
    val availableWalking: Int,
    val availableGame: Int,
    val todayWalkingMin: Double = 0.0,  // today_walking.daily_total_min (#69)
    val todayWalkingSteps: Int = 0,     // today_walking.daily_total_steps (표시 전용)
) {
    val availableTotal: Int get() = availableMeal + availableExercise + availableWalking + availableGame
}

/** @Preview·테스트용 목업(런타임 화면은 GET /home 실데이터를 씀). */
fun mockHome() = HomeUi(
    nickname = "홍길동",
    points = 1250,
    activityLevel = "normal",
    careStage = "maintain",
    predictionMessage = "지금처럼 꾸준히 이어가고 있어요. 오늘도 가볍게 시작해 볼까요?",
    disclaimer = null,
    completedToday = 1,
    availableMeal = 2,
    availableExercise = 3,
    availableWalking = 1,
    availableGame = 1,
    todayWalkingMin = 22.0,
    todayWalkingSteps = 2350,
)

/** 홈 호스트 — GET /home 로드 후 콘텐츠 렌더. 진입마다 재조회. */
@Composable
fun HomeScreen(
    onGoMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenExercise: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    val ui = vm.ui
    when {
        // 캐시된 ui 가 있으면 콘텐츠 유지하되, 재조회 실패 시 상단 배너로 알린다(리뷰 #79:
        //   낡은 홈 데이터를 최신으로 오인하지 않게). 최초 실패는 전체 오류 화면.
        ui != null -> HomeContent(
            ui = ui,
            refreshError = vm.error,
            onRetry = vm::load,
            onGoMissions = onGoMissions,
            onOpenSettings = onOpenSettings,
            onOpenRecords = onOpenRecords,
            onOpenExercise = onOpenExercise,
            modifier = modifier,
        )
        vm.error -> HomeError(onRetry = vm::load, modifier = modifier)
        else -> HomeLoading(modifier)
    }
}

@Composable
private fun HomeLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun HomeError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Dimens.ScreenPadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("홈 정보를 불러오지 못했어요.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            AigoPrimaryButton(text = "다시 시도", onClick = onRetry)
        }
    }
}

@Composable
private fun HomeContent(
    ui: HomeUi,
    refreshError: Boolean,
    onRetry: () -> Unit,
    onGoMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenExercise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val visitStore = remember(context) { PetBubbleVisitStore(context) }
    val persistentUserId = SessionStore.persistentUserId
    val hourOfDay by produceState(initialValue = currentHourOfDay()) {
        while (true) {
            delay(60_000L)
            value = currentHourOfDay()
        }
    }
    val todayEpochDay = kstEpochDay()
    val previousVisit = remember(persistentUserId, todayEpochDay) {
        persistentUserId?.let(visitStore::read)
    }
    val bubbleMessage = remember(ui, refreshError, hourOfDay, previousVisit, todayEpochDay) {
        selectPetBubbleMessage(
            PetBubbleContext(
                nickname = ui.nickname,
                completedToday = ui.completedToday,
                availableMeal = ui.availableMeal,
                availableExercise = ui.availableExercise,
                availableWalking = ui.availableWalking,
                todayWalkingMin = ui.todayWalkingMin,
                todayWalkingSteps = ui.todayWalkingSteps,
                hourOfDay = hourOfDay,
                hasFreshHomeData = !refreshError,
                daysSinceLastVisit = daysSinceLastVisit(previousVisit?.lastVisitEpochDay, todayEpochDay),
                excludedMessageId = previousVisit?.lastMessageId,
            ),
        )
    }
    // 완료된 소셜 계정만 user_id별로 저장한다. 게스트·미완료·로그아웃 상태(null)는 앱 재실행에 남기지 않는다.
    // 저장 실패는 화면을 막지 않으며 다음 실행에서 기본 선택으로 안전하게 폴백한다.
    LaunchedEffect(persistentUserId, todayEpochDay, bubbleMessage.id) {
        persistentUserId?.let { userId ->
            visitStore.write(userId, PetBubbleVisitState(todayEpochDay, bubbleMessage.id))
        }
    }
    // 스크롤 콘텐츠 위에 마스코트 펫을 '고정 오버레이'로 얹는다.
    //   PetIdleView 는 GLSurfaceView(setZOrderOnTop) 라 스크롤 Column 안에 넣으면 떠서 깨지므로,
    //   Box 로 감싸 하단 코너에 고정한다(센서 탭과 동일 방식). 마지막 콘텐츠는 하단 여백으로 가림 방지.
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            Spacer(Modifier.height(Dimens.Space8))

        // 재조회 실패 시: 낡은 값 위에 안내 배너 + 재시도(리뷰 #79). 아래 값은 이전 정보일 수 있음.
        if (refreshError) {
            AigoCard {
                Text(
                    "최신 정보를 불러오지 못했어요. 아래 값은 이전 정보일 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.Space4))
                AigoSecondaryButton(text = "다시 불러오기", onClick = onRetry)
            }
        }

        // 인사 + 포인트(골드 강조)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${ui.nickname}님,\n오늘도 좋은 하루예요",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            PointsChip(ui.points)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "설정")
            }
        }

        Text(
            "활동 강도 · ${activityLevelLabel(ui.activityLevel)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 건강 상태(위험도 순화 문구) — 비노출 계약: care_stage/display_message 만 + 고지 필수
        AigoCard {
            val (emoji, title) = careStageLabel(ui.careStage)
            Text("$emoji  $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                ui.predictionMessage ?: "간단한 건강 확인을 마치면 맞춤 안내를 받을 수 있어요.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Dimens.Space12))
            MedicalDisclaimer(text = ui.disclaimer ?: MEDICAL_DISCLAIMER_DEFAULT)
        }

        // 오늘 요약
        AigoCard {
            Text("오늘의 활동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                "지금까지 미션 ${ui.completedToday}개를 완료했어요.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // 오늘 걷기(#69 today_walking) — 실적(분·걸음). 목표(분)는 GET /missions 원천이라 후속 배선.
        AigoCard {
            Text("오늘 걷기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                // 절삭 대신 반올림(0.9분+걸음이 "0분"으로 보이지 않게, 리뷰 #79).
                "오늘 ${ui.todayWalkingMin.roundToInt()}분 걸었어요 · %,d보".format(ui.todayWalkingSteps),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // 오늘의 미션 요약 + CTA
        AigoCard {
            Text("오늘의 미션", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Dimens.Space8))
            Text(
                "식사 ${ui.availableMeal} · 운동 ${ui.availableExercise} · 걷기 ${ui.availableWalking} · 게임 ${ui.availableGame}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Dimens.Space16))
            AigoPrimaryButton(
                text = "오늘의 미션 하러 가기 (${ui.availableTotal})",
                onClick = onGoMissions,
            )
        }

        // 나의 기록(_13) 진입 — 연속 예측 추이 + 활동 요약
        AigoSecondaryButton(text = "영상 따라 운동하기", onClick = onOpenExercise)
        AigoSecondaryButton(text = "나의 기록 보기", onClick = onOpenRecords)

        // TODO: 백엔드 연결 — 주간 리포트·걸음 목표/비교(계약 GAP: /home 확장 대기)
        Spacer(Modifier.height(Dimens.Space8))
        // 하단 코너 펫 오버레이가 마지막 콘텐츠를 가리지 않도록 여백 확보.
        Spacer(Modifier.height(320.dp))
        }

        PetSpeechBubble(
            text = bubbleMessage.text,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    start = Dimens.Space16,
                    end = Dimens.Space16,
                    bottom = 164.dp,
                ),
        )

        // 마스코트 펫(고개 갸웃 idle) — 배경 투명, 하단 코너 고정. 홈에 온기를 더한다.
        PetIdle(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Dimens.Space8)
                .size(160.dp),
        )
    }
}

@Composable
private fun PetSpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(
                horizontal = Dimens.Space16,
                vertical = Dimens.Space12,
            ),
        )
    }
}

private fun currentHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

@Composable
private fun PointsChip(points: Int) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = "%,d P".format(points),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
        )
    }
}

private fun activityLevelLabel(level: String): String = when (level) {
    "easy" -> "가볍게"
    "hard" -> "활발히"
    else -> "보통"
}

private fun careStageLabel(stage: String?): Pair<String, String> = when (stage) {
    "good" -> "👍" to "아주 좋아요!"
    "action_needed" -> "💪" to "조금만 더 함께 챙겨봐요"
    else -> "🙂" to "잘 유지하고 있어요"
}
