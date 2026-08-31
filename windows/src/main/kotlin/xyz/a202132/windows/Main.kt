package xyz.a202132.windows

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import xyz.a202132.windows.core.DesktopVpnController
import xyz.a202132.windows.ui.FireflyDesktopApp
import xyz.a202132.windows.ui.FireflyTheme
import java.awt.Dimension
import java.awt.SystemTray

fun main() = application {
    val controller = remember { DesktopVpnController() }
    val windowState = rememberWindowState(width = 430.dp, height = 780.dp)
    val trayAvailable = remember { SystemTray.isSupported() }
    val trayState = rememberTrayState()
    var windowVisible by remember { mutableStateOf(true) }
    val awtWindow = remember { mutableStateOf<java.awt.Window?>(null) }

    fun showWindow() {
        windowVisible = true
        awtWindow.value?.let { window ->
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
    }

    DisposableEffect(controller) {
        onDispose(controller::close)
    }

    if (trayAvailable) {
        Tray(
            icon = painterResource("firefly.png"),
            state = trayState,
            tooltip = "Firefly",
            onAction = ::showWindow
        ) {
            Item("显示主界面", onClick = ::showWindow)
            Separator()
            Item("退出 Firefly", onClick = ::exitApplication)
        }
    }

    Window(
        visible = windowVisible,
        title = "Firefly",
        icon = painterResource("firefly.png"),
        state = windowState,
        // Windows 端保持 Android 风格的固定比例，避免拉伸后布局无法恢复。
        resizable = false,
        onCloseRequest = {
            if (trayAvailable) {
                windowVisible = false
            } else {
                exitApplication()
            }
        }
    ) {
        awtWindow.value = window
        LaunchedEffect(Unit) {
            // 使用窗口实际尺寸锁定最小和最大值，避免高 DPI 缩放下的尺寸偏差。
            val fixedSize = Dimension(window.width, window.height)
            window.minimumSize = fixedSize
            window.maximumSize = fixedSize
        }

        FireflyTheme {
            FireflyDesktopApp(controller)
        }
    }
}
