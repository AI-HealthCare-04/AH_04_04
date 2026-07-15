package com.aihealthcare.ah0404.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.theme.Dimens

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
) {
    val availableTotal: Int get() = availableMeal + availableExercise + availableWalking + availableGame
}

// TODO: 백엔드 연결 — GET /home 응답을 HomeUi 로 매핑(HomeResponse 중첩 스키마 확정 후).
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
)

@Composable
fun HomeScreen(
    onGoMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenExercise: () -> Unit,
    modifier: Modifier = Modifier,
    ui: HomeUi = mockHome(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
    ) {
        Spacer(Modifier.height(Dimens.Space8))

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

        // 나의 기록(_13) 진입 — care_stage 추이 + 활동 요약
        AigoSecondaryButton(text = "운동하기 (영상 따라하기)", onClick = onOpenExercise)
        AigoSecondaryButton(text = "나의 기록 보기", onClick = onOpenRecords)

        // TODO: 백엔드 연결 — 주간 리포트·걸음 목표/비교(계약 GAP: /home 확장 대기)
        Spacer(Modifier.height(Dimens.Space8))
    }
}

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
