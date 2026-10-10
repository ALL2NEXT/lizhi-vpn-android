package io.github.HanJason233.chimera.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.BackendAppSettings

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StartupAccountScreen(
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

    BackHandler(enabled = true) { }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "欢迎使用 Chimera",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "登录或注册账户，也可以先以游客身份继续使用。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }

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
                    supportingText = { Text("密码需为 8 到 128 个字符") },
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
                                    "注册码（必填）"
                                } else {
                                    "注册码（可选）"
                                }
                            )
                        },
                        singleLine = true,
                        enabled = !isBusy && backendSettings.registrationEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!backendSettings.registrationEnabled) {
                        Text(
                            text = "暂未开放正式注册，你仍可以选择游客登录。",
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

                OutlinedButton(
                    onClick = onGuestLogin,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "正在登录…" else "以游客身份继续")
                }
            }
        }
    }
}
