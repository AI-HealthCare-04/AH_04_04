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

// ── 기록 화면 차트 색(#387 리디자인) ──────────────────────────────────────────
//  기록 화면 리디자인에서 **새로 추가한 것은 차트 색뿐**이다(핸드오프 §3 확정).
//  카드 배경·모서리·버튼·배지·비활성색은 전부 기존 토큰(colorScheme·Dimens·BandBadge)을 그대로 쓴다.
//  차트는 데이터 표현이라 M3 의미색 슬롯에 대응 항목이 없어 여기에 둔다.

/** 막대그래프 채움(최근 7일 걷기). */
internal val ChartBarGreen = Color(0xFF6AAE71)

/** 꺾은선 그래프 선·점(점수 변화). */
internal val ChartLineGreen = Color(0xFF377E53)

/** 또래 분포에서 '내 위치' 강조 — 초록 계열과 구분되어야 해서 파랑. 기존 팔레트에 파란 토큰이 없다. */
internal val ChartPeerBlue = Color(0xFF1E6FD9)

/** 위험 구간 띠 3색(낮음·중간·높음). 순서 고정. */
internal val RiskBandLow = Color(0xFFA8DCC0)
internal val RiskBandMid = Color(0xFFF7DFA0)
internal val RiskBandHigh = Color(0xFFF2B3A8)

/** 미션별 완료 현황 타일 — 유형별 (아이콘 원 배경, 아이콘) 쌍. */
internal val MissionWalkBg = Color(0xFFEAF7EA)
internal val MissionWalkFg = Color(0xFF4E9E5B)
internal val MissionExerciseBg = Color(0xFFE4EEF7)
internal val MissionExerciseFg = Color(0xFF5B90C8)
internal val MissionMealBg = Color(0xFFFCE9E1)
internal val MissionMealFg = Color(0xFFE4835A)
internal val MissionGameBg = Color(0xFFE9F8E7)
internal val MissionGameFg = Color(0xFF6BAE71)