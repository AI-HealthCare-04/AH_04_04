package com.aihealthcare.ah0404.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.settings.TopBar
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.MEDICAL_DISCLAIMER_DEFAULT
import com.aihealthcare.ah0404.ui.components.MedicalDisclaimer
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * `_13 나의 기록` — 역할분담 §3-정인, 작업순서 ④.
 *
 *  ⚠️ 비노출 계약(#57·§0-3): care_stage(순화 등급) 추이만 보여준다. 위험도 점수/등급 노출 금지.
 *     건강 관련 정보를 보여주므로 MedicalDisclaimer 필수.
 *  구성: care_stage 추이(GET /risk-predictions/me/history) + 활동 요약(GET /mission-logs).
 *  진입할 때마다 재조회(리뷰 #68 지적 1) + 섹션별 독립 로딩/오류(지적 2).
 */
@Composable
fun RecordScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: RecordViewModel = viewModel(),
) {
    // 화면 진입(재진입 포함)마다 최신 기록 재조회.
    LaunchedEffect(Unit) { vm.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "나의 기록", onBack = onBack)

        Column(
            Modifier.padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            // 건강 추이 카드 — 순화 등급 타임라인(비노출 계약)
            AigoCard {
                Text("건강 상태 변화", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Dimens.Space8))
                when {
                    vm.historyError -> ErrorRow(onRetry = vm::load)
                    vm.loading && vm.history.isEmpty() -> LoadingRow()
                    vm.history.isEmpty() -> EmptyText(
                        "아직 기록이 없어요. 간단한 건강 확인을 마치면 여기에서 변화를 볼 수 있어요.",
                    )
                    else -> HistoryTimeline(vm.history)
                }
            }

            // 활동 요약 카드 — 미션 로그 누적
            AigoCard {
                Text("그동안의 활동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Dimens.Space8))
                when {
                    vm.activityError -> ErrorRow(onRetry = vm::load)
                    vm.loading && !vm.loaded -> LoadingRow()
                    else -> Text(
                        "완료한 미션 ${vm.completedMissions}개 · 모은 포인트 %,d P".format(vm.totalPoints),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // 결과/기록 화면 필수 고지(§0-3)
            MedicalDisclaimer(text = MEDICAL_DISCLAIMER_DEFAULT)
            Spacer(Modifier.height(Dimens.Space8))
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(Dimens.Space16), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorRow(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
        Text(
            "불러오지 못했어요. 네트워크를 확인해 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("다시 시도") }
    }
}

@Composable
private fun HistoryTimeline(items: List<RiskHistoryItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.MinTouchTarget),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatDate(item.createdAt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (emoji, label) = careStageLabel(item.careStage)
                Text(
                    "$emoji  $label",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** KST ISO8601("2026-07-14T09:00:00+09:00") → "2026.07.14". 파싱 실패 시 원문 앞부분. */
private fun formatDate(iso: String): String =
    if (iso.length >= 10) iso.substring(0, 10).replace('-', '.') else iso

/** care_stage 순화 라벨(홈 화면과 동일 매핑). 비노출 계약: 점수/등급 문구 금지. */
private fun careStageLabel(stage: String): Pair<String, String> = when (stage) {
    "good" -> "👍" to "아주 좋아요"
    "action_needed" -> "💪" to "함께 챙겨봐요"
    else -> "🙂" to "잘 유지 중"
}
