package io.github.HanJason233.chimera.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.BackendAppSettings

@Composable
fun StartupAccountScreen(
    backendSettings: BackendAppSettings,
    isBusy: Boolean,
    message: String?,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String, registrationCode: String) -> Unit,
    onGuestLogin: () -> Unit,
    onOfflineUse: () -> Unit
) {
    var isRegistration by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var registrationCode by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val normalizedEmail = email.trim().lowercase()
    val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()
    val validPassword = password.length in 8..128
    val canSubmit = !isBusy && if (isRegistration) {
        backendSettings.registrationEnabled &&
            validEmail &&
            validPassword &&
            password == confirmPassword &&
            (!backendSettings.requireAdminRegistrationCode || registrationCode.isNotBlank())
    } else {
        validEmail && validPassword
    }

    // 注册页可返回登录页；登录页继续拦截系统返回，避免跳过启动认证。
    BackHandler(enabled = true) {
        if (isRegistration && !isBusy) isRegistration = false
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isRegistration) "创建账号" else "欢迎回来",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isRegistration) {
                            "注册 Chimera，开启全新体验"
                        } else {
                            "登录 Chimera，继续你的旅程"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AuthOutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(254) },
                    placeholder = "邮箱",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Email,
                            contentDescription = null
                        )
                    },
                    enabled = !isBusy,
                    isError = email.isNotBlank() && !validEmail,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                if (isRegistration) {
                    AuthOutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it.take(16) },
                        placeholder = "验证码",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null
                            )
                        },
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    Toast.makeText(context, "获取验证码功能暂未开放", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !isBusy
                            ) {
                                Text("获取验证码", maxLines = 1)
                            }
                        }
                    )
                }

                AuthPasswordField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    placeholder = "密码",
                    visible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible },
                    enabled = !isBusy,
                    isError = password.isNotBlank() && !validPassword
                )

                if (isRegistration) {
                    AuthPasswordField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it.take(128) },
                        placeholder = "确认密码",
                        visible = confirmPasswordVisible,
                        onToggleVisibility = { confirmPasswordVisible = !confirmPasswordVisible },
                        enabled = !isBusy,
                        isError = confirmPassword.isNotEmpty() && password != confirmPassword
                    )

                    AuthOutlinedTextField(
                        value = registrationCode,
                        onValueChange = { registrationCode = it.take(32) },
                        placeholder = if (backendSettings.requireAdminRegistrationCode) {
                            "邀请码（必填）"
                        } else {
                            "邀请码（可选）"
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.CardGiftcard,
                                contentDescription = null
                            )
                        },
                        enabled = !isBusy && backendSettings.registrationEnabled
                    )
                    if (!backendSettings.registrationEnabled) {
                        Text(
                            text = "暂未开放正式注册，你仍可以选择游客登录。",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                Toast.makeText(context, "找回密码功能暂未开放", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !isBusy
                        ) {
                            Text("忘记密码？")
                        }
                    }
                }

                message?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }

                Button(
                    onClick = {
                        if (isRegistration) {
                            onRegister(normalizedEmail, password, registrationCode)
                        } else {
                            onLogin(normalizedEmail, password)
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = if (isBusy) {
                            if (isRegistration) "正在注册…" else "正在登录…"
                        } else if (isRegistration) {
                            "注册并登录"
                        } else {
                            "登录"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (!isRegistration) {
                    OutlinedButton(
                        onClick = onGuestLogin,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = if (isBusy) "正在登录…" else "以游客身份继续",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onOfflineUse,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = "仅本地使用（离线模式）",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        text = "跳过登录与注册，使用本机保存的数据，不连接 Chimera 后台。",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }

                AuthModeFooter(
                    isRegistration = isRegistration,
                    enabled = !isBusy,
                    onClick = { isRegistration = !isRegistration }
                )
            }
        }
    }
}

@Composable
private fun AuthOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: (@Composable () -> Unit)?,
    enabled: Boolean,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingContent: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingContent,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        keyboardOptions = keyboardOptions
    )
}

@Composable
private fun AuthPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    enabled: Boolean,
    isError: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null
            )
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisibility, enabled = enabled) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (visible) "隐藏密码" else "显示密码"
                )
            }
        },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
}

@Composable
private fun AuthModeFooter(
    isRegistration: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Text(
                text = if (isRegistration) "已有账号？" else "还没有账号？",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        TextButton(onClick = onClick, enabled = enabled) {
            Text(
                text = if (isRegistration) "立即登录  →" else "立即注册  →",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
