/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/home/HomePage.kt
 * @role 2. 首页：2×2 四个录入入口 + 备注单大按钮（底栏由导航提供）。
 * @exports HomePage
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.PagePadding

/**
 * 2. 首页：2×2 四个录入入口 + 备注单大按钮（底栏由导航提供）。
 *
 * 账目相关输入全部走服务端，本页只做导航。
 */
@Composable
fun HomePage(
    container: AppContainer,
    onDeliveryEntry: () -> Unit,
    onDeliveryReturn: () -> Unit,
    onPickupEntry: () -> Unit,
    onPickupReturn: () -> Unit,
    onNote: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("首页")
                    Text(
                        container.account()?.displayName ?: "未登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            })
        },
        bottomBar = bottomBar,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(inner)
                .padding(PagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryButton("配送送货", Modifier.weight(1f), onDeliveryEntry)
                EntryButton("配送退货", Modifier.weight(1f), onDeliveryReturn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryButton("自提送货", Modifier.weight(1f), onPickupEntry)
                EntryButton("自提退货", Modifier.weight(1f), onPickupReturn)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onNote, modifier = Modifier.fillMaxWidth()) {
                Text("备 注 单", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PagePadding)) {
                    Text("服务端", style = MaterialTheme.typography.labelMedium)
                    Text(container.addresses.baseUrl(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "金额 / 单号 / 单价均由服务端产出，客户端只展示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = {}, enabled = false) { Text("打印由服务端处理（客户端只读）") }
        }
    }
}

@Composable
private fun EntryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(64.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
