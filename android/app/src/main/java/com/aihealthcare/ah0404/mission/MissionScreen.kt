package com.aihealthcare.ah0404.mission

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.floor
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.network.MissionTodayProgress
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoTonalButton
import com.aihealthcare.ah0404.ui.theme.Dimens

// 미션 메인 디자인 고도화 팔레트(시안 spec 토큰).
private val MGreen = Color(0xFF1E5B3A)   // primary_green(액션 버튼)
private val MInk = Color(0xFF1C1C1E)      // body_text
private val MMuted = Color(0xFF6B7280)    // secondary_text
private val MBg = Color(0xFFF7F6F0)       // screen_background
private val MTrack = Color(0xFFE3E5E1)    // progress_track(흰색 아님)
private val MCardBorder = Color(0xFFE6E4DB) // 카드 테두리(흰 카드 구분)
private val MPointBg = Color(0xFFF0A93B)  // 포인트 배지 골드

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

/** 미션이 오늘 목표를 채웠는지(유형별 완료 신호). 걷기·운동=goalReached, 식사=오늘 기록 있음, 게임=오늘 완료. */
internal fun missionDone(m: Mission): Boolean = when (m.missionType) {
    "walking", "exercise" -> m.todayProgress?.goalReached == true
    "meal" -> m.todayLog != null
    "game" -> m.todayDone == true
    else -> false
}

// pastel=아이콘 원/완료 카드 배경(연한 틴트), icon=아이콘 짙은 브랜드색(spec design_tokens).
private data class MissionVisual(val pastel: Color, val icon: Color, val vector: ImageVector)

/** 미션 유형별 색·아이콘(시안 spec). 카드는 흰색, 원(pastel)은 연한 틴트, 아이콘은 짙은 브랜드색. */
private fun missionVisual(type: String): MissionVisual = when (type) {
    "walking" -> MissionVisual(Color(0xFFE7F5EC), Color(0xFF2FA463), Icons.Filled.DirectionsWalk)
    "exercise" -> MissionVisual(Color(0xFFFFF1E6), Color(0xFFEF7F16), Icons.Filled.FitnessCenter)
    "meal" -> MissionVisual(Color(0xFFE6F1FF), Color(0xFF2E6CFF), Icons.Filled.Restaurant)
    "game" -> MissionVisual(Color(0xFFEFE9F8), Color(0xFF7252B8), Icons.Filled.SportsEsports)
    else -> MissionVisual(Color(0xFFF0F1EF), Color(0xFF6B7280), Icons.Filled.Star)
}

/** 카드 제목은 시안 spec의 유형명(걷기/운동/식사/게임 미션) — 백엔드 title 대신 통일. */
private fun missionTitle(type: String, fallback: String): String = when (type) {
    "walking" -> "걷기 미션"
    "exercise" -> "운동 미션"
    "meal" -> "식사 미션"
    "game" -> "게임 미션"
    else -> fallback
}

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MBg),
        contentAlignment = Alignment.Center,
    ) {
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
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(modifier = Modifier.height(10.dp)) }
                item {
                    Text(
                        text = "오늘의 미션",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MInk,
                    )
                }
                items(s.missions) { mission ->
                    // 모든 유형이 눌러서 이동(라우팅만). 걷기→측정 화면, 그 외→'준비 중'. 목적지는 호출부가 결정.
                    MissionCard(
                        mission = mission,
                        onClick = { onMissionClick(mission) },
                    )
                }
                // 하단 보너스 포인트 안내 카드(시안). 정보성.
                item { MissionBonusCard() }
                // 단백질 미션 숨김 사유(#304 요청 4): 미션이 '사라진' 게 아니라 건강 상태 때문에 쉬는 중임을
                //   알리고, 되돌릴 수 있는 곳(내 정보)으로 바로 보낸다. (단백질 미션은 현행 디자인 유지)
                s.proteinHiddenNotice?.let { notice ->
                    item { ProteinHiddenNoticeCard(notice = notice, onOpenProfileEdit = onOpenProfileEdit) }
                }
                item { Spacer(modifier = Modifier.height(10.dp)) }
            }
        }
    }
}

/** 단백질 미션 숨김 사유 카드(#304 요청 4). 사유 한 줄 + '내 정보' 이동 버튼. (단백질 미션 현행 유지) */
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

/**
 * 미션 카드(디자인 고도화 컴팩트): 유형별 색 카드 + 원형 아이콘 + 제목·포인트 + 진행(현재/목표)·진행바 + 액션/완료.
 * 카드 전체가 터치 영역(시니어 44dp+ 확보). 완료면 액션 버튼 대신 유형색 체크.
 */
@Composable
private fun MissionCard(mission: Mission, onClick: () -> Unit) {
    val v = missionVisual(mission.missionType)
    val done = missionDone(mission)
    val unit = targetUnitLabel(mission.targetUnit)
    val cur = when {
        mission.todayProgress != null ->
            formatTodayProgressMinutes(mission.todayProgress.totalMin, mission.todayProgress.goalReached)
        done -> mission.targetValue.toString()
        else -> "0"
    }
    val frac = when {
        mission.todayProgress != null -> todayProgressFraction(mission.todayProgress, mission.targetValue)
        done -> 1f
        else -> 0f
    }
    // 미션 카드만 예외: 지시서 색상(유형별 연한 틴트) 사용. 카드가 연하니 아이콘은 짙은 브랜드색으로(흰 원 없음 — 사용자 피드백).
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        // 카드 = 유형색 아주 옅은 틴트(5%). 원 18%, 아이콘 글리프만 브랜드색. (배경색 아님 — 사용자 확정)
        color = v.icon.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, v.icon.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 108.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 원도 옅게(브랜드색 18% 틴트), 아이콘 글리프만 브랜드색. 카드는 8%로 더 옅게.
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(v.icon.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(v.vector, contentDescription = null, tint = v.icon, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        missionTitle(mission.missionType, mission.title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MInk,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PointsBadge(mission.rewardPoints)
                }
                // 진행현황 + 진행바를 한 세로 묶음(딱 붙임)으로, 버튼은 그 오른쪽에 — 사용자 피드백.
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$cur$unit / ${mission.targetValue}$unit",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { frac },
                            color = v.icon,
                            trackColor = MTrack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (done) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(v.icon),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "완료", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        // 식사(단백질)만 '기록하기', 나머지는 '시작하기'(시안). 실제 이동은 카드 클릭이 담당.
                        Surface(color = MGreen, shape = RoundedCornerShape(12.dp)) {
                            Text(
                                if (mission.missionType == "meal") "기록하기" else "시작하기",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 하단 보너스 안내 카드(시안): 선물 아이콘 + 문구 + 화살표. 정보성(현재 비인터랙티브). */
@Composable
private fun MissionBonusCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFEAF4EC),
        border = BorderStroke(1.dp, Color(0xFFCFE6D6)),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎁", fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("모든 미션을 완료하면", fontSize = 14.sp, color = MMuted)
                Spacer(Modifier.height(2.dp))
                Text("추가 보너스 포인트를 드려요!", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MInk)
            }
            Text("›", fontSize = 26.sp, color = MMuted)
        }
    }
}

/** 포인트 배지 'Ⓟ N'(골드 원 + 숫자, 시안 top-right). */
@Composable
private fun PointsBadge(points: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(MPointBg),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(5.dp))
        Text("$points", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MInk)
    }
}
