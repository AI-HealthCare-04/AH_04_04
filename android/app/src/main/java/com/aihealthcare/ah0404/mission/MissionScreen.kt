package com.aihealthcare.ah0404.mission

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.floor
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionTodayProgress
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoTonalButton
import com.aihealthcare.ah0404.ui.theme.AigoOnWarningContainer
import com.aihealthcare.ah0404.ui.theme.AigoWarningContainer
import com.aihealthcare.ah0404.ui.theme.Dimens

internal fun targetUnitLabel(unit: String): String = when (unit) {
    "steps" -> "걸음"
    "reps" -> "회"
    "minutes" -> "분"
    "count" -> "회"
    "km" -> "km"
    "sets" -> "세트"
    else -> unit
}

/**
 * 오늘 누적 진행 분 표시 포맷(#235 목록 확장). 목표 미달은 버림, 달성은 반올림 — 미달인데 반올림으로 목표치처럼
 * 보이는 모순(예: 9.6분→"10분"인데 '조금만 더')을 막는다. 정수 분은 소수점 없이, 소수 분은 1자리로.
 * (부동소수 오차로 9.9f 가 9.8 로 내려가지 않게 1e-3 보정.)
 */
internal fun formatTodayProgressMinutes(minutes: Float, goalReached: Boolean): String {
    val scaled = minutes * 10.0 + 1e-3
    val tenths = if (goalReached) Math.round(scaled).toInt() else floor(scaled).toInt()
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0) whole.toString() else "$whole.$frac"
}

/** 카드에 보일 '오늘까지 …' 한 줄. 걷기는 걸음 누적도 곁들이고, 목표 달성 시엔 축하 문구로 바꾼다. */
internal fun todayProgressLine(progress: MissionTodayProgress): String {
    val mins = formatTodayProgressMinutes(progress.totalMin, progress.goalReached)
    val steps = progress.totalSteps
    val walked = if (steps != null && steps > 0) " · ${"%,d".format(steps)}걸음" else ""
    return if (progress.goalReached) "오늘 목표를 채웠어요 🎉 · ${mins}분$walked"
    else "오늘까지 ${mins}분$walked 하셨어요"
}

/** 진행바 채움 비율(0~1). 목표(분) 대비 오늘 누적 분. targetValue 가 0 이하면 0(방어). */
internal fun todayProgressFraction(progress: MissionTodayProgress, targetValue: Int): Float =
    if (targetValue <= 0) 0f else (progress.totalMin / targetValue).coerceIn(0f, 1f)

@Composable
fun MissionScreen(
    modifier: Modifier = Modifier,
    vm: MissionViewModel = viewModel(),
    // 미션 카드를 누르면 유형과 무관하게 호출 — 유형별 목적지 라우팅은 호출부(MainActivity)가 담당(#93).
    onMissionClick: (Mission) -> Unit = {},
    // 단백질 미션 숨김 사유 카드(#304 요청 4)의 '내 정보' 이동 — 목적지는 호출부(MainActivity)가 담당.
    onOpenProfileEdit: () -> Unit = {},
) {
    val state by vm.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is MissionUiState.Loading -> CircularProgressIndicator()

            is MissionUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "연결 실패",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                AigoPrimaryButton(text = "다시 시도", onClick = { vm.loadMissions() })
            }

            is MissionUiState.Success -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item {
                    Text(
                        text = "오늘의 미션",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(s.missions) { mission ->
                    // 모든 유형이 눌러서 이동(라우팅만). 걷기→측정 화면, 그 외→'준비 중'. 목적지는 호출부가 결정.
                    MissionCard(
                        mission = mission,
                        onClick = { onMissionClick(mission) },
                    )
                }
                // 단백질 미션 숨김 사유(#304 요청 4): 미션이 '사라진' 게 아니라 건강 상태 때문에 쉬는 중임을
                //   알리고, 되돌릴 수 있는 곳(내 정보)으로 바로 보낸다.
                s.proteinHiddenNotice?.let { notice ->
                    item { ProteinHiddenNoticeCard(notice = notice, onOpenProfileEdit = onOpenProfileEdit) }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

/** 단백질 미션 숨김 사유 카드(#304 요청 4). 사유 한 줄 + '내 정보' 이동 버튼. */
@Composable
private fun ProteinHiddenNoticeCard(notice: String, onOpenProfileEdit: () -> Unit) {
    AigoCard {
        Text(
            text = "고단백 식사 미션",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = notice,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        AigoTonalButton(text = "내 정보에서 변경하기", onClick = onOpenProfileEdit)
    }
}

@Composable
private fun MissionCard(mission: Mission, onClick: (() -> Unit)? = null) {
    AigoCard(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mission.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (mission.requiresSafetyNotice) {
                SafetyBadge()
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = mission.description.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "목표: ${mission.targetValue} ${targetUnitLabel(mission.targetUnit)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${mission.rewardPoints}pt",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 오늘 누적 진행(운동·걷기) — 재생/측정 전에도, 중간에 끊었어도 '오늘까지 얼마나 했는지'를 목표 아래에 보여준다.
        mission.todayProgress?.let { progress ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = todayProgressLine(progress),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (progress.goalReached) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { todayProgressFraction(progress, mission.targetValue) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 1회성 미션(식사·게임)의 '오늘 했음' 배지(#346) — 진행바 대신 완료/기록 문구로 표시.
        missionTodayBadge(mission)?.let { badge ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = badge,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (onClick != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                // 실제 수행/기록 화면이 있는 유형(걷기·식사)은 그 행동을, 나머지는 '준비 중'을 안내한다(리뷰 #225).
                text = missionCtaLabel(mission.missionType),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (missionCtaHighlighted(mission.missionType)) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SafetyBadge() {
    Surface(
        color = AigoWarningContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "안전 확인 필요",
            style = MaterialTheme.typography.labelSmall,
            color = AigoOnWarningContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
