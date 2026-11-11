/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/note/SelectOrderPage.kt
 * @role 11. 选原单：按电话列出近单，点「选择此单」回填备注单页（禁止手输单号）。
 * @exports SelectOrderPage
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.AppTextField
import com.jiancai.android.app.ui.common.EmptyHint
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.MoneyText
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.callIo
import com.jiancai.android.app.ui.common.display
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import kotlinx.coroutines.launch

/**
 * 11. 选原单：按电话列出近单，点「选择此单」回填备注单页（禁止手输单号）。
 */
@Composable
fun SelectOrderPage(
    container: AppContainer,
    onSelected: (OrderDto) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var orders by remember { mutableStateOf(emptyList<OrderDto>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("选择原单") }) }) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding)) {
            AppTextField("电话", phone, { phone = it }, numeric = true)
            Button(
                onClick = {
                    if (phone.isBlank()) return@Button
                    error = null
                    loading = true
                    scope.launch {
                        when (val result = container.client.callIo(
                            ApiPaths.orders(OrderQuery(phone = phone, pageSize = 10)),
                        )) {
                            is ApiResult.Success -> orders = result.value.items
                            is ApiResult.Failure -> error = result.display()
                        }
                        loading = false
                    }
                },
                enabled = !loading && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(if (loading) "查询中…" else "查询") }

            error?.let { ErrorText(it) }
            if (!loading && orders.isEmpty()) EmptyHint("没有可选的单据")

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders, key = { it.orderNo }) { order ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(PagePadding)) {
                            Text("${order.orderDate}  ${order.orderType.label}", style = MaterialTheme.typography.titleSmall)
                            Text(order.orderNo, style = MaterialTheme.typography.bodySmall)
                            order.lines.forEach { line ->
                                Text(
                                    "${line.categoryName} ${line.model} ${line.batchTag} ${line.quantity}${line.unit.label}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                MoneyText(order.totalAmount.display())
                                OutlinedButton(onClick = { onSelected(order) }) { Text("选择此单") }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("返回")
            }
        }
    }
}
