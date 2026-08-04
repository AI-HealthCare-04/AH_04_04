package com.aihealthcare.ah0404.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 치수 상수 — 디자인 시스템 문서 §4(간격 그리드), §8(버튼/터치).
 *
 *  버튼 높이·터치 영역은 잠정값(#6)이라 실기기 어르신 터치 후 상향될 수 있다.
 *  ⚠️ 하드코딩 금지: 화면/부품에서 치수는 반드시 이 상수를 참조해, 나중에 여기 한 줄만
 *     바꿔도 전체에 반영되게 한다.
 */
object Dimens {
    // 간격 그리드(4의 배수) — §4
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp

    // 레이아웃 여백 — §4
    val ScreenPadding = 20.dp   // 화면 좌우 여백
    val CardPadding = 16.dp     // 카드 내부 여백
    val ElementGap = 12.dp      // 요소 간 기본 간격
    val ElementGapLarge = 16.dp

    // 버튼/터치 — §8 (잠정 #6, 실기기 후 확정)
    val ButtonHeight = 56.dp
    val MinTouchTarget = 48.dp

    // 테두리 — §5, §9-2
    val HairlineBorder = 1.dp
}
