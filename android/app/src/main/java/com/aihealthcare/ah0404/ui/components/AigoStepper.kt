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
 *
 *  값은 [Int]? 이다(리뷰 #267 지영님 블로커): **null = 미응답**, [min](0) = "안 해요".
 *  기본 0으로 시작하면 '안 만지고 넘긴 미응답'과 '주 0일'이 합쳐져 예측 입력이 조용히 왜곡되므로,
 *  처음엔 [placeholder]("선택해 주세요")를 보여 주고 호출부가 미응답을 '다음' 게이트로 막는다.
 *  '+'가 숫자 0으로 떨어지면 어색해서(리뷰 논의), min 위치는 숫자 대신 [zeroLabel]("안 해요")로 표시한다 —
 *  "선택해 주세요 → 안 해요 → 1일 → 2일…" 로 빈도가 올라가는 흐름이 된다.
 */
@Composable
fun AigoDayStepper(
    value: Int?,
    onValueChange: (Int) -> Unit,
    max: Int,
    maxLabel: String? = null,
    modifier: Modifier = Modifier,
    min: Int = 0,
    unitLabel: String = "일",
    zeroLabel: String = "안 해요",
    placeholder: String = "선택해 주세요",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space16, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 미응답(null)·최솟값에서 '−' 비활성. '+'는 null 이면 min(0)으로 진입한다.
        StepButton(symbol = "−", enabled = value != null && value > min, description = "$unitLabel 줄이기") {
            onValueChange((value!! - 1).coerceAtLeast(min))
        }
        Text(
            text = when {
                value == null -> placeholder
                value == min -> zeroLabel
                value == max && maxLabel != null -> maxLabel
                else -> "$value$unitLabel"
            },
            // 미응답 안내는 값이 아니므로 작고 흐리게 — 작은 화면(320dp)에서 긴 안내문이 버튼을 밀지 않게 weight 로 채운다.
            style = if (value == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f).widthIn(min = 72.dp),
        )
        StepButton(symbol = "+", enabled = value == null || value < max, description = "$unitLabel 늘리기") {
            onValueChange(if (value == null) min else (value + 1).coerceAtMost(max))
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
