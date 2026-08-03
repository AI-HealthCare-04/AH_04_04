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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.PillShape

/** 단일 선택 옵션 하나. */
data class SegmentOption<T>(val value: T, val label: String)

/**
 * 단일 선택 세그먼트 — 성별·예/아니오·질환 상태 등 구조화 enum 입력용(§5).
 *
 *  고령 사용자용으로 큰 알약형 탭 버튼. 선택 시 연녹색 채움 + 진녹 테두리로 명확히 구분.
 *  옵션 2개면 가로(horizontal), 3개 이상이면 세로 스택 권장.
 *
 * @param minHeight 셀의 최소 높이. 기본값은 기존 폼 입력용 56dp — 다른 화면(온보딩·내 정보)이
 *   이 값에 맞춰져 있어 바꾸지 않는다. 기록 화면 상단 탭은 48dp, 카드 안 단위 토글은 32dp 를 넘긴다.
 * @param compact 카드 안에 들어가는 작은 세그먼트(단위 토글 등). 폰트·좌우 패딩을 줄인다.
 *   **터치 영역은 [minHeight] 와 무관하게 48dp 를 확보**하므로(§10) 시각 크기만 줄어든다.
 */
@Composable
fun <T> AigoSegmentedSelector(
    options: List<SegmentOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    minHeight: Dp = Dimens.ButtonHeight,
    compact: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val selectAndDismissKeyboard: (T) -> Unit = { value ->
        focusManager.clearFocus()
        onSelect(value)
    }

    if (horizontal) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            options.forEach { opt ->
                SegmentCell(
                    opt = opt,
                    isSelected = opt.value == selected,
                    onClick = { selectAndDismissKeyboard(opt.value) },
                    minHeight = minHeight,
                    compact = compact,
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
                    onClick = { selectAndDismissKeyboard(opt.value) },
                    minHeight = minHeight,
                    compact = compact,
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
    minHeight: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    // 알약 자체는 minHeight 로 작아질 수 있지만, **터치 영역은 언제나 48dp 이상**(§10)이어야 한다.
    //   그래서 compact 일 때만 바깥에 48dp 높이의 Box 를 두고 알약을 그 안에 세로 가운데로 놓는다.
    //   (알약을 48dp 로 키우는 게 아니라, 작은 알약을 큰 터치 상자 안에 담는 방식)
    val cell = @Composable {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .semantics { selected = isSelected },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.Space12, vertical = if (compact) Dimens.Space4 else Dimens.Space12),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt.label,
                    style = if (compact) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
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

    if (compact) {
        Box(
            modifier = modifier.heightIn(min = Dimens.MinTouchTarget),
            contentAlignment = Alignment.Center,
        ) { cell() }
    } else {
        Box(modifier = modifier) { cell() }
    }
}
