package com.aihealthcare.ah0404.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aihealthcare.ah0404.ui.theme.Dimens
import com.aihealthcare.ah0404.ui.theme.HeroCardShape

/**
 * 공통 카드 — 디자인 시스템 문서 §5, §9-2/§9-5.
 *
 *  깊이(Depth)는 딱 두 종류로만 통일:
 *   - 기본 카드  = 그림자 없음(elevation 0) + hairline 테두리(플랫).
 *   - 히어로 카드 = 소프트 섀도우 1단계 + 큰 라운드(24dp), 콘텐츠(영상/3D/펫)가 꽉 차게.
 */

/**
 * 기본 카드: Surface 배경, 그림자 없이 hairline 테두리, 모서리 16dp, 내부 16dp.
 *
 * @param title 있으면 카드 맨 위에 제목을 렌더한다(핸드오프 §4-1). 카드마다 제목 Text 를 따로
 *   쓰면 굵기·간격이 화면마다 어긋나므로, 제목이 있는 카드는 이 파라미터를 쓴다. null 이면 종전과 동일.
 * @param contentSpacing 내부 세로 간격. **기본 0dp 는 기존 호출부 호환용**이다 — 기존 카드들은
 *   내부에서 Spacer 로 간격을 직접 넣고 있어, 여기서 간격을 주면 전 화면에 이중으로 벌어진다.
 *   새로 만드는 카드는 Spacer 대신 이 값(Space12)을 쓴다.
 */
@Composable
fun AigoCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(Dimens.HairlineBorder, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

/**
 * 히어로 카드: 영상/3D 펫/오늘의 미션 등 "주인공" 콘텐츠용.
 * 모서리 24dp + 소프트 섀도우 1단계. content 는 카드를 꽉 채우고,
 * overlay 슬롯의 텍스트는 하단 그라데이션 위에 얹어 가독성을 확보한다(§9-5).
 *
 * @param overlay 하단 그라데이션 위에 배치할 콘텐츠(제목/설명 등). 비우면 스크림도 생략.
 */
@Composable
fun AigoHeroCard(
    modifier: Modifier = Modifier,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = HeroCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(Modifier.clip(HeroCardShape)) {
            content()
            if (overlay != null) {
                // 하단 은은한 그라데이션 스크림(텍스트 가독성).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Dimens.CardPadding),
                    content = overlay,
                )
            }
        }
    }
}
