package com.aihealthcare.ah0404.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 모서리 둥글기 — 디자인 시스템 문서 §4.
 *
 *  extraSmall 칩/태그, small 입력칸/작은버튼, medium 리스트, large 카드(기본),
 *  extraLarge 바텀시트/큰 다이얼로그/영상·펫 카드.
 */
val AigoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** 주요/보조 버튼용 알약형(50%) 모양. Shapes 토큰과 별개로 버튼에만 쓴다(§5). */
val PillShape = RoundedCornerShape(percent = 50)

/** 히어로(영상/펫/미션) 카드용 큰 라운드(§5, §9-5). */
val HeroCardShape = RoundedCornerShape(24.dp)

/** 팝업/다이얼로그용 라운드(§5). */
val DialogShape = RoundedCornerShape(28.dp)
