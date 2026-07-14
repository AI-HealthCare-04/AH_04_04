package com.aihealthcare.ah0404.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Aigo 브랜드 팔레트 — 디자인 시스템 문서 §1 (Material Theme Builder seed #164528 + 시안 보정).
 *
 *  ⚠️ 라이트 모드 고정(§7). 화면 코드에서 색을 직접 쓰지 말고 반드시
 *     MaterialTheme.colorScheme 을 통해 참조한다(§0-1, §5).
 *
 *  문서 표에 명시된 역할은 그대로, M3 완결을 위한 나머지 토큰(surfaceVariant/tertiary/inverse 등)은
 *  브랜드와 어울리는 값으로 보정했다.
 */

// ── 문서 §1 명시값 ──────────────────────────────────────────────
val AigoPrimary = Color(0xFF164528)             // 브랜드 녹색 (주요 버튼·강조)
val AigoOnPrimary = Color(0xFFFFFFFF)           // 녹색 위 글자
val AigoPrimaryContainer = Color(0xFF2F5D3E)    // 밝은 녹색 면
val AigoOnPrimaryContainer = Color(0xFFEAF2EC)  // 진한 컨테이너 위 밝은 글자
val AigoSecondary = Color(0xFF486550)           // 보조 녹색
val AigoOnSecondary = Color(0xFFFFFFFF)
val AigoSecondaryContainer = Color(0xFFCAEBD0)  // 연녹색 배경(태그·연한 버튼)
val AigoOnSecondaryContainer = Color(0xFF06210F)
val AigoBackground = Color(0xFFFAFBFA)          // 앱 전체 배경
val AigoOnBackground = Color(0xFF1A1C1A)        // 본문 글자(순검정 대신 톤다운)
val AigoSurface = Color(0xFFF9FAF5)             // 카드·시트 배경
val AigoOnSurface = Color(0xFF1A1C1A)
val AigoOutline = Color(0xFF717971)             // 테두리·구분선
val AigoOutlineVariant = Color(0xFFC1C9BF)      // 얇은 테두리(hairline)
val AigoError = Color(0xFFBA1A1A)
val AigoOnError = Color(0xFFFFFFFF)
val AigoErrorContainer = Color(0xFFFFDAD6)
val AigoOnErrorContainer = Color(0xFF93000A)

// ── M3 완결용 보정값(문서 미명시, 브랜드 톤 유지) ────────────────
val AigoTertiary = Color(0xFF3D6373)            // 은은한 청록 포인트
val AigoOnTertiary = Color(0xFFFFFFFF)
val AigoTertiaryContainer = Color(0xFFC1E9FB)
val AigoOnTertiaryContainer = Color(0xFF001F29)
val AigoSurfaceVariant = Color(0xFFDDE5DB)      // 연한 중립 녹색 면
val AigoOnSurfaceVariant = Color(0xFF414942)
// 주의(경고) 배지 — M3 표준 슬롯에 없는 의미색. 화면에서 raw hex 대신 이 토큰 참조(§0-1, §11).
val AigoWarningContainer = Color(0xFFFFF3CD)    // 연한 앰버 배경(안전 확인 등)
val AigoOnWarningContainer = Color(0xFF856404)  // 앰버 배경 위 글자

val AigoScrim = Color(0xFF000000)
val AigoInverseSurface = Color(0xFF2E312D)
val AigoInverseOnSurface = Color(0xFFEFF1EB)
val AigoInversePrimary = Color(0xFF8DD8A0)      // 어두운 면 위 밝은 브랜드 녹색
