package xyz.a202132.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import xyz.a202132.app.data.model.SkinThemeMode
import xyz.a202132.app.data.model.SkinSettings

private val DefaultLightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = SecondaryVariant,
    onSecondaryContainer = Color.White,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = CardLight,
    onSurfaceVariant = TextSecondaryLight,
    error = ErrorRed,
    onError = Color.White,
    outline = DividerLight
)

private fun skinColorScheme(baseColor: Color): ColorScheme {
    if (baseColor.toArgb() == SkinSettings.DEFAULT_BASE_COLOR_ARGB.toInt()) {
        return DefaultLightColorScheme
    }

    val primary = baseColor.copy(alpha = 1f)
    val secondary = scaleColor(primary, 1.12f)
    val secondaryVariant = scaleColor(primary, 0.82f)
    val onPrimary = contentColorFor(primary)
    val onSecondary = contentColorFor(secondary)

    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = tintColor(primary),
        onPrimaryContainer = scaleColor(primary, 0.52f),
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = tintColor(secondary),
        onSecondaryContainer = scaleColor(secondaryVariant, 0.52f),
        background = BackgroundLight,
        onBackground = TextPrimaryLight,
        surface = SurfaceLight,
        onSurface = TextPrimaryLight,
        surfaceVariant = CardLight,
        onSurfaceVariant = TextSecondaryLight,
        error = ErrorRed,
        onError = Color.White,
        outline = DividerLight
    )
}

private fun skinDarkColorScheme(baseColor: Color): ColorScheme {
    val primary = scaleColor(baseColor, 1.35f)
    val secondary = scaleColor(primary, 1.08f)
    val onPrimary = contentColorFor(primary)
    val onSecondary = contentColorFor(secondary)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = scaleColor(primary, 0.72f),
        onPrimaryContainer = Color.White,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = scaleColor(secondary, 0.62f),
        onSecondaryContainer = Color.White,
        background = BackgroundDark,
        onBackground = TextPrimaryDark,
        surface = SurfaceDark,
        onSurface = TextPrimaryDark,
        surfaceVariant = CardDark,
        onSurfaceVariant = TextSecondaryDark,
        error = ErrorRed,
        onError = Color.White,
        outline = DividerDark
    )
}

private fun scaleColor(color: Color, factor: Float): Color {
    return Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = color.alpha
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
fun FireflyTheme(
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
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
