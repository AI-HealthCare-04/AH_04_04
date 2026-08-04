package com.aihealthcare.ah0404.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.R
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoEmptyBlock
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.MissionExerciseBg
import com.aihealthcare.ah0404.ui.theme.MissionExerciseFg
import com.aihealthcare.ah0404.ui.theme.MissionGameBg
import com.aihealthcare.ah0404.ui.theme.MissionGameFg
import com.aihealthcare.ah0404.ui.theme.MissionMealBg
import com.aihealthcare.ah0404.ui.theme.MissionMealFg
import com.aihealthcare.ah0404.ui.theme.MissionWalkBg
import com.aihealthcare.ah0404.ui.theme.MissionWalkFg

// =====================================================================================
// 미션 기록 탭 카드(#387 리디자인, 핸드오프 §7).
//   순서: 이번 달 요약 → 미션 달력 → 최근 7일 걷기 → 미션별 완료 현황.
//   표시 전용이다 — 집계는 RecordUiState.kt 의 순수 함수가 하고 여기서는 그리기만 한다.
// =====================================================================================

// ── M1 이번 달 요약 ───────────────────────────────────────────────────────────
/**
 * 달력을 보기 전에 "이번 달에 얼마나 했는지"를 한 줄로 먼저 준다(§7 M1).
 *  달력만 있으면 스탬프를 세어야 알 수 있어서, 시니어에게는 결론이 늦게 도착한다.
 *  빈 상태에서도 카드를 숨기지 않는다 — 0 이라는 사실 자체가 정보다.
 */
@Composable
internal fun MonthSummaryCard(
    summary: MonthSummary,
    modifier: Modifier = Modifier,
    // 아래 셋은 '표시 중인 달' 상태(PR #413 리뷰 P2). 기본값은 이번 달·조회 완료라 Preview 가 단순해진다.
    isCurrentMonth: Boolean = true,
    year: Int = 0,
    month1: Int = 0,
    loaded: Boolean = true,
    loadFailed: Boolean = false,
) {
    AigoCard(modifier = modifier, contentSpacing = Dimens.Space12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Dimens.Space4))
            Text(
                // 달력에서 다른 달로 이동하면 이 카드도 그 달을 집계한다 — 그런데 제목이 "이번 달"이면
                //   7월 숫자를 이번 달이라고 말하는 셈이 된다(리뷰 P2). 이번 달이 아닐 때는 달을 밝힌다.
                if (isCurrentMonth) "이번 달 요약" else "${year}년 ${month1}월 요약",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 조회 전/실패를 '0일 참여'로 위장하지 않는다(리뷰 P2) — 숫자 0 은 사실이고 미조회는 모름이다.
        if (!loaded || loadFailed) {
            Text(
                if (loadFailed) "기록을 불러오지 못했어요." else "불러오는 중이에요…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AigoCard
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${summary.joinedDays}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("일 참여", style = MaterialTheme.typography.titleMedium)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                CountLine("성공", summary.successCount)
                CountLine("대성공", summary.greatSuccessCount)
            }
        }
    }
}

/**
 * `🏆 성공  7 회` 한 줄. 라벨 영역을 고정 폭으로 잡아 **두 줄의 숫자가 세로로 정확히 맞게** 한다
 * (§7 M1 — weight 로 밀면 라벨 길이가 달라 숫자가 어긋난다).
 */
@Composable
private fun CountLine(label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🏆", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(Dimens.Space4))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            // 0 이어도 참여 일수와 달리 보조 정보라 톤을 낮춘다(§7 M1).
            color = if (count == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.width(Dimens.Space4))
        Text("회", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── M-EMPTY 빈 상태 안내 ──────────────────────────────────────────────────────
/**
 * 걷기 기록이 하나도 없을 때 M3 자리를 대신한다(§6 M3).
 *  값이 0 인 빈 막대그래프를 그리는 대신 무엇을 하면 채워지는지 알려 주는 것이 이 카드의 목적이다.
 */
@Composable
internal fun MissionEmptyCard(onGoToMissions: () -> Unit, modifier: Modifier = Modifier) {
    AigoCard(modifier = modifier) {
        AigoEmptyBlock(
            title = "아직 완료한 미션이 없어요",
            description = "미션을 완료하면 달력에 표시돼요!",
            illustration = R.drawable.img_record_empty_puppy,
        ) {
            AigoSecondaryButton(text = "미션 보러가기", onClick = onGoToMissions)
        }
    }
}

// ── M4 미션별 완료 현황 ───────────────────────────────────────────────────────
/**
 * 최근 7일 동안 유형별로 며칠 했는지(§7 M4). 기존 `챌린지 비율` 도넛을 대체한다 —
 * 도넛은 비율만 보여 줘서 "그래서 내가 뭘 안 했지?"에 답하지 못했다.
 * 분모가 항상 7이라 유형끼리 바로 비교된다.
 */
@Composable
internal fun MissionProgressCard(progress: List<MissionProgress>, modifier: Modifier = Modifier) {
    AigoCard(modifier = modifier, title = "미션별 완료 현황", contentSpacing = Dimens.Space12) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            progress.forEach { item ->
                MissionTile(item, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MissionTile(item: MissionProgress, modifier: Modifier = Modifier) {
    val done = item.done > 0
    // 한 번도 안 한 유형은 색을 빼서 '남은 일'로 읽히게 한다(§7 M4). 색은 기존 비활성 토큰.
    val circleBg = if (done) item.type.tileBg else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (done) item.type.tileFg else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            // 카드와 같은 바탕색이라 채움만으로는 타일 경계가 보이지 않는다 — 앱 공통 규칙(fill = 바탕색 + 테두리)대로
            //   hairline 테두리를 둘러 네 칸이 구분되게 한다(실기기 확인).
            .background(MaterialTheme.colorScheme.background, TileShape)
            .border(Dimens.HairlineBorder, MaterialTheme.colorScheme.outlineVariant, TileShape)
            .padding(vertical = Dimens.Space12, horizontal = Dimens.Space4)
            .semantics {
                contentDescription = "${item.type.label} ${item.done}일, 최근 ${item.total}일 중"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
    ) {
        Box(
            Modifier.size(36.dp).background(circleBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.type.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Text(
            item.type.label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "${item.done} / ${item.total}일",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 타일 모서리(§7 M4). */
private val TileShape = RoundedCornerShape(12.dp)

private val MissionKind.icon: ImageVector
    get() = when (this) {
        MissionKind.WALK -> Icons.Default.DirectionsWalk
        MissionKind.EXERCISE -> Icons.Default.FitnessCenter
        MissionKind.MEAL -> Icons.Default.Restaurant
        MissionKind.GAME -> Icons.Default.SportsEsports
    }

private val MissionKind.tileBg: Color
    get() = when (this) {
        MissionKind.WALK -> MissionWalkBg
        MissionKind.EXERCISE -> MissionExerciseBg
        MissionKind.MEAL -> MissionMealBg
        MissionKind.GAME -> MissionGameBg
    }

private val MissionKind.tileFg: Color
    get() = when (this) {
        MissionKind.WALK -> MissionWalkFg
        MissionKind.EXERCISE -> MissionExerciseFg
        MissionKind.MEAL -> MissionMealFg
        MissionKind.GAME -> MissionGameFg
    }
