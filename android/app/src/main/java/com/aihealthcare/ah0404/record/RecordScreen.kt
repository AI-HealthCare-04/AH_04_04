package com.aihealthcare.ah0404.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.components.SegmentOption
import com.aihealthcare.ah0404.ui.theme.Dimens

/** 하단 네비게이션이 마지막 카드를 가리지 않도록 두는 여백(§2). */
private val BottomSpacer = 96.dp

/** 걷기 단위 토글 — 카드 제목 옆에 붙는 작은 세그먼트(§7 M3). 터치 영역은 48dp 로 따로 확보된다. */
private val WalkToggleHeight = 32.dp
private val WalkToggleWidth = 132.dp

/**
 * `_13 기록` 화면(#기록탭 개편).
 *  - 근육 건강(기본 탭): 점수 → 변화 추이 → 또래 위치 → 5STS 추이 → 시뮬레이션 → 근력 안내 → 체감 피드백.
 *    긍정 점수(높을수록 좋음)만 쓰며 확률(%)·관리 필요도 표기는 없다(§3·§4).
 *  - 미션 기록: 미션 달력 → 최근 7일 걷기 → 챌린지 비율(§5).
 *
 *  ⚠️ 탭 배치 원칙(#385): "결론"(점수·추이)이 첫 화면에 오고, 그 근거가 되는 수행 원자료는 두 번째 탭에 둔다.
 *  결론을 두 번째 탭에 숨기면 대시보드로 기능하지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onBack: (() -> Unit)? = null,
    onGoToMissions: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: RecordViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    // 상단 세그먼트: 근육 건강(점수·추이·시뮬) ↔ 미션 기록(달력·걷기·챌린지).
    //   기본값은 근육 건강 — 화면을 열었을 때 결론이 먼저 보여야 한다(#385).
    var tab by remember { mutableStateOf(RecordTab.DASHBOARD) }
    // §5.2 달력 일자 탭 → 바텀시트로 그날 완료 미션 목록.
    var selectedDay by remember { mutableStateOf<String?>(null) }
    // §5.3 걷기 막대 축 전환(시간/걸음).
    var walkMetric by remember { mutableStateOf(WalkingMetric.MINUTES) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        TopBar(title = "나의 기록", onBack = onBack)

        AigoSegmentedSelector(
            options = listOf(
                // 상단바가 이미 "나의 기록"이라 세그먼트도 같은 이름이면 두 겹이 된다 → "미션 기록"으로 구분(#385).
                SegmentOption(RecordTab.DASHBOARD, "근육 건강"),
                SegmentOption(RecordTab.RECORDS, "미션 기록"),
            ),
            selected = tab,
            onSelect = { tab = it },
            horizontal = true,
            // 상단 탭만 48dp 로 줄인다(핸드오프 §3 유일한 예외). 다른 화면 세그먼트는 기존 56dp 그대로.
            minHeight = Dimens.MinTouchTarget,
            modifier = Modifier.padding(
                horizontal = Dimens.ScreenPadding,
                vertical = Dimens.Space8,
            ),
        )

        // 탭별 스크롤 위치를 따로 기억한다(§2) — 탭을 오갈 때마다 맨 위로 튀지 않게.
        val missionListState = rememberLazyListState()
        val muscleListState = rememberLazyListState()

        when (tab) {
            RecordTab.RECORDS -> LazyColumn(
                modifier = Modifier.weight(1f),
                state = missionListState,
                contentPadding = PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = Dimens.Space8,
                    bottom = BottomSpacer, // 마지막 카드가 하단 네비게이션에 가리지 않게(§2)
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.ElementGapLarge),
            ) {
                // ① 이번 달 요약 — 달력을 세지 않아도 결론이 먼저 보이게(§7 M1).
                item {
                    MonthSummaryCard(
                        summary = vm.monthSummary,
                        isCurrentMonth = vm.isCurrentMonth(),
                        year = vm.calYear,
                        month1 = vm.calMonth,
                        loaded = vm.monthLoaded,
                        loadFailed = vm.monthLoadFailed,
                    )
                }

                // ② 미션 달력(월 뷰) — 일자 탭 시 바텀시트.
                item {
                    AigoCard(title = "미션 달력", contentSpacing = Dimens.Space12) {
                        MissionCalendar(
                            year = vm.calYear,
                            month1 = vm.calMonth,
                            resultByDate = vm.stampsByDate,
                            recordedOnlyDates = mealRecordedOnlyDates(vm.monthLogs), // #343 문제 2
                            onPrevMonth = vm::showPreviousMonth,
                            onNextMonth = vm::showNextMonth,
                            onDaySelected = { selectedDay = it },
                        )
                    }
                }

                // ③ 최근 7일 걷기 — 기록이 하나도 없으면 빈 막대 대신 안내 카드로 교체한다(§6 M3).
                item {
                    val unit = if (walkMetric == WalkingMetric.MINUTES) WalkUnit.MINUTES else WalkUnit.STEPS
                    val points = vm.walk7d(unit)
                    if (isWalkEmpty(points)) {
                        MissionEmptyCard(onGoToMissions = onGoToMissions)
                    } else {
                        AigoCard(contentSpacing = Dimens.Space12) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "최근 7일 걷기",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                // 단위 전환은 유지하되 카드 절반을 먹던 큰 알약을 제목 옆 작은 세그먼트로(§7 M3).
                                AigoSegmentedSelector(
                                    options = listOf(
                                        SegmentOption(WalkingMetric.MINUTES, "분"),
                                        SegmentOption(WalkingMetric.STEPS, "걸음"),
                                    ),
                                    selected = walkMetric,
                                    onSelect = { walkMetric = it },
                                    horizontal = true,
                                    minHeight = WalkToggleHeight,
                                    compact = true,
                                    modifier = Modifier.width(WalkToggleWidth),
                                )
                            }
                            WalkingBarChart(
                                points = points,
                                unitLabel = if (unit == WalkUnit.MINUTES) "분" else "걸음",
                            )
                        }
                    }
                }

                // ④ 미션별 완료 현황 — 기존 '챌린지 비율' 도넛을 대체(§7 M4).
                item { MissionProgressCard(vm.missionProgress()) }
            }

            // 근육 건강(기본 탭) — 결론과 그 해석을 한곳에 모은다(#385).
            //   점수·추이·또래·5STS(MuscleDashboardCards) + 시뮬·근력안내(MuscleImprovementCards)
            //   + 피드백 + 고지 + 미션 기록으로 가는 링크. 점수 미도착이면 준비 중 안내.
            RecordTab.DASHBOARD -> LazyColumn(
                modifier = Modifier.weight(1f),
                state = muscleListState,
                contentPadding = PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = Dimens.Space8,
                    bottom = BottomSpacer,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.ElementGapLarge),
            ) {
                val ui = vm.muscleScore
                if (ui == null) {
                    item {
                        Text(
                            "불러오는 중이에요…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // ①~④ 점수 → 변화 → 또래 → 5STS (+ 빈 상태면 생활습관 카드)
                    item { MuscleDashboardCards(ui, onGoToMissions) }
                    // 점수가 없으면 위 섹션의 연령·예측 상태별 준비 카드 하나만 보여준다. 개선 섹션의
                    // ImprovementPendingCard까지 이어 붙이면 같은 안내와 미션 버튼이 중복된다(리뷰 #386).
                    // 피드백·"이 점수" 링크도 실제 점수가 있을 때만 의미가 있다.
                    if (ui.score != null) {
                        // ⑤ 시뮬레이션 → ⑥ 근력 안전망
                        item { MuscleImprovementCards(ui = ui, onGoToMissions = onGoToMissions) }
                        // ⑦ 체감 피드백(#357) — 결과를 다 본 뒤에 묻는다. 점수와 추이 사이에 두면
                        //    대시보드의 핵심 흐름(지금 어때? → 나아지고 있나?)이 끊기고, 사용자도
                        //    추이를 보기 전이라 답할 근거가 없다(#385).
                        ui.predictionId?.let { id ->
                            item { PredictionFeedbackCard(id, vm::submitPredictionFeedback) }
                        }
                    }
                    // ⑧ 의료 고지 — 점수를 보여주는 탭이므로 여기에 두고, 빈 상태에서도 항상 표시한다(§2-1).
                    item { MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT) }
                    if (ui.score != null) {
                        // ⑨ 결론 → 근거로 이어지는 경로(#385). 두 탭이 끊기지 않게 한다.
                        item {
                            AigoSecondaryButton(
                                text = "무엇이 이 점수를 만들었나요? · 미션 기록 보기",
                                onClick = { tab = RecordTab.RECORDS },
                            )
                        }
                    }
                }
            }
        }
    }

    // §5.2 일자 팝업: 그날 성공한 미션(미션명 + 완료 시각), 없으면 안내.
    selectedDay?.let { day ->
        ModalBottomSheet(onDismissRequest = { selectedDay = null }) {
            Column(Modifier.padding(Dimens.ScreenPadding)) {
                Text(
                    day.replace('-', '.'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Dimens.Space12))
                val missions = completedMissionsOn(vm.monthLogs, day)
                val mealRecordedOnly = mealRecordedOnlyOn(vm.monthLogs, day) // #343 문제 2
                if (missions.isEmpty() && mealRecordedOnly.isEmpty()) {
                    Text(
                        "이날은 완료한 미션이 없어요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    missions.forEach { m ->
                        Text(
                            "${m.title} · ${koreanTime(m.completedAt)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(Dimens.Space8))
                    }
                    // '안 먹었어요' 기록(#343 문제 2): 완료(적립)와 구분되는 보조 톤으로 흔적을 남긴다.
                    mealRecordedOnly.forEach { m ->
                        Text(
                            "단백질: 안 드신 것으로 기록 · ${koreanTime(m.completedAt)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Dimens.Space8))
                    }
                }
                Spacer(Modifier.height(Dimens.Space16))
            }
        }
    }
}

/** 나의 기록 각 카드 제목(#기록탭 §5). */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

/** 나의 기록 화면 상단 세그먼트 탭. */
internal enum class RecordTab { RECORDS, DASHBOARD }

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
