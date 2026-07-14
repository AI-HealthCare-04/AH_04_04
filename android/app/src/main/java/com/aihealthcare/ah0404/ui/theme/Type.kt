package com.aihealthcare.ah0404.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aihealthcare.ah0404.R

/**
 * 타이포그래피 — 디자인 시스템 문서 §2, §3.
 *
 *  폰트: Noto Sans KR(OFL, 상업/임베드 무료). 용량 최소화를 위해 가변폰트 한 파일을 번들하고
 *  400/500/700 세 굵기를 FontVariation 으로 뽑아 쓴다(§3의 3종 굵기).
 *  ⚠️ 가변 굵기는 API 26+ 에서 적용, minSdk 24~25 에서는 기본 굵기로 우아하게 폴백된다.
 *
 *  크기는 sp 단위 → 사용자의 시스템 글꼴 확대(fontScale)를 따른다(고령 사용자 배려, §2/§4).
 *  줄간격은 글자 크기의 약 1.4~1.5배로 넉넉히.
 */
@OptIn(ExperimentalTextApi::class)
private val NotoSansKR = FontFamily(
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/** 앱 기본 글꼴(Noto Sans KR). Theme 에서 bare Text 의 기본 글꼴로 provide 한다. */
val AigoFontFamily: FontFamily = NotoSansKR

// 문서 §2에서 크기를 지정한 7개 스타일은 아래에서 완전 재정의하고,
// 나머지 8개(display*/headlineMedium/headlineSmall/titleSmall/bodySmall/labelSmall)는
// M3 기본 메트릭을 유지하되 글꼴만 Noto Sans KR 로 교체한다.
// → 화면이 어떤 M3 텍스트 스타일을 쓰더라도 항상 한글 브랜드 폰트가 적용된다.
private val Default = Typography()

private fun TextStyle.withNoto() = copy(fontFamily = NotoSansKR)

val Typography = Typography(
    displayLarge = Default.displayLarge.withNoto(),
    displayMedium = Default.displayMedium.withNoto(),
    displaySmall = Default.displaySmall.withNoto(),
    // 큰 화면 타이틀
    headlineLarge = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 40.sp,
    ),
    headlineMedium = Default.headlineMedium.withNoto(),
    headlineSmall = Default.headlineSmall.withNoto(),
    // 화면 제목
    titleLarge = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    // 카드 제목·소제목
    titleMedium = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 28.sp,
    ),
    titleSmall = Default.titleSmall.withNoto(),
    // 본문(기본)
    bodyLarge = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 27.sp,
    ),
    // 보조 본문
    bodyMedium = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodySmall = Default.bodySmall.withNoto(),
    // 버튼 글자
    labelLarge = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    // 작은 라벨·탭
    labelMedium = TextStyle(
        fontFamily = NotoSansKR, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelSmall = Default.labelSmall.withNoto(),
)
