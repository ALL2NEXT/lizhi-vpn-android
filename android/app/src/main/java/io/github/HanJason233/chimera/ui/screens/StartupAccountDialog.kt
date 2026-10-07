package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.HanJason233.chimera.data.model.BackendAppSettings

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StartupAccountDialog(
    backendSettings: BackendAppSettings,
    isBusy: Boolean,
    message: String?,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String, registrationCode: String) -> Unit,
    onGuestLogin: () -> Unit
) {
    var isRegistration by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var registrationCode by remember { mutableStateOf("") }

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

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "欢迎使用 Chimera",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "请登录或注册，也可以使用游客身份继续。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isRegistration,
                        onClick = { if (!isBusy) isRegistration = false },
                        label = { Text("登录") },
                        enabled = !isBusy
                    )
                    FilterChip(
                        selected = isRegistration,
                        onClick = { if (!isBusy) isRegistration = true },
                        label = { Text("注册") },
                        enabled = !isBusy
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(254) },
                    label = { Text("邮箱") },
                    singleLine = true,
                    enabled = !isBusy,
                    isError = email.isNotBlank() && !validEmail,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    label = { Text("密码") },
                    supportingText = { Text("8～128 个字符") },
                    singleLine = true,
                    enabled = !isBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                if (isRegistration) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it.take(128) },
                        label = { Text("确认密码") },
                        singleLine = true,
                        enabled = !isBusy,
                        isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = registrationCode,
                        onValueChange = { registrationCode = it.take(32) },
                        label = {
                            Text(
                                if (backendSettings.requireAdminRegistrationCode) {
                                    "管理员一次性注册码（必填）"
                                } else {
                                    "管理员一次性注册码（可选）"
                                }
                            )
                        },
                        singleLine = true,
                        enabled = !isBusy && backendSettings.registrationEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!backendSettings.registrationEnabled) {
                        Text(
                            text = "暂未开放正式注册，你仍可选择游客登录。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }

                message?.let {
                    Text(
                        text = it,
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (isBusy) "正在处理…" else if (isRegistration) "注册并登录" else "登录")
                }

                if (isRegistration) {
                    OutlinedButton(
                        onClick = onGuestLogin,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "正在登录…" else "游客登录")
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}
