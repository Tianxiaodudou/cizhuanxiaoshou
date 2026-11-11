/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/today/TodayOrderEditPage.kt
 * @role 9. 改当日单编辑：按单号读取服务端订单，复用录入表单（可改数量 / 增删行）。
 * @exports TodayOrderEditPage
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.LoadingRow
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.callIo
import com.jiancai.android.app.ui.common.display
import com.jiancai.android.app.ui.order.OrderFormComponent
import com.jiancai.android.app.ui.order.OrderKind
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery

/**
 * 9. 改当日单编辑：按单号读取服务端订单，复用录入表单（可改数量 / 增删行）。
 *
 * 类型不可改（服务端拒绝非当日单 `E2003`，客户端不自行判断日期）。
 */
@Composable
fun TodayOrderEditPage(container: AppContainer, orderNo: String, onBack: () -> Unit) {
    var order by remember { mutableStateOf<OrderDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderNo) {
        if (orderNo.isBlank()) {
            error = "缺少单号"
            return@LaunchedEffect
        }
        when (val result = container.client.callIo(ApiPaths.orders(OrderQuery(orderNo = orderNo, pageSize = 1)))) {
            is ApiResult.Success -> order = result.value.items.firstOrNull() ?: null.also { error = "订单不存在" }
            is ApiResult.Failure -> error = result.display()
        }
    }

    val loaded = order
    if (loaded != null) {
        OrderFormComponent(
            container = container,
            kind = OrderKind.of(loaded.orderType),
            existing = loaded,
            onBack = onBack,
        )
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("修改 $orderNo") }) }) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding)) {
            if (error == null) LoadingRow() else ErrorText(error!!)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}
