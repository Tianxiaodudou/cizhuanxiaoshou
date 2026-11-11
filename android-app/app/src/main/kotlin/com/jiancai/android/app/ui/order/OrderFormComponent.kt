/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/order/OrderFormComponent.kt
 * @role 3–6 共用录入表单（配送送货 / 配送退货 / 自提送货 / 自提退货）。
 * @exports OrderKind, OrderFormComponent
 * @depends core-network, shared
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/ui/order/DeliveryEntryPage.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/ui/order/DeliveryReturnPage.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/ui/order/PickupEntryPage.kt
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.AppTextField
import com.jiancai.android.app.ui.common.EmptyHint
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.KeyValueRow
import com.jiancai.android.app.ui.common.MoneyText
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.SectionLabel
import com.jiancai.android.app.ui.common.callIo
import com.jiancai.android.app.ui.common.display
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.units.PricingUnit
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 四类录入页共用的单据类型（页面预览 3–6 只通过它区分，禁止复制表单）。
 *
 * 与服务端 `OrderType` 的映射：配送送货→PURCHASE、配送退货→PURCHASE_RETURN、
 * 自提送货→DIRECT、自提退货→DIRECT_RETURN。
 */
enum class OrderKind(val label: String, val type: OrderType) {
    DELIVERY("配送送货", OrderType.PURCHASE),
    DELIVERY_RETURN("配送退货", OrderType.PURCHASE_RETURN),
    PICKUP("自提送货", OrderType.DIRECT),
    PICKUP_RETURN("自提退货", OrderType.DIRECT_RETURN);

    val isReturn: Boolean get() = this == DELIVERY_RETURN || this == PICKUP_RETURN

    companion object {
        fun of(type: OrderType): OrderKind = when (type) {
            OrderType.PURCHASE -> DELIVERY
            OrderType.PURCHASE_RETURN -> DELIVERY_RETURN
            OrderType.DIRECT -> PICKUP
            OrderType.DIRECT_RETURN -> PICKUP_RETURN
        }
    }
}

/** 一行明细的编辑态（金额只存服务端给的单价格式串）。 */
private data class LineDraft(
    val key: Long,
    val categoryId: Long? = null,
    val categoryName: String = "",
    val batchTag: String = "",
    val unit: PricingUnit = PricingUnit.PIECE,
    val quantity: String = "",
    val unitPrice: String? = null,
    val returnableQuantity: String? = null,
)

/**
 * 3–6 共用录入表单（配送送货 / 配送退货 / 自提送货 / 自提退货）。
 *
 * 交互约束：客户按电话查；单价取服务端 `/prices`；本单合计在提交后由服务端返回；
 * 退货必须选原单并从可退批次里选；金额只展示不计算。
 */
@Composable
fun OrderFormComponent(
    container: AppContainer,
    kind: OrderKind,
    existing: OrderDto? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val editing = existing != null
    val lines = remember { mutableStateListOf<LineDraft>() }
    var nextKey by remember { mutableStateOf(1L) }

    var phone by remember { mutableStateOf(existing?.customerPhone ?: "") }
    var customerId by remember { mutableStateOf(existing?.customerId) }
    var customerName by remember { mutableStateOf(existing?.customerName ?: "") }
    var categories by remember { mutableStateOf(emptyList<com.jiancai.shared.dto.CategoryDto>()) }
    var remark by remember { mutableStateOf(existing?.remark.orEmpty()) }
    var balanceText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf<OrderDto?>(null) }
    var printMessage by remember { mutableStateOf<String?>(null) }

    // 退货：原单 + 可退批次
    var originalOrder by remember { mutableStateOf<OrderDto?>(null) }
    var candidates by remember { mutableStateOf(emptyList<OrderDto>()) }
    var returnable by remember { mutableStateOf(emptyList<ReturnableBatchDto>()) }

    fun refreshBalance() {
        val id = customerId ?: return
        scope.launch {
            when (val b = container.client.callIo(ApiPaths.balance(id))) {
                is ApiResult.Success -> balanceText = b.value.receivableAmount.display()
                is ApiResult.Failure -> Unit
            }
        }
    }
    LaunchedEffect(Unit) {
        when (val result = container.client.callIo(ApiPaths.categories(enabledOnly = true))) {
            is ApiResult.Success -> categories = result.value
            is ApiResult.Failure -> error = result.display()
        }
        if (existing != null) {
            nextKey = 1
            lines.clear()
            existing.lines.forEach { line ->
                lines += LineDraft(
                    key = nextKey++,
                    categoryId = line.categoryId,
                    categoryName = line.categoryName,
                    batchTag = line.batchTag,
                    unit = line.unit,
                    quantity = line.quantity,
                    unitPrice = line.unitPrice.display(),
                )
            }
            refreshBalance()
        }
    }


    fun addLine() {
        lines += LineDraft(key = nextKey++)
    }

    fun updatePrice(index: Int) {
        val line = lines.getOrNull(index) ?: return
        val id = customerId ?: return
        val categoryId = line.categoryId ?: return
        scope.launch {
            when (val price = container.client.callIo(ApiPaths.price(id, categoryId, line.batchTag))) {
                is ApiResult.Success -> lines[index] = line.copy(unitPrice = price.value.unitPrice.display())
                is ApiResult.Failure -> error = price.display()
            }
        }
    }

    fun submit() {
        error = null
        val id = customerId
        if (id == null) {
            error = "请先按电话查客户"
            return
        }
        if (lines.isEmpty()) {
            error = "请至少录入一行明细"
            return
        }
        if (lines.any { it.categoryId == null || it.quantity.isBlank() }) {
            error = "每行都要选品类并填数量"
            return
        }
        if (kind.isReturn && originalOrder == null) {
            error = "退货单必须先选原单"
            return
        }
        loading = true
        scope.launch {
            val cmds = lines.map { line ->
                OrderLineCmd(
                    categoryId = line.categoryId!!,
                    batchTag = line.batchTag,
                    unit = line.unit,
                    quantity = line.quantity,
                )
            }
            val result = if (existing != null) {
                container.client.callIo(
                    ApiPaths.updateOrder(
                        UpdateOrderCmd(
                            orderNo = existing.orderNo,
                            version = existing.version,
                            lines = cmds,
                            remark = remark.ifBlank { null },
                        ),
                    ),
                )
            } else {
                container.client.callIo(
                    ApiPaths.createOrder(
                        CreateOrderCmd(
                            clientRequestId = "android-" + UUID.randomUUID(),
                            orderType = kind.type,
                            customerId = id,
                            lines = cmds,
                            remark = remark.ifBlank { null },
                            sourceOrderNo = originalOrder?.orderNo,
                        ),
                    ),
                )
            }
            loading = false
            when (result) {
                is ApiResult.Success -> {
                    submitted = result.value
                    refreshBalance()
                    // 打印只在服务端：提交成功后请求服务端渲染一次（客户端不渲染、不连打印机）
                    when (val printed = container.client.callIo(ApiPaths.printTicket(result.value.orderNo))) {
                        is ApiResult.Success -> printMessage = "已提交服务端渲染（80mm），客户端不打印"
                        is ApiResult.Failure -> printMessage = "服务端渲染失败：${printed.display()}"
                    }
                }
                is ApiResult.Failure -> error = result.display()
            }
        }
    }

    fun loadReturnContext() {
        if (phone.isBlank()) {
            error = "请先填电话并查客户"
            return
        }
        scope.launch {
            when (val page = container.client.callIo(ApiPaths.orders(OrderQuery(phone = phone, pageSize = 10)))) {
                is ApiResult.Success -> candidates = page.value.items.filter { it.orderType == OrderType.PURCHASE || it.orderType == OrderType.DIRECT }
                is ApiResult.Failure -> error = page.display()
            }
        }
    }

    fun chooseOriginal(order: OrderDto) {
        originalOrder = order
        scope.launch {
            when (val result = container.client.callIo(ApiPaths.returnableBatches(order.orderNo))) {
                is ApiResult.Success -> returnable = result.value
                is ApiResult.Failure -> error = result.display()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (editing) "修改 ${existing?.orderNo}" else kind.label + "单") }) },
    ) { inner ->
        Column(
            Modifier.fillMaxWidth().padding(inner).padding(PagePadding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel("客户")
            Row(verticalAlignment = Alignment.Bottom) {
                AppTextField("客户电话", phone, { phone = it }, modifier = Modifier.weight(1f), numeric = true)
                OutlinedButton(
                    onClick = {
                        if (phone.isBlank()) return@OutlinedButton
                        scope.launch {
                            when (val c = container.client.callIo(ApiPaths.customerByPhone(phone))) {
                                is ApiResult.Success -> {
                                    customerId = c.value.id
                                    customerName = c.value.name
                                    refreshBalance()
                                }
                                is ApiResult.Failure -> error = c.display()
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("查客户") }
            }
            if (customerName.isNotBlank()) KeyValueRow("客户", customerName)
            balanceText?.let { KeyValueRow("历史应收", it) }

            if (kind.isReturn && !editing) {
                SectionLabel("原单（退货必须关联原单）")
                OutlinedButton(onClick = ::loadReturnContext, modifier = Modifier.fillMaxWidth()) {
                    Text(if (originalOrder == null) "选择原单" else "已选 ${originalOrder?.orderNo}")
                }
                candidates.takeIf { it.isNotEmpty() && originalOrder == null }?.forEach { order ->
                    OutlinedButton(onClick = { chooseOriginal(order) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${order.orderNo}  ${order.orderType.label}  ${order.totalAmount.display()}")
                    }
                }
                returnable.forEach { batch ->
                    Text(
                        "可退：${batch.categoryName} ${batch.batchTag} ${batch.returnableQuantity}${batch.unit.label}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionLabel("明细")
            lines.forEachIndexed { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryPicker(
                                categories = categories,
                                selected = line.categoryName,
                                onPick = { category ->
                                    lines[index] = line.copy(
                                        categoryId = category.id,
                                        categoryName = category.categoryName,
                                        unit = category.unit,
                                        batchTag = line.batchTag.ifBlank { category.batchTags.firstOrNull().orEmpty() },
                                    )
                                    updatePrice(index)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { lines.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除本行")
                            }
                        }
                        AppTextField(
                            "批号 / 色号",
                            line.batchTag,
                            { value ->
                                lines[index] = line.copy(batchTag = value)
                                updatePrice(index)
                            },
                        )
                        AppTextField(
                            "数量（${line.unit.label}）",
                            line.quantity,
                            { value -> lines[index] = line.copy(quantity = value) },
                            numeric = true,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("单价（服务端）", style = MaterialTheme.typography.bodySmall)
                            MoneyText(line.unitPrice ?: "待解析")
                        }
                        if (kind.isReturn) {
                            val batch = returnable.firstOrNull {
                                it.categoryId == line.categoryId && it.batchTag == line.batchTag
                            }
                            Text(
                                "可退数量：${batch?.returnableQuantity ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = ::addLine, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加一行")
            }

            SectionLabel("合计")
            KeyValueRow("本单合计", submitted?.totalAmount?.display() ?: "提交后由服务端计算")
            KeyValueRow("历史累计（应收）", balanceText ?: "—")

            SectionLabel("备注")
            AppTextField("备注", remark, { remark = it })

            error?.let { ErrorText(it) }
            submitted?.let { order ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("提交成功", fontWeight = FontWeight.SemiBold)
                        KeyValueRow("单号", order.orderNo)
                        KeyValueRow("总额", order.totalAmount.display(), emphasize = true)
                        KeyValueRow("应收", order.receivableAmount.display())
                        printMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { submitted = null; error = null },
                    modifier = Modifier.weight(1f),
                ) { Text("暂 存") }
                Button(
                    onClick = ::submit,
                    enabled = !loading && !editing,
                    modifier = Modifier.weight(1f),
                ) { Text(if (loading) "提交中…" else "提交打印") }
            }
            if (editing) {
                Button(onClick = ::submit, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "保存中…" else "保存修改")
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}

/** 品类下拉（数据来自 `/categories`）。 */
@Composable
private fun CategoryPicker(
    categories: List<com.jiancai.shared.dto.CategoryDto>,
    selected: String,
    onPick: (com.jiancai.shared.dto.CategoryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.ifBlank { "选择品类" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text("${category.categoryName} ${category.model}") },
                    onClick = {
                        expanded = false
                        onPick(category)
                    },
                )
            }
        }
    }
}
