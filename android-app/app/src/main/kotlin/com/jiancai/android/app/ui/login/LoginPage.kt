/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/login/LoginPage.kt
 * @role 1. 登录页。
 * @exports LoginPage
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.AppTextField
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.SectionLabel
import kotlinx.coroutines.launch

/**
 * 1. 登录页。
 *
 * 服务端地址可改（保存到本地，立即生效）；登录走 C01 `SessionGate`，页面不接触 token。
 */
@Composable
fun LoginPage(container: AppContainer, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(container.addresses.baseUrl()) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("建材进销存", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("单据管理", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))

            SectionLabel("服务端地址")
            AppTextField("服务端地址", address, { address = it }, supporting = "例如 http://192.168.1.20:8080")
            Spacer(Modifier.height(8.dp))
            AppTextField("账号", username, { username = it })
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { ErrorText(it) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    error = null
                    val saved = runCatching { container.addresses.update(address) }
                    if (saved.isFailure) {
                        error = "服务端地址非法：${saved.exceptionOrNull()?.message}"
                        return@Button
                    }
                    loading = true
                    scope.launch {
                        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            container.session.login(username, password)
                        }
                        loading = false
                        if (outcome.success) {
                            onLoggedIn()
                        } else {
                            error = outcome.failure?.let { "${it.wireCode ?: ""} ${it.message}" } ?: "登录失败"
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "登录中…" else "登 录")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "服务端：${container.addresses.baseUrl()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
