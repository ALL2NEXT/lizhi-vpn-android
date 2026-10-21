package io.github.HanJason233.chimera.ui.components

import androidx.compose.material3.AlertDialog as Material3AlertDialog
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Surface as Material3Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** 标准提示弹窗统一使用 Material 3。 */
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

/** 自定义弹窗容器使用 Material 3 Surface，并保留调用方传入的外观配置。 */
@Composable
fun AppDialogSurface(
    modifier: Modifier = Modifier,
    color: Color = Material3Theme.colorScheme.surface,
    contentColor: Color = Material3Theme.colorScheme.onSurface,
    m3Shape: Shape = Material3Theme.shapes.extraLarge,
    m3TonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Material3Surface(
        modifier = modifier,
        shape = m3Shape,
        color = color,
        contentColor = contentColor,
        tonalElevation = m3TonalElevation,
        content = content
    )
}
