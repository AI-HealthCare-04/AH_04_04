package com.aihealthcare.ah0404.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.DialogShape

/**
 * 공통 팝업/다이얼로그 — 디자인 시스템 문서 §5, §9-6.
 *
 *  모서리 28dp, Surface 배경, 제목 titleLarge, 본문 bodyLarge, 하단은 공통 버튼 재사용(세로 스택
 *  = 고령 사용자용 큰 터치). 등장 시 페이드 + 살짝 스케일 인.
 *
 * @param dismissText null 이면 확인 버튼만 있는 단일 액션 팝업.
 */
@Composable
fun AigoDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        val scale by animateFloatAsState(if (visible) 1f else 0.92f, label = "dialogScale")
        val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "dialogAlpha")

        Surface(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(Dimens.Space24)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Dimens.Space12))
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Dimens.Space24))
                AigoPrimaryButton(text = confirmText, onClick = onConfirm)
                if (dismissText != null) {
                    Spacer(Modifier.height(Dimens.Space12))
                    AigoSecondaryButton(text = dismissText, onClick = onDismiss ?: onDismissRequest)
                }
            }
        }
    }
}
