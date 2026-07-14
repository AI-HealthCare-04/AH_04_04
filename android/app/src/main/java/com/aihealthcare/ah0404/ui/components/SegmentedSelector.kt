package com.aihealthcare.ah0404.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.PillShape

/** 단일 선택 옵션 하나. */
data class SegmentOption<T>(val value: T, val label: String)

/**
 * 단일 선택 세그먼트 — 성별·예/아니오·질환 상태 등 구조화 enum 입력용(§5).
 *
 *  고령 사용자용으로 큰 알약형 탭 버튼. 선택 시 연녹색 채움 + 진녹 테두리로 명확히 구분.
 *  옵션 2개면 가로(horizontal), 3개 이상이면 세로 스택 권장.
 */
@Composable
fun <T> AigoSegmentedSelector(
    options: List<SegmentOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    if (horizontal) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            options.forEach { opt ->
                SegmentCell(
                    opt = opt,
                    isSelected = opt.value == selected,
                    onClick = { onSelect(opt.value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            options.forEach { opt ->
                SegmentCell(
                    opt = opt,
                    isSelected = opt.value == selected,
                    onClick = { onSelect(opt.value) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun <T> SegmentCell(
    opt: SegmentOption<T>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.ButtonHeight),
        shape = PillShape,
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            Dimens.HairlineBorder,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(Dimens.Space12),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = opt.label,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
