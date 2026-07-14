package com.aihealthcare.ah0404.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aihealthcare.ah0404.ui.components.AigoCard
import com.aihealthcare.ah0404.ui.theme.Dimens

/**
 * 고객센터(_16) — 화면 API 계약: support → email.
 *  🟡 GAP: FAQ 목록 서버 관리 시 엔드포인트 필요 → 지금은 정적 mock(// TODO 서버 FAQ).
 */

// TODO: 백엔드 연결 — support email / FAQ 목록 응답으로 교체.
private const val SUPPORT_EMAIL = "support@aigo.example"

private val MOCK_FAQ = listOf(
    "음성 입력이 잘 안 돼요." to
        "조용한 곳에서 또박또박 말해 주세요. 인식이 안 되면 옆의 입력칸에 직접 입력할 수 있어요.",
    "미션은 하루에 몇 번 할 수 있나요?" to
        "미션마다 하루 횟수 제한이 있어요. 미션 카드에서 남은 횟수를 확인할 수 있어요.",
    "걸음 수가 안 세어져요." to
        "휴대폰을 들고 걸어 주세요. 화면의 '걷는 중' 표시가 켜지면 걸음이 집계돼요.",
    "포인트는 어디에 쓰나요?" to
        "모은 포인트는 추후 리워드로 사용할 수 있도록 준비 중이에요.",
)

@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                Text("이메일: $SUPPORT_EMAIL", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Dimens.Space4))
                Text(
                    "궁금한 점을 이메일로 보내주시면 확인 후 답변드려요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "자주 묻는 질문",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            MOCK_FAQ.forEach { (q, a) ->
                AigoCard {
                    Text("Q. $q", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(Dimens.Space8))
                    Text(a, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
