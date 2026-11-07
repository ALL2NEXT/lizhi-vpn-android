package io.github.HanJason233.chimera.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.HanJason233.chimera.data.model.SkinSettings
import io.github.HanJason233.chimera.R

/**
 * Compose equivalents of the app-specific roles Gmail layers over Android's Monet palette.
 * dynamicColorScheme supplies the wallpaper-derived colors; these roles keep page and control
 * surfaces on the same palette while giving selected controls a softer container overlay.
 */
object MonetThemeOverlay {
    val screenBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.background

    val dialogSurface: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surface

    val controlSurface: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceVariant

    val homePanelSurface: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color.White

    val selectedControl: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primaryContainer

    val onSelectedControl: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onPrimaryContainer

    val outline: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outlineVariant
}

private val MonochromeLightColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Color(0xFF000000),
    inversePrimary = Color(0xFFC0C0C0),
    secondary = Color(0xFFF97910),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE8D6),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF009966),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA0F2D0),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFE5E5E5),
    onSurfaceVariant = Color(0xFF494949),
    inverseSurface = Color(0xFF313131),
    inverseOnSurface = Color(0xFFF4F4F4),
    outline = Color(0xFF797979),
    outlineVariant = Color(0xFFC7C7C7),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF000000)
)

private val MonochromeDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC0C0C0),
    onPrimary = Color(0xFF303030),
    primaryContainer = Color(0xFF474747),
    onPrimaryContainer = Color(0xFFE0E0E0),
    inversePrimary = Color(0xFF000000),
    secondary = Color(0xFFF97910),
    onSecondary = Color(0xFF4E2600),
    secondaryContainer = Color(0xFF6F3800),
    onSecondaryContainer = Color(0xFFFFE8D6),
    tertiary = Color(0xFF83D6B5),
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFFA0F2D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C1C1C),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF494949),
    onSurfaceVariant = Color(0xFFCACACA),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1C1C1C),
    outline = Color(0xFF939393),
    outlineVariant = Color(0xFF494949),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFC0C0C0)
)

@Composable
fun ChimeraTheme(
    skinSettings: SkinSettings = SkinSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val useMonetColors = skinSettings.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val baseColorScheme = when {
        useMonetColors && isDarkTheme -> dynamicDarkColorScheme(context)
        useMonetColors -> dynamicLightColorScheme(context)
        isDarkTheme -> MonochromeDarkColorScheme
        else -> MonochromeLightColorScheme
    }
    // Gmail's Material 3 canvas uses surface_container, whose Android 12 Monet
    // resource is system_neutral2_600 with the requested L* applied. The older
    // Compose BOM in this project predates surfaceContainer roles, so mirror the
    // platform color resource directly instead of substituting background.
    val colorScheme = if (useMonetColors) {
        val surfaceContainer = if (isDarkTheme) {
            context.getColor(R.color.monet_surface_container_dark)
        } else {
            context.getColor(R.color.monet_surface_container)
        }
        baseColorScheme.copy(background = Color(surfaceContainer))
    } else {
        baseColorScheme
    }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDarkTheme
            insetsController.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
