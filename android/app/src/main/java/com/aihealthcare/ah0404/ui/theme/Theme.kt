package com.aihealthcare.ah0404.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.aihealthcare.ah0404.settings.AppSettings

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
    // 설정(_15)에서 고른 글자 크기를 전역 적용(묶음 C-2, 방식 B): LocalDensity 의 fontScale 에 곱하면
    //   모든 sp 텍스트가 한 번에 확대/축소된다(OS 글자크기 설정과도 곱연산). dp 레이아웃은 영향 없음.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * AppSettings.fontScale),
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
}
