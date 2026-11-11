/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/query/QueryPage.kt
 * @role 7. 查询：按电话查客户 + 订单列表（底栏 tab）。
 * @exports QueryPage
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.query

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.jiancai.android.app.ui.common.LoadingRow
import com.jiancai.android.app.ui.common.MoneyText
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.SectionLabel
import com.jiancai.android.app.ui.common.callIo
import com.jiancai.android.app.ui.common.display
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.ReceivableDto
import kotlinx.coroutines.launch

/**
 * 7. 查询：按电话查客户 + 订单列表（底栏 tab）。
 *
 * 累计只取服务端读模型（`/customers/{id}/balance`），不在本地累加。
 */
@Composable
fun QueryPage(container: AppContainer, bottomBar: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var customerName by remember { mutableStateOf<String?>(null) }
    var balance by remember { mutableStateOf<ReceivableDto?>(null) }
    var orders by remember { mutableStateOf<List<OrderDto>>(emptyList()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("查询") }) },
        bottomBar = bottomBar,
    ) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding)) {
            AppTextField("电话", phone, { phone = it }, numeric = true)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    error = null
                    loading = true
                    scope.launch {
                        val customer = container.client.callIo(ApiPaths.customerByPhone(phone))
                        if (customer is ApiResult.Failure) {
                            loading = false
                            error = customer.display()
                            return@launch
                        }
                        val found = (customer as ApiResult.Success).value
                        customerName = found.name
                        balance = when (val b = container.client.callIo(ApiPaths.balance(found.id))) {
                            is ApiResult.Success -> b.value
                            is ApiResult.Failure -> null
                        }
                        orders = when (val page = container.client.callIo(
                            ApiPaths.orders(OrderQuery(phone = phone, pageSize = 20)),
                        )) {
                            is ApiResult.Success -> page.value.items
                            is ApiResult.Failure -> {
                                error = page.display()
                                emptyList()
                            }
                        }
                        loading = false
                    }
                },
                enabled = !loading && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "查询中…" else "查询") }

            error?.let { ErrorText(it) }
            customerName?.let { name ->
                SectionLabel(name)
                balance?.let { b ->
                    Text(
                        "应收 ${b.receivableAmount.display()} · 已收 ${b.receivedAmount.display()} · 未收 ${b.unreceivedAmount.display()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (loading) LoadingRow()
            if (!loading && customerName != null && orders.isEmpty()) EmptyHint("没有单据")

            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders, key = { it.orderNo }) { order ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(PagePadding)) {
                            Text("${order.orderDate}  ${order.orderType.label}", style = MaterialTheme.typography.titleSmall)
                            Text(order.orderNo, style = MaterialTheme.typography.bodySmall)
                            order.lines.forEach { line ->
                                Text(
                                    "${line.categoryName} ${line.model} ${line.batchTag}  ${line.quantity}${line.unit.label}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            MoneyText(order.totalAmount.display())
                        }
                    }
                }
            }
        }
    }
}
