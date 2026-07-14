package com.aihealthcare.ah0404.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.MyApplicationTheme

/**
 * 디자인 시스템 ② 공통 부품 참조 갤러리(§5).
 *
 *  Android Studio Preview 로 앱 실행 없이 모든 부품을 한눈에 확인·복붙하기 위한 문서용 화면.
 *  실제 화면에 넣는 코드가 아니라 "부품 카탈로그"다.
 */
@Composable
private fun ComponentGallery() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
    ) {
        Text("타이포그래피", style = MaterialTheme.typography.titleLarge)
        Text("headlineLarge 30", style = MaterialTheme.typography.headlineLarge)
        Text("titleLarge 24", style = MaterialTheme.typography.titleLarge)
        Text("titleMedium 20", style = MaterialTheme.typography.titleMedium)
        Text("bodyLarge 18 — 본문 기본", style = MaterialTheme.typography.bodyLarge)
        Text("bodyMedium 16 — 보조 본문", style = MaterialTheme.typography.bodyMedium)

        Text("버튼", style = MaterialTheme.typography.titleLarge)
        AigoPrimaryButton(text = "주요 버튼 (시작하기)", onClick = {})
        AigoSecondaryButton(text = "보조 버튼 (취소)", onClick = {})
        AigoTonalButton(text = "연녹색 버튼 (건너뛰기)", onClick = {})
        AigoPrimaryButton(text = "비활성 버튼", onClick = {}, enabled = false)

        Text("입력칸 / 체크박스", style = MaterialTheme.typography.titleLarge)
        var text by remember { mutableStateOf("") }
        AigoTextField(value = text, onValueChange = { text = it }, label = "키 (cm)")
        var checked by remember { mutableStateOf(false) }
        AigoCheckboxRow(checked = checked, onCheckedChange = { checked = it }, label = "이용약관에 동의합니다")

        Text("카드", style = MaterialTheme.typography.titleLarge)
        AigoCard {
            Text("기본 카드", style = MaterialTheme.typography.titleMedium)
            Text("그림자 없이 hairline 테두리 · 모서리 16dp", style = MaterialTheme.typography.bodyMedium)
        }
        AigoHeroCard(
            modifier = Modifier.height(160.dp),
            overlay = {
                Text(
                    "오늘의 미션 — 3D 펫과 산책",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) {
            // 실제로는 영상/3D 뷰가 들어갈 자리(여기선 브랜드색 플레이스홀더).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
}

@Preview(name = "Aigo 부품 갤러리", showBackground = true, heightDp = 1400)
@Composable
private fun ComponentGalleryPreview() {
    MyApplicationTheme {
        ComponentGallery()
    }
}
