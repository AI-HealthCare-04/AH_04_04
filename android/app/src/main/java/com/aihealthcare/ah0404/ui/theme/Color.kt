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
// tertiary = 성취/보상 강조 골드. 초록 단색 팔레트에 따뜻한 포인트를 더해
// "성공 경험을 주는" 순간(포인트 적립·미션 완료·배지)에 쓴다(안전배지 앰버와 톤 일관).
val AigoTertiary = Color(0xFF8A6A00)            // 리치 골드(성취 강조 텍스트/아이콘)
// 트랙 위에 얹는 골드 막대용 한 단계 어두운 골드(#406). AigoTertiary 는 텍스트로는 충분하지만
//   (배경 AigoSurface 대비 4.83:1) **트랙(AigoOutlineVariant) 대비가 2.99:1** 이라 비텍스트 3:1 에
//   미달했다 — 하필 '개선 여지' 쪽이라 가장 봐야 할 막대가 가장 안 보이는 조합이었다.
//   트랙을 진하게(outline) 하는 방향은 역효과다: 골드 1.13:1·진녹 2.44:1 로 **둘 다** 더 나빠진다.
//   이 값은 막대 vs 트랙 3.68:1, 텍스트 vs 배경 5.96:1 로 두 기준을 모두 넘긴다.
val AigoTertiaryDark = Color(0xFF7A5C00)        // 기여도 막대·방향 문구용 진한 골드
val AigoOnTertiary = Color(0xFFFFFFFF)
val AigoTertiaryContainer = Color(0xFFFFE49B)   // 밝은 골드 면(보상 배지 배경)
val AigoOnTertiaryContainer = Color(0xFF2B2000)
val AigoSurfaceVariant = Color(0xFFDDE5DB)      // 연한 중립 녹색 면
val AigoOnSurfaceVariant = Color(0xFF414942)
// surfaceContainer 계열(M3 신규) — NavigationBar·시트 등이 사용. 미정의 시 기본 보라틴트가 새어
// 나오므로 초록틴트 중립색으로 명시(스크린샷에서 발견한 보라 네비바 수정).
val AigoSurfaceContainerLowest = Color(0xFFFFFFFF)
val AigoSurfaceContainerLow = Color(0xFFF3F5F0)
val AigoSurfaceContainer = Color(0xFFEDF0EA)
val AigoSurfaceContainerHigh = Color(0xFFE7EAE4)
val AigoSurfaceContainerHighest = Color(0xFFE1E5DE)
val AigoSurfaceBright = Color(0xFFF9FAF5)
val AigoSurfaceDim = Color(0xFFDADED7)
// 주의(경고) 배지 — M3 표준 슬롯에 없는 의미색. 화면에서 raw hex 대신 이 토큰 참조(§0-1, §11).
val AigoWarningContainer = Color(0xFFFFF3CD)    // 연한 앰버 배경(안전 확인 등)
val AigoOnWarningContainer = Color(0xFF856404)  // 앰버 배경 위 글자

val AigoScrim = Color(0xFF000000)
val AigoInverseSurface = Color(0xFF2E312D)
val AigoInverseOnSurface = Color(0xFFEFF1EB)
val AigoInversePrimary = Color(0xFF8DD8A0)      // 어두운 면 위 밝은 브랜드 녹색
