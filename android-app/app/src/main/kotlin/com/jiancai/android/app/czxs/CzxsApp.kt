@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.czxs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.ui.common.AppTextField
import com.jiancai.android.app.ui.common.EmptyHint
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.KeyValueRow
import com.jiancai.android.app.ui.common.LoadingRow
import com.jiancai.android.app.ui.common.MoneyText
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.PageScaffold
import com.jiancai.android.app.ui.common.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ---------- 路由 ----------
sealed interface Route {
    data object Login : Route
    data object Home : Route
    data class Order(val type: String, val orderId: Long? = null) : Route
    data class Payment(val type: String) : Route
    data object Query : Route
    data object Today : Route
    data object Orders : Route
    data object Note : Route
    data object PickOrder : Route
    data object Settlement : Route
    data object Settings : Route
}

private val TABS = listOf("单据" to Route.Orders, "查询" to Route.Query, "改当日单" to Route.Today, "结算" to Route.Settlement, "设置" to Route.Settings)

/** czxs 客户端根：登录守卫 + 底栏 + 页面栈（单 Activity，状态驱动）。 */
@Composable
fun CzxsApp(api: CzxsApi) {
    var route by remember { mutableStateOf<Route>(if (api.token.isNullOrBlank()) Route.Login else Route.Home) }

    fun goBack() {
        route = when (route) {
            is Route.Order -> Route.Home
            is Route.Payment -> Route.Home
            Route.PickOrder -> Route.Note
            Route.Today, Route.Query, Route.Settings, Route.Settlement, Route.Orders -> Route.Home
            else -> Route.Home
        }
    }

    val current = route
    when (current) {
        Route.Login -> LoginScreen(api) { route = Route.Home }
        Route.Home -> HomeScreen(api, ::goBack) { route = it }
        is Route.Order -> OrderScreen(api, current.type, current.orderId, onBack = ::goBack) { route = Route.Home }
        is Route.Payment -> PaymentScreen(api, current.type, onBack = ::goBack) { route = Route.Home }
        Route.Query -> QueryScreen(api, onBack = ::goBack)
        Route.Today -> TodayScreen(api, onBack = ::goBack) { id -> route = Route.Order("edit", id) }
        Route.Orders -> OrdersScreen(api, onBack = ::goBack) { id -> route = Route.Order("edit", id) }
        Route.Note -> NoteScreen(api, onPick = { route = Route.PickOrder }, onBack = ::goBack)
        Route.PickOrder -> PickOrderScreen(api, onBack = ::goBack)
        Route.Settlement -> SettlementScreen(api, onBack = ::goBack)
        Route.Settings -> SettingsScreen(api, onBack = ::goBack) { route = Route.Login }
    }
}

@Composable
private fun BottomTabs(current: Route, onSelect: (Route) -> Unit) {
    NavigationBar {
        TABS.forEach { (label, r) ->
            NavigationBarItem(
                selected = current::class == r::class,
                onClick = { onSelect(r) },
                icon = { Text("•") },
                label = { Text(label) },
            )
        }
    }
}

// ---------- 登录 ----------
@Composable
private fun LoginScreen(api: CzxsApi, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(api.baseUrl) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    PageScaffold("登录") { modifier ->
        Column(modifier.padding(PagePadding)) {
            Text("瓷砖店销售系统", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            AppTextField("NAS 域名（如 192.168.1.20 或 http://nas:8080）", server, { server = it })
            AppTextField("管理员账号", username, { username = it })
            AppTextField("密码", password, { password = it }, numeric = false)
            error?.let { ErrorText(it) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            runCatching { api.saveServer(server) }.getOrElse {
                                throw ApiException("SERVER_INVALID", "域名格式不对：${it.message}")
                            }
                            withContext(Dispatchers.IO) { api.login(username, password) }
                            onLoggedIn()
                        } catch (e: ApiException) {
                            error = "${e.code}：${e.message}"
                        } catch (e: Exception) {
                            error = e.message ?: "登录失败"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "登录中…" else "登 录") }
            Spacer(Modifier.height(8.dp))
            Text("服务端：${api.baseUrl}$GATEWAY_PREFIX", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- 首页 ----------
@Composable
private fun HomeScreen(api: CzxsApi, onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    PageScaffold("首页", bottomBar = { BottomTabs(Route.Home, onNavigate) }) { modifier ->
        Column(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("店员：${api.accountName.ifBlank { "未登录" }}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrderEntry("配送", Modifier.weight(1f)) { onNavigate(Route.Order("delivery")) }
                OrderEntry("配退", Modifier.weight(1f)) { onNavigate(Route.Order("delivery_return")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrderEntry("自送", Modifier.weight(1f)) { onNavigate(Route.Order("pickup")) }
                OrderEntry("自退", Modifier.weight(1f)) { onNavigate(Route.Order("pickup_return")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrderEntry("备注", Modifier.weight(1f)) { onNavigate(Route.Note) }
                OrderEntry("收款", Modifier.weight(1f)) { onNavigate(Route.Payment("receipt")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrderEntry("退款", Modifier.weight(1f)) { onNavigate(Route.Payment("refund")) }
                OrderEntry("结算", Modifier.weight(1f)) { onNavigate(Route.Settlement) }
            }
        }
    }
}

@Composable
private fun OrderEntry(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(60.dp)) { Text(label) }
}

// ---------- 订单表单（四类共用 + 改单） ----------
private data class DraftLine(
    val key: Long,
    val categoryId: Long? = null,
    val categoryName: String = "",
    val batchType: String = "none",
    val batchTag: String = "",
    val units: List<Pair<String, Double>> = emptyList(),
    val unit: String = "",
    val quantity: String = "",
    val price: String = "",
    val priceLocked: Boolean = false,
    val available: String? = null,
    val pending: Boolean = false,
)

@Composable
private fun OrderScreen(api: CzxsApi, type: String, orderId: Long?, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val editing = orderId != null
    val isReturn = type.endsWith("_return")

    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nameLocked by remember { mutableStateOf(false) }
    var remark by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<JSONObject>()) }
    var batches by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    val lines = remember { mutableStateListOf<DraftLine>() }
    var seq by remember { mutableStateOf(1L) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<JSONObject?>(null) }
    var version by remember { mutableStateOf(1) }
    var orderNo by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(listOf<JSONObject>()) }
    var priceMode by remember { mutableStateOf("open") }
    var returnables by remember { mutableStateOf(listOf<JSONObject>()) }
    val hiddenStaff = priceMode == "hidden" && !api.isAdmin

    LaunchedEffect(Unit) {
        try {
            categories = withContext(Dispatchers.IO) { api.categories() }.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }
            if (orderId != null) {
                val order = withContext(Dispatchers.IO) { api.order(orderId) }
                phone = order.optString("customer_phone")
                name = order.optString("customer_name")
                nameLocked = true
                remark = order.optString("remark")
                version = order.optInt("version", 1)
                orderNo = order.optString("order_no")
                val items = order.optJSONArray("items") ?: JSONArray()
                lines.clear()
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val cat = categories.firstOrNull { c -> c.optLong("id") == it.optLong("category_id") }
                    lines += DraftLine(
                        key = seq++,
                        categoryId = it.optLong("category_id"),
                        categoryName = it.optString("category_name"),
                        batchType = cat?.optString("batch_type") ?: "none",
                        batchTag = it.optString("batch_tag"),
                        units = unitsOf(cat),
                        unit = it.optString("input_unit"),
                        quantity = it.optString("input_quantity"),
                        price = it.optString("unit_price"),
                        priceLocked = true,
                    )
                }
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    fun applyCustomer(c: JSONObject) {
        phone = c.optString("phone")
        name = c.optString("name")
        nameLocked = true
        priceMode = c.optString("price_mode", "open")
        matches = emptyList()
        if (isReturn) {
            scope.launch {
                try {
                    returnables = withContext(Dispatchers.IO) { api.returnableList(phone) }.let { arr ->
                        (0 until arr.length()).map { arr.getJSONObject(it) }
                    }
                } catch (e: Exception) { error = e.message }
            }
        }
    }

    fun lookupCustomer() {
        scope.launch {
            try {
                matches = emptyList()
                val q = phone.trim()
                if (q.isEmpty()) { error = "请输入电话 / 姓名 / 地址"; return@launch }
                val list = withContext(Dispatchers.IO) { api.searchCustomers(q) }
                when {
                    list.length() == 1 -> applyCustomer(list.getJSONObject(0))
                    list.length() == 0 -> {
                        nameLocked = false
                        name = ""
                        priceMode = "open"
                        returnables = emptyList()
                    }
                    else -> matches = (0 until list.length()).map { list.getJSONObject(it) }
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun refreshPrice(index: Int) {
        val line = lines.getOrNull(index) ?: return
        val catId = line.categoryId ?: return
        if (phone.isBlank() || line.batchTag.isBlank() && line.batchType != "none") return
        scope.launch {
            try {
                val price = withContext(Dispatchers.IO) { api.price(phone, catId, line.batchTag) }
                if (price != null) {
                    lines[index] = line.copy(price = price, priceLocked = true)
                } else {
                    lines[index] = line.copy(priceLocked = false)
                }
            } catch (e: Exception) {
                lines[index] = line.copy(priceLocked = false)
            }
        }
    }

    fun returnBatchesFor(catId: Long?): List<Pair<String, String>> =
        returnables.filter { it.optLong("category_id") == catId }
            .map { it.optString("batch_tag") to it.optString("available") }

    fun refreshBatches(index: Int) {
        val line = lines.getOrNull(index) ?: return
        if (!isReturn) return
        val list = returnBatchesFor(line.categoryId)
        batches = list
        if (list.isNotEmpty()) {
            lines[index] = line.copy(batchTag = list.first().first, available = list.first().second)
        }
    }

    fun submit() {
        error = null
        if (phone.isBlank() || name.isBlank()) {
            error = "请填写客户电话和姓名"
            return
        }
        if (lines.isEmpty()) {
            error = "请至少添加一行明细"
            return
        }
        lines.forEach {
            if (it.categoryId == null || it.quantity.isBlank()) {
                error = "每行都要选型号并填数量"
                return
            }
            if (it.batchType != "none" && it.batchTag.isBlank()) {
                error = "该型号必须填写批次"
                return
            }
            if (it.pending) {
                error = "请移除或替换待审核型号的明细行"
                return
            }
            if (!hiddenStaff && !it.priceLocked && it.price.isBlank()) {
                error = "该批次无价格，请录入单价"
                return
            }
        }
        loading = true
        scope.launch {
            try {
                val items = lines.map { line ->
                    JSONObject()
                        .put("category_id", line.categoryId)
                        .put("batch_tag", line.batchTag)
                        .put("input_unit", line.unit)
                        .put("input_quantity", line.quantity)
                        .put("unit_price", if (hiddenStaff) "" else line.price)
                }
                val result = withContext(Dispatchers.IO) {
                    if (orderId != null) api.updateOrder(orderId, version, remark, items)
                    else api.createOrder(type, phone, name, remark, items)
                }
                submitted = result
            } catch (e: ApiException) {
                error = "${e.code}：${e.message}"
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    PageScaffold(title = if (editing) "修改 $orderNo" else typeLabel(type), onBack = onBack) { modifier ->
        Column(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("客户")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField("客户电话", phone, { phone = it; if (it.length >= 7) Unit }, modifier = Modifier.weight(1f), numeric = true)
                OutlinedButton(onClick = ::lookupCustomer, enabled = !editing) { Text("查客户") }
            }
            matches.forEach { c ->
                OutlinedButton(onClick = { applyCustomer(c) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${c.optString("name")} / ${c.optString("phone")} / ${c.optString("addr").ifBlank { "无地址" }}")
                }
            }
            AppTextField(
                "客户姓名",
                name,
                { name = it },
                readOnly = nameLocked,
                supporting = if (nameLocked) "老客户，姓名不可改" else "新客户，提交时创建",
            )

            SectionLabel("明细")
            lines.forEachIndexed { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val pickerCats = if (isReturn)
                            categories.filter { c -> returnables.any { r -> r.optLong("category_id") == c.optLong("id") } }
                        else categories
                        CategoryPicker(pickerCats, line.categoryName) { cat ->
                            lines[index] = line.copy(
                                categoryId = cat.optLong("id"),
                                categoryName = cat.optString("name") + " " + cat.optString("spec"),
                                batchType = cat.optString("batch_type"),
                                units = unitsOf(cat),
                                unit = unitsOf(cat).firstOrNull()?.first ?: "",
                                price = "",
                                priceLocked = false,
                            )
                            refreshPrice(index)
                            refreshBatches(index)
                        }
                        if (isReturn) {
                            val opts = returnBatchesFor(line.categoryId)
                            if (opts.isNotEmpty()) {
                                BatchPicker("可退批次", opts, line.batchTag) { batch, available ->
                                    lines[index] = line.copy(batchTag = batch, available = available)
                                    refreshPrice(index)
                                }
                            }
                        }
                        if (line.batchType == "color" || line.batchType == "date") {
                            val label = if (line.batchType == "color") "色号" else "日期"
                            if (isReturn) {
                                BatchPicker(label, batches, line.batchTag) { batch, available ->
                                    lines[index] = line.copy(batchTag = batch, available = available)
                                    refreshPrice(index)
                                }
                            } else {
                                AppTextField(label, line.batchTag, {
                                    lines[index] = line.copy(batchTag = it)
                                    refreshPrice(index)
                                })
                            }
                            line.available?.let {
                                val convHint = line.units.firstOrNull { u -> u.first == line.unit }?.second ?: 1.0
                                val shown = (it.toDoubleOrNull() ?: 0.0) / (if (convHint != 0.0) convHint else 1.0)
                                Text("可退 ${trim(shown)}${line.unit}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UnitPicker(line.units, line.unit) { u -> lines[index] = line.copy(unit = u) }
                            AppTextField("数量", line.quantity, { lines[index] = line.copy(quantity = it) }, modifier = Modifier.weight(1f), numeric = true)
                        }
                        val conv = line.units.firstOrNull { it.first == line.unit }?.second ?: 1.0
                        val qty = line.quantity.toDoubleOrNull() ?: 0.0
                        val settlement = qty * conv
                        Text(
                            if (conv != 1.0) "录入 ${line.quantity}${line.unit} = 结算 ${trim(settlement)}" else "录入 ${line.quantity}${line.unit}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hiddenStaff) {
                                Text("单价：***（暗价客户，业务员不可见）", modifier = Modifier.weight(1f))
                            } else {
                                AppTextField(
                                    if (line.priceLocked) "单价（只读）" else "单价（首单录入）",
                                    line.price,
                                    { lines[index] = line.copy(price = it) },
                                    modifier = Modifier.weight(1f),
                                    numeric = true,
                                    readOnly = line.priceLocked,
                                )
                            }
                            OutlinedButton(onClick = { lines.removeAt(index) }) { Text("删除") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { lines += DraftLine(key = seq++) }, modifier = Modifier.fillMaxWidth()) { Text("+ 添加一行") }

            SectionLabel("备注")
            AppTextField("备注", remark, { remark = it })

            error?.let { ErrorText(it) }
            submitted?.let { order ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("提交成功", fontWeight = FontWeight.SemiBold)
                        KeyValueRow("单号", order.optString("order_no"))
                        KeyValueRow("整单合计", order.optString("order_total"), emphasize = true)
                        KeyValueRow("累计结算", order.optString("cumulative_total"))
                        Text("打印状态：待打印（服务端队列）", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (loading) LoadingRow("提交中…")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("返回") }
                Button(onClick = ::submit, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Text(if (editing) "保存修改" else "提交打印")
                }
            }
            if (submitted != null) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("回首页") }
            }
        }
    }
}

private fun unitsOf(cat: JSONObject?): List<Pair<String, Double>> {
    val arr = cat?.optJSONArray("units") ?: return emptyList()
    return (0 until arr.length()).map {
        val u = arr.getJSONObject(it)
        u.optString("unit") to u.optDouble("conversion", 1.0)
    }.sortedByDescending { pair -> arr.getJSONObject(0).optBoolean("is_settlement") }
}

private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun CategoryPicker(categories: List<JSONObject>, selected: String, onPick: (JSONObject) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.ifBlank { "选择型号" })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.optString("name") + " " + cat.optString("spec")) },
                    onClick = { open = false; onPick(cat) },
                )
            }
        }
    }
}

@Composable
private fun UnitPicker(units: List<Pair<String, Double>>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) { Text(selected.ifBlank { "单位" }) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            units.forEach { (unit, conv) ->
                DropdownMenuItem(
                    text = { Text(if (conv == 1.0) unit else "$unit（×$conv）") },
                    onClick = { open = false; onPick(unit) },
                )
            }
        }
    }
}

@Composable
private fun BatchPicker(label: String, batches: List<Pair<String, String>>, selected: String, onPick: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected.isBlank()) "选择$label" else "$label：$selected")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            batches.forEach { (batch, available) ->
                DropdownMenuItem(
                    text = { Text("$batch（可退 $available）") },
                    onClick = { open = false; onPick(batch, available) },
                )
            }
        }
    }
}

private fun amountLabel(type: String): String = when {
    type == "receipt" -> "已收"
    type == "refund" -> "已退"
    type.endsWith("_return") -> "应退"
    else -> "应收"
}

private fun typeLabel(type: String) = when (type) {
    "delivery" -> "配送送货单"
    "delivery_return" -> "配送退货单"
    "pickup" -> "自提送货单"
    "pickup_return" -> "自提退货单"
    else -> "修改单据"
}

// ---------- 查询 ----------
@Composable
private fun QueryScreen(api: CzxsApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    PageScaffold("查询", onBack = onBack, bottomBar = { BottomTabs(Route.Query, {}) }) { modifier ->
        Column(modifier.padding(PagePadding)) {
            AppTextField("客户电话", phone, { phone = it }, numeric = true)
            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            result = withContext(Dispatchers.IO) { api.history(phone) }
                            orders = withContext(Dispatchers.IO) { api.recent(phone, 3) }.let { arr ->
                                (0 until arr.length()).map { arr.getJSONObject(it) }
                            }
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = phone.isNotBlank() && !loading,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(if (loading) "查询中…" else "查询") }
            error?.let { ErrorText(it) }
            result?.let { data ->
                KeyValueRow("客户", data.optJSONObject("customer")?.optString("name") ?: "")
                KeyValueRow("累计结算", data.optString("settlement_total"), emphasize = true)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders.size) { index ->
                    val o = orders[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${o.optString("created_at").take(10)}  ${typeLabel(o.optString("type"))}", fontWeight = FontWeight.SemiBold)
                            Text(o.optString("order_no"), style = MaterialTheme.typography.bodySmall)
                            val items = o.optJSONArray("items") ?: JSONArray()
                            for (i in 0 until items.length()) {
                                val line = items.getJSONObject(i)
                                Text(
                                    "${line.optString("category_name")} ${line.optString("batch_tag")} ${line.optString("input_quantity")}${line.optString("input_unit")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            MoneyText(o.optString("order_total"))
                        }
                    }
                }
            }
        }
    }
}

// ---------- 改当日单 ----------
@Composable
private fun TodayScreen(api: CzxsApi, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            orders = withContext(Dispatchers.IO) { api.today() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    PageScaffold("改当日单", onBack = onBack, bottomBar = { BottomTabs(Route.Today, {}) }) { modifier ->
        Column(modifier.padding(PagePadding)) {
            error?.let { ErrorText(it) }
            if (loading) LoadingRow()
            if (!loading && orders.isEmpty()) EmptyHint("今天还没有单据")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders.size) { index ->
                    val o = orders[index]
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${o.optString("order_no")}  ${typeLabel(o.optString("type"))}", fontWeight = FontWeight.SemiBold)
                                Text(o.optString("customer_name"), style = MaterialTheme.typography.bodySmall)
                            }
                            Column {
                                MoneyText(o.optString("order_total"))
                                OutlinedButton(onClick = { onEdit(o.optLong("id")) }) { Text("修改") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- 备注（写入选中单据） ----------
@Composable
private fun NoteScreen(api: CzxsApi, onPick: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val selected = SelectedOrder.holder
    var remark by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        remark = selected?.optString("remark") ?: ""
        done = false
    }

    PageScaffold("备注", onBack = onBack) { modifier ->
        Column(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected == null) "选择要备注的单据" else "单据：${selected.optString("order_no")}")
            }
            selected?.let { order ->
                KeyValueRow("客户", "${order.optString("customer_name")} ${order.optString("customer_phone")}")
                KeyValueRow("类型", typeLabel(order.optString("type")))
                KeyValueRow("金额", order.optString("order_total"))
            }
            AppTextField("备注文字", remark, { remark = it })
            error?.let { ErrorText(it) }
            if (done) Text("备注已保存", color = MaterialTheme.colorScheme.primary)
            Button(
                onClick = {
                    val order = selected ?: run { error = "请先选择单据"; return@Button }
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.setRemark(order.optLong("id"), remark) }
                            done = true
                            error = null
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存备注") }
        }
    }
}

@Composable
private fun PickOrderScreen(api: CzxsApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }

    PageScaffold("选择单据", onBack = onBack) { modifier ->
        Column(modifier.padding(PagePadding)) {
            AppTextField("电话 / 姓名 / 地址", keyword, { keyword = it })
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val q = keyword.trim()
                            val customers = withContext(Dispatchers.IO) { api.searchCustomers(q) }
                            if (customers.length() == 0) {
                                error = "未找到该客户"
                                orders = emptyList()
                                return@launch
                            }
                            val phone = customers.getJSONObject(0).optString("phone")
                            orders = withContext(Dispatchers.IO) { api.recent(phone, 365) }.let { arr ->
                                (0 until arr.length()).map { arr.getJSONObject(it) }
                            }
                            error = if (orders.isEmpty()) "该客户暂无单据" else null
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                },
                enabled = keyword.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text("查询") }
            error?.let { ErrorText(it) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders.size) { index ->
                    val o = orders[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${o.optString("created_at").take(16).replace('T', ' ')}  ${typeLabel(o.optString("type"))}")
                            Text(o.optString("order_no"), style = MaterialTheme.typography.bodySmall)
                            Text("${o.optString("customer_name")} · ${o.optString("customer_phone")}")
                            MoneyText(o.optString("order_total"))
                            OutlinedButton(onClick = {
                                SelectedOrder.holder = o
                                onBack()
                            }) { Text("选择此单") }
                        }
                    }
                }
            }
        }
    }
}

/** 选原单结果（页面间共享）。 */
object SelectedOrder {
    var holder: JSONObject? = null
}


// ---------- 收款 / 退款 ----------
private data class PayRow(val method: String, val amount: String)

@Composable
private fun PaymentScreen(api: CzxsApi, type: String, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isRefund = type == "refund"
    var keyword by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(listOf<JSONObject>()) }
    var customer by remember { mutableStateOf<JSONObject?>(null) }
    var refundable by remember { mutableStateOf("") }
    var outstanding by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf(PayRow("现金", "")) }
    var discount by remember { mutableStateOf("") }
    var discountType by remember { mutableStateOf("") }
    var methods by remember { mutableStateOf(listOf("其他")) }
    var discountTypes by remember { mutableStateOf(emptyList<String>()) }
    var openRow by remember { mutableStateOf(-1) }
    var openDiscount by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                methods = api.presets("payment_methods", listOf("其他"))
                discountTypes = api.presets("discount_types", emptyList())
            }
        }
        if (rows.isNotEmpty() && methods.isNotEmpty()) rows[0] = rows[0].copy(method = methods.first())
    }

    val total = rows.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }

    fun pickCustomer(c: JSONObject) {
        customer = c
        matches = emptyList()
        keyword = c.optString("phone")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isRefund) refundable = api.refundable(c.optString("phone")) else outstanding = api.outstanding(c.optString("phone"))
                }
            } catch (e: Exception) { error = e.message }
        }
    }

    PageScaffold(if (isRefund) "退款" else "收款", onBack = onBack) { modifier ->
        Column(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField("电话 / 姓名 / 地址", keyword, { keyword = it })
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) { api.searchCustomers(keyword.trim()) }
                            if (list.length() == 1) pickCustomer(list.getJSONObject(0))
                            else if (list.length() == 0) error = "未找到客户" else {
                                matches = (0 until list.length()).map { list.getJSONObject(it) }
                            }
                        } catch (e: Exception) { error = e.message }
                    }
                },
                enabled = keyword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查客户") }

            matches.forEach { c ->
                OutlinedButton(onClick = { pickCustomer(c) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${c.optString("name")} / ${c.optString("phone")} / ${c.optString("addr").ifBlank { "无地址" }}")
                }
            }

            customer?.let { c ->
                KeyValueRow("客户", "${c.optString("name")} ${c.optString("phone")}")
                if (isRefund) KeyValueRow("可退金额", refundable) else KeyValueRow("应收金额", outstanding)
            }

            SectionLabel("付款方式")
            rows.forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { openRow = index }) { Text(row.method) }
                    DropdownMenu(expanded = openRow == index, onDismissRequest = { openRow = -1 }) {
                        methods.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { openRow = -1; rows[index] = row.copy(method = m) })
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        AppTextField("金额", row.amount, { v -> rows[index] = row.copy(amount = v) }, numeric = true)
                    }
                    OutlinedButton(onClick = { rows.removeAt(index) }) { Text("删") }
                }
            }
            OutlinedButton(onClick = { rows.add(PayRow(methods.firstOrNull() ?: "现金", "")) }, modifier = Modifier.fillMaxWidth()) { Text("+ 添加付款方式") }
            KeyValueRow("实收合计", String.format("%.2f", total))

            if (!isRefund) {
                if (discountTypes.isNotEmpty()) {
                    OutlinedButton(onClick = { openDiscount = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (discountType.isBlank()) "优惠方式（可选）" else "优惠方式：$discountType")
                    }
                    DropdownMenu(expanded = openDiscount, onDismissRequest = { openDiscount = false }) {
                        discountTypes.forEach { d ->
                            DropdownMenuItem(text = { Text(d) }, onClick = { openDiscount = false; discountType = d })
                        }
                    }
                }
                AppTextField("优惠金额", discount, { discount = it }, numeric = true)
            }

            error?.let { ErrorText(it) }
            done?.let {
                Text("已生成 ${it.optString("order_no")}", color = MaterialTheme.colorScheme.primary)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
            Button(
                onClick = {
                    val c = customer ?: run { error = "请先查客户"; return@Button }
                    scope.launch {
                        try {
                            val payments = rows.map { JSONObject().put("method", it.method).put("amount", it.amount) }
                            val out = withContext(Dispatchers.IO) {
                                api.createPayment(
                                    type = type,
                                    phone = c.optString("phone"),
                                    name = c.optString("name"),
                                    address = c.optString("addr"),
                                    payments = payments,
                                    discount = if (isRefund) "" else discount,
                                    discountType = if (isRefund) "" else discountType,
                                )
                            }
                            done = out
                            error = null
                        } catch (e: Exception) { error = e.message }
                    }
                },
                enabled = done == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isRefund) "提交退款" else "提交收款") }
        }
    }
}

// ---------- 结算 ----------
@Composable
private fun SettlementScreen(api: CzxsApi, onBack: () -> Unit) {
    var list by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            list = withContext(Dispatchers.IO) { api.settlement() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        } catch (e: Exception) { error = e.message }
    }

    PageScaffold("结算", onBack = onBack) { modifier ->
        LazyColumn(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { error?.let { ErrorText(it) } }
            items(list.size) { index ->
                val r = list[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(r.optString("name"), fontWeight = FontWeight.SemiBold)
                        Text(r.optString("phone"), style = MaterialTheme.typography.bodySmall)
                        Text("累计结算：${r.optString("total")}")
                        OutlinedButton(onClick = {
                            scope.launch {
                                try {
                                    detail = withContext(Dispatchers.IO) { api.settlementDetail(r.optLong("id")) }
                                } catch (e: Exception) { error = e.message }
                            }
                        }) { Text("详情") }
                    }
                }
            }
        }
    }

    detail?.let { d ->
        val orders = d.optJSONArray("orders") ?: JSONArray()
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            title = { Text("结算详情 · ${d.optJSONObject("customer")?.optString("name") ?: ""}") },
            text = {
                Column {
                    KeyValueRow("送货合计", d.optString("receivable"))
                    KeyValueRow("退货合计", d.optString("payable"))
                    KeyValueRow("收款合计", d.optString("received"))
                    KeyValueRow("退款合计", d.optString("refunded"))
                    KeyValueRow("优惠合计", d.optString("discount"))
                    val out = d.optString("outstanding", "0").toDoubleOrNull() ?: 0.0
                    KeyValueRow("结余", if (out > 0) "现应收 $out" else if (out < 0) "现应退 ${-out}" else "已结清")
                    LazyColumn(Modifier.height(220.dp)) {
                        items(orders.length()) { i ->
                            val o = orders.getJSONObject(i)
                            Text("${o.optString("order_no")}  ${typeLabel(o.optString("type"))}  ${o.optString("order_total")}")
                        }
                    }
                }
            },
        )
    }
}


// ---------- 单据库 ----------
@Composable
private fun OrderDetailDialog(
    order: JSONObject,
    isAdmin: Boolean,
    onClose: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val type = order.optString("type")
    val editable = isAdmin && (type == "delivery" || type == "delivery_return" || type == "pickup" || type == "pickup_return") &&
        order.optString("status") != "archived"
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editable) TextButton(onClick = { onEdit(order.optLong("id")); onClose() }) { Text("编辑") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        },
        title = { Text("${order.optString("order_no")} · ${typeLabel(type)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyValueRow("客户", "${order.optString("customer_name")}--${order.optString("customer_phone")}--${order.optString("customer_address").ifBlank { "无" }}")
                KeyValueRow("时间", order.optString("created_at").take(16).replace('T', ' '))
                KeyValueRow("状态", order.optString("status"))
                KeyValueRow("备注", order.optString("remark").ifBlank { "（空）" })
                if (order.optString("discount_type").isNotBlank() || order.optString("discount").toDoubleOrNull() ?: 0.0 > 0.0) {
                    KeyValueRow("优惠", "${order.optString("discount_type")} ${order.optString("discount")}")
                }
                KeyValueRow(amountLabel(type), order.optString("order_total"), emphasize = true)

                val linesArr = order.optJSONArray("items") ?: JSONArray()
                if (linesArr.length() > 0) {
                    SectionLabel("明细")
                    LazyColumn(Modifier.height(180.dp)) {
                        items(linesArr.length()) { i ->
                            val line = linesArr.getJSONObject(i)
                            Text(
                                "${i + 1}. ${line.optString("name")} ${line.optString("batch_tag")} " +
                                    "${line.optString("input_quantity")}${line.optString("input_unit")} × ${line.optString("unit_price")} = ${line.optString("line_total")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                val payments = order.optJSONArray("payments") ?: JSONArray()
                if (payments.length() > 0) {
                    SectionLabel("付款方式")
                    for (i in 0 until payments.length()) {
                        val pay = payments.getJSONObject(i)
                        Text("${pay.optString("method")}　${pay.optString("amount")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun OrdersScreen(api: CzxsApi, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        try {
            orders = withContext(Dispatchers.IO) { api.ordersAll() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    PageScaffold("单据库", onBack = onBack) { modifier ->
        Column(modifier.padding(PagePadding)) {
            error?.let { ErrorText(it) }
            if (loading) LoadingRow()
            if (!loading && orders.isEmpty()) EmptyHint("暂无单据")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders.size) { index ->
                    val o = orders[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("${o.optString("order_no")}  ${typeLabel(o.optString("type"))}", fontWeight = FontWeight.SemiBold)
                                Text("${amountLabel(o.optString("type"))} ${o.optString("order_total")}")
                            }
                            Text(
                                "${o.optString("customer_name")}--${o.optString("customer_phone")}--${o.optString("customer_address").ifBlank { "无" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                OutlinedButton(onClick = { detail = o }) { Text("详情") }
                                val type = o.optString("type")
                                if (api.isAdmin && o.optString("status") != "archived" &&
                                    (type == "delivery" || type == "delivery_return" || type == "pickup" || type == "pickup_return")
                                ) {
                                    OutlinedButton(onClick = { onEdit(o.optLong("id")) }) { Text("编辑") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { o ->
        OrderDetailDialog(order = o, isAdmin = api.isAdmin, onClose = { detail = null }, onEdit = onEdit)
    }
}

// ---------- 设置 ----------
@Composable
private fun SettingsScreen(api: CzxsApi, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    var server by remember { mutableStateOf(api.baseUrl) }
    var print by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        print = runCatching { withContext(Dispatchers.IO) { api.printStatus() } }.getOrNull()
    }

    PageScaffold("设置", onBack = onBack, bottomBar = { BottomTabs(Route.Settings, {}) }) { modifier ->
        Column(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyValueRow("当前账号", api.accountName)
            KeyValueRow("服务端", api.baseUrl + GATEWAY_PREFIX)
            KeyValueRow("打印机", if (print?.optBoolean("printer_online") == true) "在线" else "离线")
            KeyValueRow("待打印", print?.optString("pending") ?: "0")
            SectionLabel("NAS 域名")
            AppTextField("域名", server, { server = it })
            Button(onClick = {
                runCatching { api.saveServer(server) }
                    .onSuccess { message = "已保存" }
                    .onFailure { message = "格式不对：${it.message}" }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存地址") }
            message?.let { Text(it) }
            OutlinedButton(onClick = { api.logout(); onLoggedOut() }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        }
    }
}
