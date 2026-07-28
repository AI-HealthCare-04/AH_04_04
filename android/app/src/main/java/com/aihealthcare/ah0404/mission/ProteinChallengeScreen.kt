package com.aihealthcare.ah0404.mission

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.Mission
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.components.AigoPrimaryButton
import com.aihealthcare.ah0404.ui.components.AigoSecondaryButton
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 단백질 식사 기록 화면 (미션 유형 "meal").
 *
 * 신장질환자를 제외한 사용자가 **오늘 먹은 단백질 카테고리**([PROTEIN_CATEGORIES] 7종)를 골라 저장한다.
 * 서로 다른 [PROTEIN_DAILY_GOAL]종 이상이면 서버가 '오늘 단백질 챙김'으로 카운트하고 포인트를 준다.
 *
 * - 재진입: [Mission.todayLog] 가 있으면 이미 고른 카테고리를 미리 선택해둔다(편집 가능).
 * - 이 화면이 식사 미션의 **유일한 기록 지점**이다(즉시완료 미션, 별도 세션 없음).
 * - 저장은 upsert: 같은 날 다시 저장하면 새 기록이 아니라 오늘 기록을 갱신한다(이중 적립 없음).
 */
@Composable
fun ProteinChallengeScreen(
    mission: Mission,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // 저장 성공 시 1회 호출 — 호출부(MainActivity)가 미션 목록을 재조회해 today_log 를 서버 권위값으로
    //   갱신한다(리뷰 #225 P1: 갱신 없이는 재진입 선택 복원이 저장 직후 stale 값으로 깨진다).
    onSaved: () -> Unit = {},
    vm: ProteinChallengeViewModel = viewModel(),
) {
    BackHandler { onBack() }

    // 선택 상태: 재진입 시 오늘 기록(todayLog.eaten)으로 미리 채운다. 구성 변경에도 유지.
    var selected by rememberSaveable {
        mutableStateOf(mission.todayLog?.eaten?.filter { id -> PROTEIN_CATEGORIES.any { it.id == id } }.orEmpty())
    }
    val saveState by vm.saveState.collectAsState()
    val count = selected.size
    val goalMet = count >= PROTEIN_DAILY_GOAL

    // 저장 성공 → 미션 목록 재조회 트리거. 사용자가 결과 오버레이를 읽는 동안 갱신이 끝나,
    // 복귀·재진입 시점에는 today_log 가 이미 최신이다.
    LaunchedEffect(saveState) {
        if (saveState is ProteinSaveState.Saved) onSaved()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentPadding = PaddingValues(Dimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        text = "오늘 단백질 챙기기",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(Dimens.Space8))
                    Text(
                        text = "오늘 드신 단백질을 모두 골라주세요.\n${PROTEIN_DAILY_GOAL}가지 이상이면 목표 달성이에요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                    )
                    Spacer(Modifier.height(Dimens.Space16))
                    ProgressBanner(count = count, goalMet = goalMet)
                    Spacer(Modifier.height(Dimens.Space8))
                }
            }

            items(PROTEIN_CATEGORIES, key = { it.id }) { cat ->
                ProteinCard(
                    category = cat,
                    selected = cat.id in selected,
                    onToggle = {
                        selected = if (cat.id in selected) {
                            selected - cat.id
                        } else {
                            selected + cat.id
                        }
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(Dimens.Space8))
                    // 0개 선택도 저장 가능 — #224 계약상 빈 기록은 '오늘 안 먹었어요'라는 유효 기록이다
                    //   (리뷰 #225 P2). 버튼 문구가 그 의미를 미리 알려 실수 저장을 막는다.
                    AigoPrimaryButton(
                        text = proteinSaveButtonLabel(count),
                        onClick = { vm.save(mission, selected) },
                        enabled = saveState !is ProteinSaveState.Saving,
                    )
                    Spacer(Modifier.height(Dimens.Space8))
                    AigoSecondaryButton(text = "돌아가기", onClick = onBack)
                    if (saveState is ProteinSaveState.Error) {
                        Spacer(Modifier.height(Dimens.Space12))
                        Text(
                            text = (saveState as ProteinSaveState.Error).message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // 저장 중 오버레이 — 이중 탭 방지 + 진행 표시.
        if (saveState is ProteinSaveState.Saving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        // 저장 완료 결과 — 목표 달성 여부에 따라 문구를 다르게 안내.
        (saveState as? ProteinSaveState.Saved)?.let { done ->
            ProteinResultOverlay(
                result = done,
                onDone = {
                    vm.reset()
                    onBack()
                },
            )
        }
    }
}

/** 진행 배너: 몇 가지 골랐는지 + 목표 달성 강조. */
@Composable
private fun ProgressBanner(count: Int, goalMet: Boolean) {
    val container = if (goalMet) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (goalMet) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (goalMet) {
                "🎉 오늘 목표 달성! ${count}가지 선택했어요"
            } else {
                "${count} / ${PROTEIN_DAILY_GOAL}가지 선택했어요"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.Space16, horizontal = Dimens.Space12),
        )
    }
}

/** 단백질 카테고리 카드: 정사각 사진 + 이름. 선택 시 테두리·체크·강조. */
@Composable
private fun ProteinCard(
    category: ProteinCategory,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.Space16)
    Column(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onToggle)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier.border(Dimens.HairlineBorder, MaterialTheme.colorScheme.outlineVariant, shape)
                }
            )
            .padding(Dimens.Space8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(category.imageRes),
                contentDescription = category.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Dimens.Space12))
                    .alpha(if (selected) 1f else 0.9f),
            )
            if (selected) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.Space8),
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = Dimens.Space8, vertical = Dimens.Space4),
                    )
                }
            }
        }
        Spacer(Modifier.height(Dimens.Space8))
        Text(
            text = category.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.Space4),
        )
    }
}

/** 저장 완료 결과 오버레이 — 목표 달성/미달을 시니어가 알기 쉽게 안내. */
@Composable
private fun ProteinResultOverlay(result: ProteinSaveState.Saved, onDone: () -> Unit) {
    BackHandler { onDone() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        AigoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.ScreenPadding),
        ) {
            Text(
                text = if (result.countedForDaily) "🎉 잘하셨어요!" else "기록했어요",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimens.Space12))
            Text(
                // 신규 달성/재저장/빈 기록을 구분해 안내(순수 함수, 리뷰 #225 P1·P2).
                text = proteinResultMessage(result),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimens.Space24))
            AigoPrimaryButton(text = "확인", onClick = onDone)
        }
    }
}
