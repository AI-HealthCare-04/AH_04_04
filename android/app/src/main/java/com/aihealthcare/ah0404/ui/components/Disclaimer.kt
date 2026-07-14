package com.aihealthcare.ah0404.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aihealthcare.ah0404.ui.theme.Dimens

/** 결과/기록 화면 등 위험도·건강 관련 정보를 보여줄 때 반드시 붙이는 의학적 비진단 고지(§0-3, 약관 #56). */
const val MEDICAL_DISCLAIMER_DEFAULT = "이 결과는 참고용이며 의학적 진단이 아닙니다."

/**
 * 의학적 비진단 고지 배너.
 *
 * @param text 서버 응답의 disclaimer 문구가 있으면 그대로, 없으면 기본 문구.
 */
@Composable
fun MedicalDisclaimer(
    modifier: Modifier = Modifier,
    text: String = MEDICAL_DISCLAIMER_DEFAULT,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(Dimens.CardPadding)) {
            Text(
                text = "ⓘ  $text",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
