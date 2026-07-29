package com.aihealthcare.ah0404.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 정수 값을 −/+ 로 조절하는 시니어용 스테퍼(#261 온보딩 활동 일수 등).
 *  - 큰 원형 버튼(56dp)·큰 숫자로 고령 사용자 조작을 쉽게.
 *  - [min]..[max] 범위를 강제한다 — 경계에서 해당 버튼이 비활성이라 잘못된 값이 안 들어간다.
 *  - 화면 상단의 질문 Text 아래에 함께 쓴다(라벨은 이 컴포넌트가 갖지 않는다).
 *  - TalkBack: −/+ 버튼에 "줄이기/늘리기" contentDescription 을 준다.
 */
@Composable
fun AigoDayStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    max: Int,
    modifier: Modifier = Modifier,
    min: Int = 0,
    unitLabel: String = "일",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space16, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(symbol = "−", enabled = value > min, description = "$unitLabel 줄이기") {
            onValueChange((value - 1).coerceAtLeast(min))
        }
        Text(
            text = "$value$unitLabel",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        StepButton(symbol = "+", enabled = value < max, description = "$unitLabel 늘리기") {
            onValueChange((value + 1).coerceAtMost(max))
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = description },
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}
