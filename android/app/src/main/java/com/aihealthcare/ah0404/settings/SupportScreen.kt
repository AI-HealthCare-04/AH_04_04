package com.aihealthcare.ah0404.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aihealthcare.ah0404.network.FaqItem
import com.aihealthcare.ah0404.ui.theme.HdBg
import com.aihealthcare.ah0404.ui.theme.HdCardBorder
import com.aihealthcare.ah0404.ui.theme.HdCardFill
import com.aihealthcare.ah0404.ui.theme.HdGreen
import com.aihealthcare.ah0404.ui.theme.HdGreenTint
import com.aihealthcare.ah0404.ui.theme.HdInk
import com.aihealthcare.ah0404.ui.theme.HdMuted

/**
 * 문의 접수를 운영하지 않는다는 안내(#387 리뷰 P1).
 *
 * 심사·시연용 앱이라 받을 메일함이 없다. 주소를 적어두면 "죽은 링크"가 "보내도 아무도 안 읽는 주소"로
 * 바뀔 뿐이라, 주소 대신 사실을 적고 화면 안에서 할 수 있는 일(FAQ)로 보낸다.
 */
internal const val SUPPORT_DEMO_NOTICE =
    "이 앱은 심사·시연용이라 문의 접수는 운영하지 않아요.\n" +
        "아래 자주 묻는 질문에서 궁금한 점을 확인해 주세요."

/**
 * 고객센터(_16) — 문의 안내 + FAQ 진입.
 *
 *  ★ 디자인 고도화에서 **FAQ 와 고객센터를 별도 화면으로 분리**했다(시안 확정). 질문 목록은 [FaqScreen] 이
 *  담당하고, 여기서는 안내와 진입 행만 둔다 — 두 목적이 한 화면에 섞여 스크롤이 길던 문제 해소.
 *
 *  ⚠️ 이전에는 서버 `GET /support` 의 이메일을 크게 띄우고 메일 앱으로 연결했지만, 그 값이 배포에서도
 *  placeholder 라 아무도 받지 못했다(#387 리뷰 P1). 문의 창구를 두지 않기로 하고 표시를 걷어냈다.
 *  API 자체는 계약 변경 파장을 피하려 그대로 두었고, 이 화면이 값을 쓰지 않을 뿐이다.
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onOpenFaq: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SupportViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HdBg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "고객센터", onBack = onBack)

        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 문의 접수를 운영하지 않는다(#387 리뷰 P1). 이 앱은 심사·시연용이라 받을 메일함이 없는데,
            //   여기서는 서버 SUPPORT_EMAIL 을 20sp 굵게 띄우고 "확인 후 답변드려요"까지 약속하고 있었다.
            //   실제로는 그 값이 placeholder(support@aigo.example.com)라 아무도 받지 못한다 — 주소를 지우고
            //   받지 못한다는 사실을 그대로 적는다. 로그인 전 안내(OnboardingScreen)와 같은 방침이다.
            HdCard {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "문의 안내",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HdInk,
                        modifier = Modifier.align(Alignment.Start),
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier.size(56.dp).background(HdGreenTint, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MailOutline, contentDescription = null, tint = HdGreen, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        SUPPORT_DEMO_NOTICE,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = HdMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            HdCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenFaq)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).background(HdGreenTint, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = null,
                            tint = HdGreen,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("자주 묻는 질문 보기", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = HdInk, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = HdMuted)
                }
            }
        }
    }
}

/**
 * 자주 묻는 질문(#74) — GET /support/faqs.
 *
 *  ★ 시안 확정: **질문-답변 아코디언**. 기본은 질문만 보이고 누른 카드만 펼쳐 답변을 보여준다 —
 *  답변을 모두 펼쳐 두면 스크롤이 길어져 시니어가 원하는 질문을 찾기 어렵다.
 *  문구는 서버 카탈로그에서 오므로 앱 배포 없이 갱신된다.
 */
@Composable
fun FaqScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SupportViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.load() }
    // 펼친 질문 1개만 유지(아코디언). 회전·복귀에도 유지되게 saveable.
    var expandedId by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HdBg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = "자주 묻는 질문", onBack = onBack)

        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                vm.faqsError -> FaqError(onRetry = vm::load)
                vm.loading && vm.faqs.isEmpty() -> FaqLoading()
                vm.faqs.isEmpty() -> Text(
                    "표시할 질문이 아직 없어요.",
                    fontSize = 17.sp,
                    color = HdMuted,
                )
                else -> vm.faqs.forEach { item ->
                    FaqAccordionCard(
                        item = item,
                        expanded = expandedId == item.faqId,
                        // 이미 펼친 걸 다시 누르면 접는다(토글).
                        onToggle = { expandedId = if (expandedId == item.faqId) null else item.faqId },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 질문 한 줄(항상) + 펼쳤을 때만 답변. 펼친 카드는 초록 틴트로 '지금 보는 질문'을 분명히 한다(시안). */
@Composable
private fun FaqAccordionCard(item: FaqItem, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = if (expanded) HdGreenTint else HdCardFill,
        border = BorderStroke(1.dp, if (expanded) HdGreen.copy(alpha = 0.35f) else HdCardBorder),
    ) {
        Column(
            Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.question,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = HdInk,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "답변 접기" else "답변 보기",
                    tint = HdInk,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(item.answer, fontSize = 16.sp, lineHeight = 26.sp, color = HdInk.copy(alpha = 0.85f))
            }
        }
    }
}

/** 시안 카드 규칙 — fill 은 바탕색과 동일하고 테두리로만 구분한다(흰 카드 금지, 사용자 확정). */
@Composable
private fun HdCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = HdCardFill,
        border = BorderStroke(1.dp, HdCardBorder),
        content = content,
    )
}

@Composable
private fun FaqLoading() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HdGreen)
    }
}

@Composable
private fun FaqError(onRetry: () -> Unit) {
    HdCard {
        Column(Modifier.padding(16.dp)) {
            Text("질문을 불러오지 못했어요. 네트워크를 확인해 주세요.", fontSize = 17.sp, lineHeight = 24.sp, color = HdInk)
            TextButton(onClick = onRetry) { Text("다시 시도", color = HdGreen, fontWeight = FontWeight.Bold) }
        }
    }
}
