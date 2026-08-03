package com.aihealthcare.ah0404.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.theme.Dimens

/** 빈 상태 일러스트 크기(핸드오프 §4-4). */
private val IllustrationSize = 88.dp

/**
 * 빈 상태 안내 블록(핸드오프 §4-4) — "아직 기록이 없어요" 계열 카드의 공통 구성.
 *
 *  일러스트가 있으면 Row(좌 그림 / 우 텍스트), 없으면 Column. 일러스트는 장식이므로
 *  contentDescription 은 null 이다(§10) — 옆 텍스트가 같은 내용을 이미 말한다.
 *
 *  빈 상태에서 값이 0 인 차트를 그리지 않고 이 블록으로 갈아끼우는 것이 이번 리디자인의 핵심 중 하나다(§0-2).
 */
@Composable
fun AigoEmptyBlock(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    @DrawableRes illustration: Int? = null,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (illustration == null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            EmptyTexts(title, description)
            action?.invoke(this)
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(illustration),
            contentDescription = null, // 장식용(§10)
            modifier = Modifier.size(IllustrationSize),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            EmptyTexts(title, description)
            action?.invoke(this)
        }
    }
}

@Composable
private fun EmptyTexts(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    Text(
        description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
