package com.aihealthcare.ah0404.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.FaqItem
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 고객센터(_16) — GET /support(문의 이메일) + GET /support/faqs(자주 묻는 질문, #74) 배선.
 *  FAQ 문구는 서버 카탈로그에서 오므로 앱 배포 없이 갱신 가능. 이메일 조회 실패 시 기본 문의처 유지.
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SupportViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "고객센터", onBack = onBack)

        Column(
            Modifier.padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ElementGap),
        ) {
            AigoCard {
                Text("문의하기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Dimens.Space8))
                Text("이메일: ${vm.email}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Dimens.Space4))
                Text(
                    "궁금한 점을 이메일로 보내주시면 확인 후 답변드려요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("자주 묻는 질문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            when {
                vm.faqsError -> FaqError(onRetry = vm::load)
                vm.loading && vm.faqs.isEmpty() -> FaqLoading()
                vm.faqs.isEmpty() -> Text(
                    "표시할 질문이 아직 없어요.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> vm.faqs.forEach { FaqCard(it) }
            }
        }
    }
}

@Composable
private fun FaqCard(item: FaqItem) {
    AigoCard {
        Text("Q. ${item.question}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Dimens.Space8))
        Text(item.answer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FaqLoading() {
    Box(Modifier.fillMaxWidth().padding(Dimens.Space16), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FaqError(onRetry: () -> Unit) {
    AigoCard {
        Text(
            "질문을 불러오지 못했어요. 네트워크를 확인해 주세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.Space4))
        TextButton(onClick = onRetry) { Text("다시 시도") }
    }
}
