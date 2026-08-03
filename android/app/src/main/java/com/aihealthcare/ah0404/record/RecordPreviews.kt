package com.aihealthcare.ah0404.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.components.AigoSegmentedSelector
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.components.SegmentOption
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme
import com.aihealthcare.ah0404.ui.theme.Dimens

// =====================================================================================
// 기록 화면 Preview(#387, 핸드오프 §11) — 시안 01~04 와 카드 순서·구성을 눈으로 대조하기 위한 것.
//
//  ⚠️ 실제 화면(RecordScreen)은 ViewModel 을 통해 서버 값을 그린다. 여기서는 같은 카드들을
//   [RecordPreviewData] 로 배치할 뿐이고, **더미 데이터는 이 파일 밖으로 나가지 않는다**(§0-2).
//   ViewModel 이 필요한 카드(5STS·달력의 월 이동)는 상태를 직접 넘겨 그린다.
// =====================================================================================

private val PreviewStsHistory = emptyList<com.aihealthcare.ah0404.network.StsAssessmentItem>()

@Composable
private fun PreviewScaffold(content: @Composable () -> Unit) {
    MyApplicationTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Text(
                "나의 기록",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(Dimens.ScreenPadding),
            )
            content()
        }
    }
}

@Composable
private fun PreviewTabs(missionSelected: Boolean) {
    var tab by remember { mutableStateOf(if (missionSelected) RecordTab.RECORDS else RecordTab.DASHBOARD) }
    AigoSegmentedSelector(
        options = listOf(
            SegmentOption(RecordTab.DASHBOARD, "근육 건강"),
            SegmentOption(RecordTab.RECORDS, "미션 기록"),
        ),
        selected = tab,
        onSelect = { tab = it },
        horizontal = true,
        minHeight = Dimens.MinTouchTarget,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.Space8),
    )
}

private val PreviewContentPadding = PaddingValues(
    start = Dimens.ScreenPadding,
    end = Dimens.ScreenPadding,
    top = Dimens.Space8,
    bottom = 96.dp,
)

// ── 미션 기록 탭 ──────────────────────────────────────────────────────────────
@Composable
private fun MissionTabPreview(hasData: Boolean) {
    PreviewScaffold {
        PreviewTabs(missionSelected = true)
        LazyColumn(
            contentPadding = PreviewContentPadding,
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGapLarge),
        ) {
            item {
                MonthSummaryCard(
                    if (hasData) RecordPreviewData.monthSummary else RecordPreviewData.emptyMonthSummary,
                )
            }
            item {
                AigoCard(title = "미션 달력", contentSpacing = Dimens.Space12) {
                    MissionCalendar(
                        year = 2026,
                        month1 = 8,
                        resultByDate = if (hasData) previewStamps() else emptyMap(),
                        onPrevMonth = {},
                        onNextMonth = {},
                        onDaySelected = {},
                    )
                }
            }
            item {
                if (hasData) {
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
                            AigoSegmentedSelector(
                                options = listOf(
                                    SegmentOption(WalkingMetric.MINUTES, "분"),
                                    SegmentOption(WalkingMetric.STEPS, "걸음"),
                                ),
                                selected = WalkingMetric.MINUTES,
                                onSelect = {},
                                horizontal = true,
                                minHeight = 32.dp,
                                compact = true,
                                modifier = Modifier.width(132.dp),
                            )
                        }
                        WalkingBarChart(points = RecordPreviewData.walk7d, unitLabel = "분")
                    }
                } else {
                    MissionEmptyCard(onGoToMissions = {})
                }
            }
            item {
                MissionProgressCard(
                    if (hasData) RecordPreviewData.missionProgress else RecordPreviewData.emptyMissionProgress,
                )
            }
        }
    }
}

/** 시안 01 의 스탬프 배치(성공 7 · 대성공 5). */
private fun previewStamps(): Map<String, String> = mapOf(
    "2026-08-02" to "success", "2026-08-03" to "success", "2026-08-04" to "success",
    "2026-08-05" to "great_success", "2026-08-06" to "success", "2026-08-07" to "success",
    "2026-08-09" to "success", "2026-08-10" to "great_success", "2026-08-11" to "success",
    "2026-08-17" to "success", "2026-08-19" to "great_success", "2026-08-21" to "success",
    "2026-08-24" to "great_success", "2026-08-26" to "success", "2026-08-28" to "success",
    "2026-08-30" to "great_success",
)

// ── 근육 건강 탭 ──────────────────────────────────────────────────────────────
@Composable
private fun MuscleTabPreview(hasData: Boolean) {
    val ui = if (hasData) RecordPreviewData.muscleScore else RecordPreviewData.emptyMuscleScore
    PreviewScaffold {
        PreviewTabs(missionSelected = false)
        LazyColumn(
            contentPadding = PreviewContentPadding,
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGapLarge),
        ) {
            if (hasData) {
                item { ScoreHeadlineCard(ui, ui.score!!) }
                item { ScoreTrendCard(ui.trend) }
            } else {
                item { ScoreEmptyCard() }
                item { EmptyCohortCard() }
            }
            item {
                StsTrendCardBody(
                    history = PreviewStsHistory,
                    loaded = true,
                    loadError = false,
                    saving = false,
                    saveError = false,
                    onReload = {},
                    onRetrySave = {},
                    onMeasure = {},
                )
            }
            if (!hasData) item { LifestyleTipCard() }
            item { MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT) }
            if (hasData) {
                item { AigoSecondaryButton(text = "무엇이 이 점수를 만들었나요? · 미션 기록 보기", onClick = {}) }
            }
        }
    }
}

// ── §11 필수 Preview 8종 ──────────────────────────────────────────────────────
@Preview(name = "1. 미션 기록 · 데이터 있음", showBackground = true, heightDp = 1400)
@Composable
private fun PreviewMissionWithData() = MissionTabPreview(hasData = true)

@Preview(name = "2. 미션 기록 · 데이터 없음", showBackground = true, heightDp = 1400)
@Composable
private fun PreviewMissionEmpty() = MissionTabPreview(hasData = false)

@Preview(name = "3. 근육 건강 · 데이터 있음", showBackground = true, heightDp = 1200)
@Composable
private fun PreviewMuscleWithData() = MuscleTabPreview(hasData = true)

@Preview(name = "4. 근육 건강 · 데이터 없음", showBackground = true, heightDp = 1200)
@Composable
private fun PreviewMuscleEmpty() = MuscleTabPreview(hasData = false)

@Preview(name = "5. 미션 기록 · 글꼴 200%", showBackground = true, fontScale = 2f, heightDp = 2200)
@Composable
private fun PreviewMissionLargeFont() = MissionTabPreview(hasData = true)

@Preview(name = "6. 근육 건강 · 글꼴 200%", showBackground = true, fontScale = 2f, heightDp = 2000)
@Composable
private fun PreviewMuscleLargeFont() = MuscleTabPreview(hasData = true)

@Preview(name = "7. 미션 기록 · 좁은 화면", showBackground = true, widthDp = 320, heightDp = 1400)
@Composable
private fun PreviewMissionNarrow() = MissionTabPreview(hasData = true)

@Preview(name = "8. 근육 건강 · 좁은 화면", showBackground = true, widthDp = 320, heightDp = 1200)
@Composable
private fun PreviewMuscleNarrow() = MuscleTabPreview(hasData = true)
