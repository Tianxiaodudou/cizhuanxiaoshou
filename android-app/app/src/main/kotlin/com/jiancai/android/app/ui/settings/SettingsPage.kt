/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/settings/SettingsPage.kt
 * @role 12. 设置：当前账号、服务端地址（可改）、打印状态（只读）、退出登录。
 * @exports SettingsPage
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.AppTextField
import com.jiancai.android.app.ui.common.KeyValueRow
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.SectionLabel

/**
 * 12. 设置：当前账号、服务端地址（可改）、打印状态（只读）、退出登录。
 *
 * 打印状态由服务端 S12 维护，客户端只展示「由服务端处理」，没有打印入口。
 */
@Composable
fun SettingsPage(
    container: AppContainer,
    onLoggedOut: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    var address by remember { mutableStateOf(container.addresses.baseUrl()) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        bottomBar = bottomBar,
    ) { inner ->
        Column(
            Modifier.fillMaxWidth().padding(inner).padding(PagePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PagePadding)) {
                    KeyValueRow("当前账号", container.account()?.displayName ?: "未登录")
                    KeyValueRow("服务端", container.addresses.baseUrl())
                }
            }
            SectionLabel("打印机状态（只读）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PagePadding)) {
                    KeyValueRow("打印方式", "服务端 S12 渲染")
                    KeyValueRow("客户端打印", "无（客户端只读）")
                    Text(
                        "客户端不连接打印机、不渲染票据；票据由服务端生成后交打印任务处理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionLabel("修改服务端地址")
            AppTextField("服务端地址", address, { address = it }, supporting = message)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val saved = runCatching { container.addresses.update(address) }
                    message = if (saved.isSuccess) "已保存：${saved.getOrThrow().value}" else "非法：${saved.exceptionOrNull()?.message}"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存地址") }
            OutlinedButton(
                onClick = {
                    container.session.logout()
                    onLoggedOut()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出登录") }
        }
    }
}
