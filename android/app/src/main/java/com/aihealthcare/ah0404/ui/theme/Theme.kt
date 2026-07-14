package com.aihealthcare.ah0404.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Aigo 앱 테마 — 디자인 시스템 문서 §0, §7, §10.
 *
 *  §0-2  dynamicColor(배경화면 색 따라가기) 제거 — 폰 배경에 따라 색이 바뀌지 않는다.
 *  §7    라이트 모드 고정 — 시스템 다크 무시(다크는 이번 MVP 범위 아님).
 *
 *  → 항상 브랜드 녹색 LightColorScheme 만 사용한다.
 */
private val LightColorScheme = lightColorScheme(
    primary = AigoPrimary,
    onPrimary = AigoOnPrimary,
    primaryContainer = AigoPrimaryContainer,
    onPrimaryContainer = AigoOnPrimaryContainer,
    secondary = AigoSecondary,
    onSecondary = AigoOnSecondary,
    secondaryContainer = AigoSecondaryContainer,
    onSecondaryContainer = AigoOnSecondaryContainer,
    tertiary = AigoTertiary,
    onTertiary = AigoOnTertiary,
    tertiaryContainer = AigoTertiaryContainer,
    onTertiaryContainer = AigoOnTertiaryContainer,
    background = AigoBackground,
    onBackground = AigoOnBackground,
    surface = AigoSurface,
    onSurface = AigoOnSurface,
    surfaceVariant = AigoSurfaceVariant,
    onSurfaceVariant = AigoOnSurfaceVariant,
    surfaceContainerLowest = AigoSurfaceContainerLowest,
    surfaceContainerLow = AigoSurfaceContainerLow,
    surfaceContainer = AigoSurfaceContainer,
    surfaceContainerHigh = AigoSurfaceContainerHigh,
    surfaceContainerHighest = AigoSurfaceContainerHighest,
    surfaceBright = AigoSurfaceBright,
    surfaceDim = AigoSurfaceDim,
    outline = AigoOutline,
    outlineVariant = AigoOutlineVariant,
    error = AigoError,
    onError = AigoOnError,
    errorContainer = AigoErrorContainer,
    onErrorContainer = AigoOnErrorContainer,
    scrim = AigoScrim,
    inverseSurface = AigoInverseSurface,
    inverseOnSurface = AigoInverseOnSurface,
    inversePrimary = AigoInversePrimary,
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = AigoShapes,
    ) {
        // 크기만 지정한 bare Text 도 기본 글꼴이 Noto Sans KR 가 되도록 provide.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AigoFontFamily),
            content = content,
        )
    }
}
