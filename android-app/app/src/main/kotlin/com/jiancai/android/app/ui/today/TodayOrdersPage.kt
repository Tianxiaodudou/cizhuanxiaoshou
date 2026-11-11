/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/today/TodayOrdersPage.kt
 * @role 8. 改当日单列表（底栏 tab）：只列服务端「今天」的单据，是否可改由服务端判定。
 * @exports TodayOrdersPage
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.EmptyHint
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.LoadingRow
import com.jiancai.android.app.ui.common.MoneyText
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.callIo
import com.jiancai.android.app.ui.common.display
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.OrderDto
import kotlinx.coroutines.launch

/**
 * 8. 改当日单列表（底栏 tab）：只列服务端「今天」的单据，是否可改由服务端判定。
 */
@Composable
fun TodayOrdersPage(
    container: AppContainer,
    onEdit: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf(emptyList<OrderDto>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = container.client.callIo(ApiPaths.todayOrders())) {
            is ApiResult.Success -> orders = result.value
            is ApiResult.Failure -> error = result.display()
        }
        loading = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("改当日单") }) },
        bottomBar = bottomBar,
    ) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding)) {
            Text("今日单据", style = MaterialTheme.typography.titleSmall)
            error?.let { ErrorText(it) }
            if (loading) LoadingRow()
            if (!loading && orders.isEmpty()) EmptyHint("今天还没有单据")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(orders, key = { it.orderNo }) { order ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onEdit(order.orderNo) },
                    ) {
                        Row(Modifier.padding(PagePadding), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(order.orderNo, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${order.createdAt.substringAfter('T').take(5)}  ${order.customerName}  ${order.orderType.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            MoneyText(order.totalAmount.display())
                        }
                    }
                }
            }
            Text(
                "仅可修改今天生成的单据；历史单据由服务端拒绝（E2003）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            scope.launch { }
        }
    }
}
