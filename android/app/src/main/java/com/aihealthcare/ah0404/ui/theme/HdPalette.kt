package com.aihealthcare.ah0404.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 디자인 고도화 시안 팔레트(핸드오프 design_tokens 공통).
 *
 *  화면마다 같은 색을 다시 선언하지 않도록 한곳에 모은다. 카드 규칙은 사용자 확정:
 *  **카드/입력필드/알약의 fill 은 화면 바탕색과 동일([HdBg]) + 테두리**로 통일한다
 *  (흰색은 붕 뜨고 진한 베이지는 무겁다). 선택 안 한 알약에도 테두리를 넣고,
 *  선택된 것만 초록 틴트([HdGreenTint]) + 초록 테두리로 구분한다.
 */
internal val HdBg = Color(0xFFF7F6F0)
internal val HdGreen = Color(0xFF1F5D3A)
internal val HdGreenDark = Color(0xFF164A2D)
internal val HdGreenTint = Color(0xFFE7F3EA)
internal val HdInk = Color(0xFF202321)
internal val HdMuted = Color(0xFF6B726D)

/** 카드 fill — 바탕색과 동일(테두리로만 구분). */
internal val HdCardFill = HdBg

/** 카드 테두리 — 박스처럼 도드라지지 않게 은은하게. */
internal val HdCardBorder = Color(0xFFE7E5DB)

/** 선택 안 한 알약·라디오 테두리(투명 배경이어도 테두리는 필수). */
internal val HdUnselBorder = Color(0xFFD3D8CE)