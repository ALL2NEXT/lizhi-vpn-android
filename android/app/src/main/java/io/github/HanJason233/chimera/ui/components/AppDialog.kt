package io.github.HanJason233.chimera.ui.components

import androidx.compose.material.AlertDialog as Material2AlertDialog
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme as Material2Theme
import androidx.compose.material.Surface as Material2Surface
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material3.AlertDialog as Material3AlertDialog
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Surface as Material3Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.HanJason233.chimera.data.model.DialogMaterialVersion
import io.github.HanJason233.chimera.ui.theme.LocalDialogMaterialVersion

@Composable
private fun material2Colors(): Colors {
    val scheme = Material3Theme.colorScheme
    // 弹窗仍复用应用的 M3 配色，只映射到 M2 主题以保持内容颜色一致。
    return remember(scheme) {
        val colors = arrayOf(
            scheme.primary,
            scheme.primary,
            scheme.secondary,
            scheme.background,
            scheme.surface,
            scheme.error,
            scheme.onPrimary,
            scheme.onSecondary,
            scheme.onBackground,
            scheme.onSurface,
            scheme.onError
        )
        if (scheme.background.luminance() > 0.5f) {
            lightColors(
                primary = colors[0], primaryVariant = colors[1], secondary = colors[2],
                background = colors[3], surface = colors[4], error = colors[5],
                onPrimary = colors[6], onSecondary = colors[7], onBackground = colors[8],
                onSurface = colors[9], onError = colors[10]
            )
        } else {
            darkColors(
                primary = colors[0], primaryVariant = colors[1], secondary = colors[2],
                background = colors[3], surface = colors[4], error = colors[5],
                onPrimary = colors[6], onSecondary = colors[7], onBackground = colors[8],
                onSurface = colors[9], onError = colors[10]
            )
        }
    }
}

@Composable
private fun Material2DialogTheme(content: @Composable () -> Unit) {
    Material2Theme(colors = material2Colors(), content = content)
}

/** 根据主题设置选择 M2 或 M3 的 Compose AlertDialog。 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    properties: DialogProperties = DialogProperties()
) {
    val m3Colors = Material3Theme.colorScheme
    if (LocalDialogMaterialVersion.current == DialogMaterialVersion.MATERIAL_2) {
        Material2DialogTheme {
            Material2AlertDialog(
                onDismissRequest = onDismissRequest,
                confirmButton = confirmButton,
                modifier = modifier,
                dismissButton = dismissButton,
                title = title,
                text = text,
                shape = shape ?: Material2Theme.shapes.medium,
                backgroundColor = m3Colors.surface,
                contentColor = m3Colors.onSurface,
                properties = properties
            )
        }
    } else {
        Material3AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton ?: {},
            title = title,
            text = text,
            shape = shape ?: Material3Theme.shapes.extraLarge,
            properties = properties
        )
    }
}

/** 统一自定义弹窗容器，并按主题设置切换 M2/M3 Surface 外观。 */
@Composable
fun AppDialogSurface(
    modifier: Modifier = Modifier,
    color: Color = Material3Theme.colorScheme.surface,
    contentColor: Color = Material3Theme.colorScheme.onSurface,
    m3Shape: Shape = Material3Theme.shapes.extraLarge,
    m3TonalElevation: Dp = 0.dp,
    m2Shape: Shape? = null,
    m2Elevation: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    if (LocalDialogMaterialVersion.current == DialogMaterialVersion.MATERIAL_2) {
        Material2DialogTheme {
            Material2Surface(
                modifier = modifier,
                shape = m2Shape ?: Material2Theme.shapes.medium,
                color = color,
                contentColor = contentColor,
                elevation = m2Elevation,
                content = content
            )
        }
    } else {
        Material3Surface(
            modifier = modifier,
            shape = m3Shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = m3TonalElevation,
            content = content
        )
    }
}
