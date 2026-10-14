package io.github.HanJason233.chimera.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.HanJason233.chimera.data.model.SkinThemeMode
import io.github.HanJason233.chimera.data.model.SkinSettings

// 将普通自定义颜色作为种子，扩展为覆盖背景、表面、文字和描边的浅色配色。
private fun skinColorScheme(baseColor: Color): ColorScheme {
    val primary = baseColor.copy(alpha = 1f)
    val secondary = mixColors(primary, Secondary, 0.18f)
    val tertiary = mixColors(primary, Color(0xFF7D5260), 0.22f)
    val primaryContainer = tintColor(primary)
    val secondaryContainer = tintColor(secondary)
    val tertiaryContainer = tintColor(tertiary)
    val onPrimary = contentColorFor(primary)
    val onSecondary = contentColorFor(secondary)
    val onTertiary = contentColorFor(tertiary)
    val background = mixColors(BackgroundLight, primary, 0.035f)
    val surface = mixColors(SurfaceLight, primary, 0.055f)
    val surfaceVariant = mixColors(CardLight, primary, 0.12f)
    val onBackground = mixColors(TextPrimaryLight, primary, 0.025f)
    val onSurface = mixColors(TextPrimaryLight, primary, 0.025f)
    val onSurfaceVariant = mixColors(TextSecondaryLight, primary, 0.06f)
    val outline = mixColors(DividerLight, primary, 0.2f)
    val outlineVariant = mixColors(DividerLight, primary, 0.12f)

    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = contentColorFor(primaryContainer),
        inversePrimary = primary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = contentColorFor(secondaryContainer),
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = contentColorFor(tertiaryContainer),
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        inverseSurface = mixColors(SurfaceDark, primary, 0.08f),
        inverseOnSurface = mixColors(TextPrimaryDark, primary, 0.04f),
        error = ErrorRed,
        onError = Color.White,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceTint = primary
    )
}

// 深色模式沿用同一颜色种子，并混入深色基底以保持整体层级和文字对比。
private fun skinDarkColorScheme(baseColor: Color): ColorScheme {
    val seed = baseColor.copy(alpha = 1f)
    val primary = mixColors(seed, Color.White, 0.26f)
    val secondary = mixColors(primary, Secondary, 0.18f)
    val tertiary = mixColors(primary, Color(0xFF7D5260), 0.22f)
    val primaryContainer = mixColors(seed, Color.White, 0.38f)
    val secondaryContainer = mixColors(secondary, Color.White, 0.38f)
    val tertiaryContainer = mixColors(tertiary, Color.White, 0.38f)
    val onPrimary = contentColorFor(primary)
    val onSecondary = contentColorFor(secondary)
    val onTertiary = contentColorFor(tertiary)
    val background = mixColors(BackgroundDark, seed, 0.12f)
    val surface = mixColors(SurfaceDark, seed, 0.15f)
    val surfaceVariant = mixColors(CardDark, seed, 0.2f)
    val onBackground = mixColors(TextPrimaryDark, seed, 0.035f)
    val onSurface = mixColors(TextPrimaryDark, seed, 0.035f)
    val onSurfaceVariant = mixColors(TextSecondaryDark, seed, 0.07f)
    val outline = mixColors(DividerDark, seed, 0.3f)
    val outlineVariant = mixColors(DividerDark, seed, 0.2f)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = contentColorFor(primaryContainer),
        inversePrimary = primary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = contentColorFor(secondaryContainer),
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = contentColorFor(tertiaryContainer),
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        inverseSurface = mixColors(SurfaceLight, seed, 0.055f),
        inverseOnSurface = mixColors(TextPrimaryLight, seed, 0.025f),
        error = ErrorRed,
        onError = Color.White,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceTint = primary
    )
}

// 按权重混合基准色与主题种子，用于生成同一主题中的各个颜色角色。
private fun mixColors(color: Color, seed: Color, seedWeight: Float): Color {
    val colorWeight = 1f - seedWeight
    return Color(
        red = color.red * colorWeight + seed.red * seedWeight,
        green = color.green * colorWeight + seed.green * seedWeight,
        blue = color.blue * colorWeight + seed.blue * seedWeight,
        alpha = color.alpha * colorWeight + seed.alpha * seedWeight
    )
}

private fun tintColor(color: Color): Color {
    return Color(
        red = (color.red * 0.12f + 0.88f).coerceIn(0f, 1f),
        green = (color.green * 0.12f + 0.88f).coerceIn(0f, 1f),
        blue = (color.blue * 0.12f + 0.88f).coerceIn(0f, 1f),
        alpha = 1f
    )
}

private fun contentColorFor(color: Color): Color {
    return if (color.luminance() > 0.55f) TextPrimaryLight else Color.White
}

@Composable
fun ChimeraTheme(
    skinSettings: SkinSettings = SkinSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = when (skinSettings.themeMode) {
        SkinThemeMode.LIGHT -> false
        SkinThemeMode.DARK -> true
        SkinThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val useMonetColors = skinSettings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val fallbackColorScheme = remember(skinSettings.baseColorArgb, isDarkTheme) {
        val baseColor = Color(skinSettings.baseColorArgb)
        if (isDarkTheme) skinDarkColorScheme(baseColor) else skinColorScheme(baseColor)
    }
    val colorScheme = if (useMonetColors) {
        if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        fallbackColorScheme
    }
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    CompositionLocalProvider(
        LocalDialogMaterialVersion provides skinSettings.activeScheme.dialogMaterialVersion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
