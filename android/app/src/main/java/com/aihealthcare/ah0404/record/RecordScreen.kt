package com.aihealthcare.ah0404.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.components.SegmentOption
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * `_13 기록` 화면(#기록탭 개편).
 *  - 나의 기록: 챌린지 수행 통계(일별 완료 추이·달력·걷기·챌린지 비율)만 표시(§5).
 *  - 근육 건강 정보: 긍정 점수(높을수록 좋음) + 시뮬레이션(§3·§4). 확률(%)·관리 필요도 표기는 없다.
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
    // 상단 세그먼트: 나의 기록(챌린지 통계) ↔ 근육 건강 정보(점수/시뮬레이션). (§1 명칭 변경)
    var tab by remember { mutableStateOf(RecordTab.RECORDS) }
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
                SegmentOption(RecordTab.RECORDS, "나의 기록"),
                SegmentOption(RecordTab.DASHBOARD, "근육 건강 정보"),
            ),
            selected = tab,
            onSelect = { tab = it },
            horizontal = true,
            modifier = Modifier.padding(
                horizontal = Dimens.ScreenPadding,
                vertical = Dimens.Space8,
            ),
        )

        when (tab) {
            RecordTab.RECORDS -> Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
            ) {
                // §5.1 일별 미션 완료 선그래프(최근 14일)
                AigoCard {
                    SectionTitle("최근 2주 미션 완료")
                    val keys = remember { recentDateKeys(14, System.currentTimeMillis()) }
                    val counts = remember(vm.lineLogs) { dailyCompletionCounts(vm.lineLogs, keys) }
                    Spacer(Modifier.height(Dimens.Space8))
                    CompletionLineChart(keys, counts)
                }

                // §5.2 미션 달력(월 뷰) — 일자 탭 시 바텀시트
                AigoCard {
                    SectionTitle("미션 달력")
                    Spacer(Modifier.height(Dimens.Space8))
                    MissionCalendar(
                        year = vm.calYear,
                        month1 = vm.calMonth,
                        resultByDate = vm.stampsByDate,
                        onPrevMonth = vm::showPreviousMonth,
                        onNextMonth = vm::showNextMonth,
                        onDaySelected = { selectedDay = it },
                    )
                }

                // §5.3 걷기 막대(시간/걸음 탭 전환)
                AigoCard {
                    SectionTitle("최근 7일 걷기")
                    Spacer(Modifier.height(Dimens.Space8))
                    AigoSegmentedSelector(
                        options = listOf(
                            SegmentOption(WalkingMetric.MINUTES, "시간(분)"),
                            SegmentOption(WalkingMetric.STEPS, "걸음 수"),
                        ),
                        selected = walkMetric,
                        onSelect = { walkMetric = it },
                        horizontal = true,
                    )
                    Spacer(Modifier.height(Dimens.Space8))
                    WalkingBarChart(vm.walkingDays, walkMetric)
                }

                // §5.4 챌린지 비율 도넛
                AigoCard {
                    SectionTitle("챌린지 비율")
                    Spacer(Modifier.height(Dimens.Space8))
                    val totals = vm.challengeTotals
                    if (totals == null) {
                        EmptyText("아직 기록이 없어요. 챌린지를 완료하면 이곳에 쌓여요.")
                    } else {
                        ChallengeDonut(totals)
                    }
                }

                MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT)
                Spacer(Modifier.height(Dimens.Space8))
            }

            // 근육 건강 정보(§3·§4) — WebView 은퇴, 네이티브 점수 화면(실API). 점수 미도착이면 화면이 "준비 중" 처리.
            RecordTab.DASHBOARD -> Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                val ui = vm.muscleScore
                if (ui == null) {
                    Text(
                        "불러오는 중이에요…",
                        modifier = Modifier.padding(Dimens.ScreenPadding),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MuscleScoreScreen(ui = ui, onGoToMissions = onGoToMissions)
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
                if (missions.isEmpty()) {
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
