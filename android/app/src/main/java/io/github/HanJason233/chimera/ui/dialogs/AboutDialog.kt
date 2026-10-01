package io.github.HanJason233.chimera.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.ui.components.AppDialogSurface

@Composable
fun AboutDialog(
    githubUrl: String = "",
    websiteUrl: String = "",
    feedbackEmail: String = "",
    feedbackUrl: String = "",
    showUpdateAction: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onOpenBackendDialog: () -> Unit = {},
    telegramUrl: String = "",
    appDisplayName: String = "Chimera",
    appIconResourceId: Int = R.mipmap.ic_launcher,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hasFeedbackAction = feedbackEmail.isNotBlank() || feedbackUrl.isNotBlank()
    var iconClickCount by remember { mutableIntStateOf(0) }
    var lastIconClickAt by remember { mutableLongStateOf(0L) }

    fun handleAppNameClick() {
        val now = SystemClock.elapsedRealtime()
        iconClickCount = if (
            lastIconClickAt > 0L && now - lastIconClickAt <= 1_500L
        ) {
            iconClickCount + 1
        } else {
            1
        }
        lastIconClickAt = now
        if (iconClickCount >= 5) {
            iconClickCount = 0
            onDismiss()
            onOpenBackendDialog()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val verticalSafePadding = maxOf(statusBarTop, navigationBarBottom) + 12.dp

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 14.dp,
                    top = verticalSafePadding,
                    end = 14.dp,
                    bottom = verticalSafePadding
                ),
            contentAlignment = Alignment.Center
        ) {
            val isLargeScreen = maxWidth >= 600.dp
            val dialogWidthFraction = if (isLargeScreen) 0.58f else 1f
            val dialogMaxHeight = if (isLargeScreen) maxHeight * 0.94f else maxHeight
            val contentHorizontalPadding = if (isLargeScreen) 28.dp else 18.dp
            val contentVerticalPadding = if (isLargeScreen) 26.dp else 16.dp
            val iconSize = if (isLargeScreen) 88.dp else 64.dp
            val sectionSpacer = if (isLargeScreen) 24.dp else 14.dp

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            AppDialogSurface(
                modifier = Modifier
                    .fillMaxWidth(dialogWidthFraction)
                    .widthIn(max = 540.dp)
                    .wrapContentHeight()
                    .heightIn(max = dialogMaxHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                m3Shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                m3TonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = dialogMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = contentHorizontalPadding, vertical = contentVerticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 根据当前主题的启动器图标资源生成关于页预览。
                    val iconDrawable = remember(appIconResourceId) {
                        context.getDrawable(appIconResourceId)
                            ?: context.packageManager.getApplicationIcon(context.packageName)
                    }
                    val iconBitmap = remember(iconDrawable) {
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            iconDrawable.intrinsicWidth.coerceAtLeast(1),
                            iconDrawable.intrinsicHeight.coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
                        iconDrawable.draw(canvas)
                        bitmap.asImageBitmap()
                    }
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = "${appDisplayName.trim()} 应用图标",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(iconSize)
                            .clickable(onClick = ::handleAppNameClick)
                    )

                    Spacer(modifier = Modifier.height(if (isLargeScreen) 16.dp else 10.dp))

                    // App名字
                    Text(
                        text = appDisplayName.trim().ifBlank { "Chimera" },
                        fontSize = if (isLargeScreen) 24.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = ::handleAppNameClick
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 标语
                    Text(
                        text = appDisplayName.trim().ifBlank { "Chimera" },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(if (isLargeScreen) 20.dp else 12.dp))

                    // 描述
                    Text(
                        text = "基于xray和sing-box的现代化双核智能代理程序",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    if (
                        showUpdateAction ||
                        websiteUrl.isNotBlank() ||
                        hasFeedbackAction ||
                        githubUrl.isNotBlank() ||
                        telegramUrl.isNotBlank()
                    ) {
                        Spacer(modifier = Modifier.height(if (isLargeScreen) 20.dp else 14.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showUpdateAction) {
                                AboutIconAction(
                                    icon = Icons.Outlined.SystemUpdate,
                                    contentDescription = "检查更新",
                                    onClick = {
                                        onDismiss()
                                        onCheckUpdate()
                                    }
                                )
                            }

                            if (websiteUrl.isNotBlank()) {
                                AboutIconAction(
                                    icon = Icons.Outlined.Language,
                                    contentDescription = "官网",
                                    onClick = {
                                        onDismiss()
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))
                                        )
                                    }
                                )
                            }

                            if (hasFeedbackAction) {
                                AboutIconAction(
                                    icon = Icons.Outlined.Email,
                                    contentDescription = "邮箱",
                                    onClick = {
                                        if (feedbackEmail.isNotBlank()) {
                                            val clipboard = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE
                                            ) as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText("email", feedbackEmail)
                                            )
                                            Toast.makeText(
                                                context,
                                                "邮箱已复制",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        onDismiss()
                                        if (feedbackUrl.isNotBlank()) {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl))
                                            )
                                        }
                                    }
                                )
                            }

                            if (githubUrl.isNotBlank()) {
                                AboutIconAction(
                                    painter = painterResource(id = R.drawable.ic_github),
                                    contentDescription = "GitHub",
                                    onClick = {
                                        onDismiss()
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                                        )
                                    }
                                )
                            }

                            if (telegramUrl.isNotBlank()) {
                                AboutIconAction(
                                    painter = painterResource(id = R.drawable.ic_telegram),
                                    contentDescription = "Telegram",
                                    onClick = {
                                        onDismiss()
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl))
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    // Close button
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "关闭",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutIconAction(
    icon: ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (painter != null) {
                    Icon(
                        painter = painter,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = contentDescription,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
