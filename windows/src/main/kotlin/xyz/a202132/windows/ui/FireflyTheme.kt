package xyz.a202132.windows.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF00BFA5)
val PrimaryVariant = Color(0xFF00897B)
val Secondary = Color(0xFF26C6DA)
val BackgroundLight = Color(0xFFFFFFFF)
val CardLight = Color(0xFFF8FAFC)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)
val DividerLight = Color(0xFFE2E8F0)
val ErrorRed = Color(0xFFEF4444)

private val FireflyLightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = BackgroundLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = CardLight,
    onSurfaceVariant = TextSecondaryLight,
    error = ErrorRed,
    onError = Color.White,
    outline = DividerLight
)

@Composable
fun FireflyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FireflyLightColors,
        content = content
    )
}
