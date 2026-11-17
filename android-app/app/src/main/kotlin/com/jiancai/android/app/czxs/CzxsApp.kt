@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jiancai.android.app.czxs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
    data object PrintJobs : Route
    data object Categories : Route
    data object Settings : Route
}

private val TABS = listOf("单据" to Route.Orders, "查询" to Route.Query, "改当日单" to Route.Today, "结算" to Route.Settlement, "设置" to Route.Settings)

/** czxs 客户端根：登录守卫 + 底栏 + 页面栈（单 Activity，状态驱动）。 */
@Composable
fun CzxsApp(api: CzxsApi) {
    val stack = remember { mutableStateListOf<Route>(if (api.token.isNullOrBlank()) Route.Login else Route.Home) }
    val route = stack.last()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 登录态失效：清空页面栈，统一回登录页
    LaunchedEffect(Unit) {
        api.onUnauthorized = {
            scope.launch {
                drawerState.close()
                stack.clear()
                stack.add(Route.Login)
            }
        }
        if (!api.token.isNullOrBlank() && stack.last() != Route.Login) {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.today() } }
        }
    }

    val topLevel = route == Route.Home || route == Route.Orders || route == Route.PrintJobs ||
        route == Route.Categories || route == Route.Settlement || route == Route.Settings

    fun goBack() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        // 不变量：只要停在首页，栈里就只有首页，再按返回即退出
        if (stack.lastOrNull() == Route.Home && stack.size > 1) {
            stack.clear()
            stack.add(Route.Home)
        }
    }

    fun navigate(r: Route) {
        if (r == Route.Home) {
            stack.clear()
            stack.add(Route.Home)
            return
        }
        if (stack.lastOrNull() != r) stack.add(r)
    }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen || stack.size > 1) {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else goBack()
    }

    if (route == Route.Login) {
        LoginScreen(api) { stack.clear(); stack.add(Route.Home) }
        return
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = topLevel,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.5f).dp),
            ) {
                CzxsDrawerContent(
                    api = api,
                    current = route,
                    onNavigate = {
                        // 抽屉切换视为“从首页进入”：重置为 首页→目标，保证返回最终停在首页且首页返回可退出
                        stack.clear()
                        if (it != Route.Home) stack.add(Route.Home)
                        stack.add(it)
                        scope.launch { drawerState.close() }
                    },
                    onLogout = {
                        api.logout()
                        stack.clear(); stack.add(Route.Login)
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        val current = route
        when (current) {
            Route.Home -> HomeScreen(api, onMenu = openDrawer, onSearch = { navigate(Route.Query) }, onNavigate = { navigate(it) })
            Route.Orders -> OrdersScreen(api, onMenu = openDrawer, onSearch = { navigate(Route.Query) }, onBack = ::goBack) { id -> navigate(Route.Order("edit", id)) }
            Route.PrintJobs -> PrintScreen(api, onMenu = openDrawer, onSearch = { navigate(Route.Query) })
            Route.Categories -> CategoriesScreen(api, onMenu = openDrawer, onSearch = { navigate(Route.Query) })
            Route.Settlement -> SettlementScreen(api, onMenu = openDrawer, onSearch = { navigate(Route.Query) })
            Route.Settings -> SettingsScreen(api, onMenu = openDrawer, onBack = ::goBack) { stack.clear(); stack.add(Route.Login) }
            Route.Query -> QueryScreen(api, onBack = ::goBack)
            Route.Today -> TodayScreen(api, onBack = ::goBack) { id -> navigate(Route.Order("edit", id)) }
            Route.Note -> NoteScreen(api, onPick = { navigate(Route.PickOrder) }, onBack = ::goBack)
            Route.PickOrder -> PickOrderScreen(api, onBack = ::goBack)
            is Route.Order -> OrderScreen(api, current.type, current.orderId, onBack = ::goBack) { stack.clear(); stack.add(Route.Home) }
            is Route.Payment -> PaymentScreen(api, current.type, onBack = ::goBack) { stack.clear(); stack.add(Route.Home) }
            Route.Login -> Unit
        }
    }
}

@Composable
private fun CzxsDrawerContent(api: CzxsApi, current: Route, onNavigate: (Route) -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("瓷砖店销售系统", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("${api.accountName.ifBlank { "未登录" }}（${if (api.isAdmin) "管理员" else "业务员"}）", style = MaterialTheme.typography.bodySmall)
        Text(api.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider()
    val items = listOf(
        "首页" to Route.Home,
        "单据库" to Route.Orders,
        "打印" to Route.PrintJobs,
        "型号库" to Route.Categories,
        "结算" to Route.Settlement,
    )
    items.forEach { (label, r) ->
        NavigationDrawerItem(
            label = { Text(label) },
            selected = current == r,
            onClick = { onNavigate(r) },
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    NavigationDrawerItem(
        label = { Text("⚙ 设置") },
        selected = current == Route.Settings,
        onClick = { onNavigate(Route.Settings) },
        modifier = Modifier.padding(horizontal = 10.dp),
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    NavigationDrawerItem(
        label = { Text("🚪 退出账号") },
        selected = false,
        onClick = onLogout,
        modifier = Modifier.padding(horizontal = 10.dp),
    )
}

/** 统一顶栏：左侧 ☰、右侧 🔍（不强制滚动，页面自己决定 LazyColumn 或滚动 Column）。 */
@Composable
private fun AppScaffold(
    title: String,
    onMenu: () -> Unit,
    onSearch: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰", style = MaterialTheme.typography.titleLarge) } },
                actions = {
                    if (onSearch != null) IconButton(onClick = onSearch) { Text("🔍", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { inner -> content(inner) }
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
    val saved = api.baseUrl
    var https by remember { mutableStateOf(saved.startsWith("https://")) }
    var host by remember { mutableStateOf(saved.removePrefix("https://").removePrefix("http://").substringBefore(":").substringBefore("/")) }
    var port by remember { mutableStateOf(saved.removePrefix("https://").removePrefix("http://").substringAfter(":", "9527").substringBefore("/")) }
    var entry by remember { mutableStateOf(api.securityEntry) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    PageScaffold("登录") { modifier ->
        Column(modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("瓷砖店销售系统", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.Switch(checked = https, onCheckedChange = { https = it })
                Text("HTTPS", style = MaterialTheme.typography.bodySmall)
            }
            CompactInput(host, { host = it }, "IP / 域名")
            CompactInput(port, { port = it }, "端口")
            CompactInput(entry, { entry = it }, "安全入口")
            CompactInput(username, { username = it }, "账号")
            CompactInput(password, { password = it }, "密码")
            error?.let { ErrorText(it) }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val scheme = if (https) "https" else "http"
                            api.setSecurityEntry(entry)
                            runCatching { api.saveServer("$scheme://${host.trim()}:${port.trim()}") }.getOrElse {
                                throw ApiException("SERVER_INVALID", "地址或端口格式不对")
                            }
                            withContext(Dispatchers.IO) { api.login(username.trim(), password) }
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
                enabled = !loading && host.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "登录中…" else "登 录") }
        }
    }
}

// ---------- 首页 ----------
@Composable
private fun HomeScreen(api: CzxsApi, onMenu: () -> Unit, onSearch: () -> Unit, onNavigate: (Route) -> Unit) {
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            orders = withContext(Dispatchers.IO) { api.today() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    AppScaffold("首页", onMenu = onMenu, onSearch = onSearch) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("店员：${api.accountName.ifBlank { "未登录" }}", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OrderEntry("配送", Modifier.weight(1f)) { onNavigate(Route.Order("delivery")) }
                                OrderEntry("配退", Modifier.weight(1f)) { onNavigate(Route.Order("delivery_return")) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OrderEntry("自送", Modifier.weight(1f)) { onNavigate(Route.Order("pickup")) }
                                OrderEntry("自退", Modifier.weight(1f)) { onNavigate(Route.Order("pickup_return")) }
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }

            item { SectionLabel("今日单据（${orders.size}）") }
            error?.let { msg -> item { ErrorText(msg) } }
            if (loading) item { LoadingRow() }
            if (!loading && orders.isEmpty() && error == null) item { EmptyHint("今天还没有单据") }

            items(orders.size) { index ->
                val o = orders[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${o.optString("order_no")}  ${typeLabel(o.optString("type"))}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${o.optString("customer_name")}-${o.optString("customer_phone")}-${o.optString("customer_address").ifBlank { "无" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("${amountLabel(o.optString("type"))} ${o.optString("order_total")}")
                            OutlinedButton(onClick = { detail = o }) { Text("详情") }
                        }
                    }
                }
            }
        }
    }

    detail?.let { o ->
        OrderDetailDialog(
            api = api,
            order = o,
            isAdmin = api.isAdmin,
            onClose = { detail = null },
            onEdit = { id -> detail = null; onNavigate(Route.Order("edit", id)) },
            onArchive = { id ->
                scope.launch {
                    try { withContext(Dispatchers.IO) { api.archiveOrder(id) }; orders = orders.map { if (it.optLong("id") == id) JSONObject(it.toString()).put("status", "archived") else it } }
                    catch (e: Exception) { error = e.message }
                }
            },
            onDelete = { id ->
                scope.launch {
                    try { withContext(Dispatchers.IO) { api.deleteOrder(id) }; orders = orders.filterNot { it.optLong("id") == id } }
                    catch (e: Exception) { error = e.message }
                }
            },
        )
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
    var address by remember { mutableStateOf("") }
    var nameLocked by remember { mutableStateOf(false) }
    var remark by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<JSONObject>()) }
    var batches by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    val lines = remember { mutableStateListOf<DraftLine>() }
    var seq by remember { mutableStateOf(1L) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<JSONObject?>(null) }
    var media by remember { mutableStateOf(listOf<JSONObject>()) }
    var viewing by remember { mutableStateOf<JSONObject?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var showTray by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var traySheet by remember { mutableStateOf(false) }

    fun reloadMedia(oid: Long) {
        if (oid <= 0) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.orderMedia(oid) }.let { arr ->
                    (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        JSONObject(m.toString()).put("url", api.mediaUrl(m.optLong("id")))
                    }
                }
            }.onSuccess { media = it }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(orderId) { orderId?.let { reloadMedia(it) } }

    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun uploadFiles(files: List<Triple<String, String, ByteArray>>) {
        if (files.isEmpty()) return
        val oid = orderId ?: run {
            error = "新单据需先提交生成单号后才能上传图片/视频"
            return
        }
        uploading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { files.forEach { (n, m, b) -> api.uploadMedia(oid, n, m, b) } }
                reloadMedia(oid)
            } catch (e: Exception) {
                error = e.message
            } finally {
                uploading = false
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            uploadFiles(listOf(Triple("camera-${System.currentTimeMillis()}.jpg", "image/jpeg", bytes)))
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            uploading = true
            scope.launch {
                try {
                    val files = withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                            val name = queryDisplayName(ctx, uri) ?: ("media-" + System.currentTimeMillis())
                            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (bytes == null || bytes.size > 200 * 1024 * 1024) null else Triple(name, mime, bytes)
                        }
                    }
                    uploadFiles(files)
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    uploading = false
                }
            }
        }
    }
    var version by remember { mutableStateOf(1) }
    var orderNo by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(listOf<JSONObject>()) }
    var priceMode by remember { mutableStateOf("open") }
    var returnables by remember { mutableStateOf(listOf<JSONObject>()) }
    var pickerFor by remember { mutableStateOf(-1) }
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
                address = order.optString("customer_address")
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
                        categoryName = it.optString("name"),
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
        address = c.optString("addr")
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
                    else api.createOrder(type, phone, name, address, remark, items)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CompactInput(phone, { phone = it }, "电话 / 姓名 / 地址", Modifier.weight(1f), readOnly = editing)
                OutlinedButton(onClick = ::lookupCustomer, enabled = !editing) { Text("查客户") }
            }
            matches.forEach { c ->
                OutlinedButton(onClick = { applyCustomer(c) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${c.optString("name")} / ${c.optString("phone")} / ${c.optString("addr").ifBlank { "无地址" }}")
                }
            }
            CompactInput(address, { address = it }, "客户地址", readOnly = editing)
            CompactInput(
                name,
                { name = it },
                "客户姓名",
                readOnly = nameLocked,
                readOnlyHint = if (nameLocked) "老客户，姓名不可改" else "新客户，提交时创建",
            )

            SectionLabel("明细")
            lines.forEachIndexed { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("[${index + 1}]", fontWeight = FontWeight.SemiBold)
                            OutlinedButton(
                                onClick = { pickerFor = index },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    (line.categoryName.ifBlank { "选择商品" }) +
                                        (if (line.batchTag.isNotBlank()) " · ${line.batchTag}" else ""),
                                    maxLines = 1,
                                )
                            }
                            OutlinedButton(
                                onClick = { lines.removeAt(index) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) { Text("删") }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            UnitPicker(line.units, line.unit) { u -> lines[index] = line.copy(unit = u) }
                            Box(Modifier.weight(1f)) {
                                Box(
                                    Modifier
                                        .height(34.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                                ) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = line.quantity,
                                        onValueChange = { lines[index] = line.copy(quantity = it) },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        decorationBox = { inner ->
                                            if (line.quantity.isEmpty()) Text("数量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                            inner()
                                        },
                                    )
                                }
                            }
                            Box(Modifier.weight(1.2f)) {
                                if (hiddenStaff) {
                                    Text("***", Modifier.padding(6.dp))
                                } else {
                                    Box(
                                        Modifier
                                            .height(34.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                                    ) {
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = line.price,
                                            onValueChange = { lines[index] = line.copy(price = it) },
                                            singleLine = true,
                                            readOnly = line.priceLocked,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                            decorationBox = { inner ->
                                                if (line.price.isEmpty()) Text("单价", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                                inner()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (pickerFor >= 0) {
                val idx = pickerFor
                val line = lines.getOrNull(idx)
                if (line != null) {
                    val pickerCats = if (isReturn)
                        categories.filter { c -> returnables.any { r -> r.optLong("category_id") == c.optLong("id") } }
                    else categories.filter { it.optString("status", "active") == "active" }
                    CategoryPicker(
                        categories = pickerCats,
                        selectedLabel = line.categoryName,
                        onCancel = { pickerFor = -1 },
                        batchesFor = { catId ->
                            if (isReturn) {
                                returnBatchesFor(catId)
                            } else {
                                categories.firstOrNull { it.optLong("id") == catId }
                                    ?.let { listOf(it.optString("batch") to "") } ?: emptyList()
                            }
                        },
                    ) { cat, batch ->
                        val avail = if (isReturn) returnBatchesFor(cat.optLong("id")).firstOrNull { it.first == batch }?.second else null
                        lines[idx] = line.copy(
                            categoryId = cat.optLong("id"),
                            categoryName = cat.optString("name"),
                            batchType = cat.optString("batch_type"),
                            batchTag = batch,
                            units = unitsOf(cat),
                            unit = unitsOf(cat).firstOrNull()?.first ?: "",
                            price = "",
                            priceLocked = false,
                            available = avail,
                        )
                        refreshPrice(idx)
                        pickerFor = -1
                    }
                }
            }
            OutlinedButton(onClick = { lines += DraftLine(key = seq++) }, modifier = Modifier.fillMaxWidth()) { Text("添加商品") }

            SectionLabel("备注")
            AppTextField("备注", remark, { remark = it })
            if (editing) {
                SectionLabel("图片 / 视频")
                MediaStrip(
                    items = media,
                    uploading = uploading,
                    onAdd = { showTray = true },
                    onOpen = { viewing = it },
                    onDelete = { m ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { api.deleteMedia(m.optLong("id")) }
                                orderId?.let { reloadMedia(it) }
                            } catch (e: Exception) { error = e.message }
                        }
                    },
                )
            }

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
            viewing?.let { m ->
                MediaViewerDialog(url = m.optString("url"), kind = m.optString("kind")) { viewing = null }
            }
            if (showTray) {
                AlertDialog(
                    onDismissRequest = { showTray = false },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showTray = false }) { Text("取消") } },
                    title = { Text("添加图片 / 视频") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { showTray = false; pickMedia.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                                Text("从相册选择（可多选）")
                            }
                            OutlinedButton(onClick = { showTray = false; showCamera = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("打开相机（拍照 / 录像）")
                            }
                        }
                    },
                )
            }
            if (showCamera) {
                Dialog(
                    onDismissRequest = { showCamera = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    CameraCaptureScreen(
                        onClose = { showCamera = false },
                        onCaptured = { n, m, b ->
                            showCamera = false
                            uploadFiles(listOf(Triple(n, m, b)))
                        },
                    )
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

@Composable
private fun TableHead(text: String, width: Int) {
    Text(
        text,
        Modifier.width(width.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}


/** 照片上传前压缩：长边压到 maxLong（默认 1920），JPEG 质量 70；小于 1280 不放大。 */
private fun compressJpeg(bytes: ByteArray, maxLong: Int = 1920, quality: Int = 70): ByteArray {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLong) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
    val longSide = maxOf(decoded.width, decoded.height)
    val scaled = if (longSide > maxLong) {
        val ratio = maxLong.toFloat() / longSide
        android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt(), (decoded.height * ratio).toInt(), true)
    } else decoded
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

private fun queryDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? {
    return runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

private fun unitsOf(cat: JSONObject?): List<Pair<String, Double>> {
    if (cat == null) return emptyList()
    val out = mutableListOf<Pair<String, Double>>()
    val settle = cat.optString("settlement_unit")
    val convUnit = cat.optString("conversion_unit")
    val conv = cat.optDouble("conversion", 1.0)
    if (settle.isNotBlank()) out += settle to 1.0
    if (convUnit.isNotBlank() && convUnit != settle) out += convUnit to if (conv > 0) conv else 1.0
    return out
}


private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun Wheel(
    options: List<String>,
    index: Int,
    onIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemHeight = 40.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemPx = with(density) { itemHeight.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceAtLeast(0))
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(lazyListState = state)

    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress && options.isNotEmpty()) {
            val center = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            onIndex(center.coerceIn(0, options.lastIndex))
        }
    }
    LaunchedEffect(index) {
        if (!state.isScrollInProgress && state.firstVisibleItemIndex != index && index >= 0) {
            state.scrollToItem(index)
        }
    }

    Box(modifier.height(itemHeight * 5)) {
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = itemHeight * 2),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(options) { i, label ->
                Box(Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun CategoryPicker(
    categories: List<JSONObject>,
    selectedLabel: String,
    batchesFor: (Long) -> List<Pair<String, String>>,
    onCancel: () -> Unit = {},
    onPick: (JSONObject, String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }

    val rows = remember(categories) {
        categories.flatMap { c ->
            val bs = batchesFor(c.optLong("id")).map { it.first }.ifEmpty { listOf(c.optString("batch")) }
            bs.distinct().map { b -> c to b }
        }
    }
    val filtered = rows.filter { (c, b) ->
        query.isBlank() || listOf(c.optString("group"), c.optString("name"), b)
            .any { it.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("关闭") } },
        title = { Text("选择商品") },
        text = {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("品类 / 型号 / 批号", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { query = input.trim() }, modifier = Modifier.padding(start = 6.dp)) { Text("搜索") }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text("无匹配型号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    LazyColumn(Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered.size) { i ->
                            val (c, b) = filtered[i]
                            val tint = cardTints[i % cardTints.size]
                            Card(
                                onClick = { pending = c to b },
                                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = tint.bg),
                                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    listOf(c.optString("group"), c.optString("name"), b.ifBlank { "无批号" }).joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tint.title,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )

    pending?.let { (c, b) ->
        val title = listOf(c.optString("group"), c.optString("name"), b.ifBlank { "无批号" }).joinToString(" · ")
        AlertDialog(
            onDismissRequest = { pending = null },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onPick(c, b)
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
            title = { Text("确认选择") },
            text = { Text("是否确认选择「$title」？") },
        )
    }
}

@Composable
private fun CompactInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    readOnlyHint: String = "",
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                readOnly = readOnly,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    inner()
                },
            )
        }
        if (readOnlyHint.isNotBlank()) {
            Text(readOnlyHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}





@Composable
private fun FullscreenDialogEffect() {
    val rootView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val dialogWindow = (rootView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        dialogWindow?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
        )
        dialogWindow?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        val ctrl = dialogWindow?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, rootView) }
        ctrl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        ctrl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { ctrl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun RemoteVideoThumb(url: String, modifier: Modifier) {
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(url, emptyMap<String, String>())
                val frame = retriever.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            }.getOrNull()
        }
    }
    val image = bmp
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(androidx.compose.ui.graphics.Color(0xFFE3E7EF)))
    }
}

@Composable
private fun RemoteImage(url: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    val image = bmp
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(androidx.compose.ui.graphics.Color(0xFFE5E7EB)))
    }
}


/** CameraX 拍摄页：预览 + 短按照片 / 长按录视频（720p，1.2Mbps），后摄，补光灯多模式。 */
@Composable
private fun CameraCaptureScreen(
    onClose: () -> Unit,
    onCaptured: (String, String, ByteArray) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var granted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[android.Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(
            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        )
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(24.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("需要相机权限", color = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClose) { Text("返回") }
        }
        return
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<androidx.camera.view.PreviewView?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var recording by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(0) } // 0 关 1 开 2 自动
    var error by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf<Triple<String, String, java.io.File>?>(null) }
    val scope = rememberCoroutineScope()
    val mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(ctx)

    val imageCapture = remember {
        androidx.camera.core.ImageCapture.Builder()
            .setCaptureMode(androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val recorder = remember {
        androidx.camera.video.Recorder.Builder()
            .setQualitySelector(
                androidx.camera.video.QualitySelector.from(
                    androidx.camera.video.Quality.HD,
                    androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(androidx.camera.video.Quality.HD),
                )
            )
            .setTargetVideoEncodingBitRate(1_200_000)
            .build()
    }
    val videoCapture = remember { androidx.camera.video.VideoCapture.withOutput(recorder) }
    var activeRecording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }

    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        try {
            val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx).get()
            val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture, videoCapture,
            )
        } catch (e: Exception) {
            error = "相机启动失败：${e.message}"
        }
    }

    fun photo() {
        val f = java.io.File(ctx.cacheDir, "cap-${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            androidx.camera.core.ImageCapture.OutputFileOptions.Builder(f).build(),
            mainExecutor,
            object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: androidx.camera.core.ImageCapture.OutputFileResults) {
                    captured = Triple(f.name, "image/jpeg", f)
                }

                override fun onError(e: androidx.camera.core.ImageCaptureException) {
                    error = e.message
                }
            },
        )
    }

    fun startVideo() {
        if (recording) return
        val f = java.io.File(ctx.cacheDir, "rec-${System.currentTimeMillis()}.mp4")
        val pending = recorder.prepareRecording(ctx, androidx.camera.video.FileOutputOptions.Builder(f).build())
        val withAudio = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val p2 = try {
            if (withAudio) pending.withAudioEnabled() else pending
        } catch (se: SecurityException) {
            error = "录音权限被拒，录像无声"
            pending
        }
        activeRecording = p2.start(mainExecutor, object : androidx.core.util.Consumer<androidx.camera.video.VideoRecordEvent> {
            override fun accept(event: androidx.camera.video.VideoRecordEvent) {
                if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                    recording = false
                    if (!event.hasError()) captured = Triple(f.name, "video/mp4", f) else error = "录制失败：${event.error}"
                }
            }
        })
        recording = true
    }

    fun stopVideo() {
        activeRecording?.stop()
        activeRecording = null
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(
            factory = { c -> androidx.camera.view.PreviewView(c).apply { implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE } },
            update = { previewView = it },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层：短按照片 / 长按录制（松手停止）
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val started = System.currentTimeMillis()
                    var longFired = false
                    val hold = scope.launch {
                        kotlinx.coroutines.delay(420)
                        longFired = true
                        startVideo()
                    }
                    val released = tryAwaitRelease()
                    hold.cancel()
                    val duration = System.currentTimeMillis() - started
                    if (longFired || duration >= 420) {
                        if (recording) stopVideo()
                    } else if (released) {
                        photo()
                    }
                })
            }
        )

        // 左上：退出
        Box(
            Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(18.dp)
                .size(38.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text("✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp) }

        // 左下：闪光灯切换
        Box(
            Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(start = 22.dp, bottom = 46.dp)
                .size(46.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)
                .clickable {
                    torch = (torch + 1) % 3
                    runCatching { camera?.cameraControl?.enableTorch(torch == 1) }
                },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                when (torch) { 0 -> "⚡"; 1 -> "⚡"; else -> "A" },
                color = if (torch == 1) androidx.compose.ui.graphics.Color(0xFFFFD54F) else androidx.compose.ui.graphics.Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        // 闪光灯状态文字
        Text(
            when (torch) { 0 -> "闪光灯 关"; 1 -> "闪光灯 开"; else -> "闪光灯 自动" },
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(start = 22.dp, bottom = 24.dp),
        )

        // 底部中间：拍摄按钮（点击拍照 / 按住录像）
        Box(
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 30.dp)
                .size(if (recording) 84.dp else 76.dp)
                .border(4.dp, androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.CircleShape)
                .padding(6.dp)
                .background(
                    if (recording) androidx.compose.ui.graphics.Color(0xFFE53935) else androidx.compose.ui.graphics.Color.White,
                    androidx.compose.foundation.shape.CircleShape,
                )
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val started = System.currentTimeMillis()
                        var longFired = false
                        val hold = scope.launch {
                            kotlinx.coroutines.delay(420)
                            longFired = true
                            startVideo()
                        }
                        val released = tryAwaitRelease()
                        hold.cancel()
                        val duration = System.currentTimeMillis() - started
                        if (longFired || duration >= 420) {
                            if (recording) stopVideo()
                        } else if (released) {
                            photo()
                        }
                    })
                },
        )

        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Text(
                "麦克风未授权：录像将无声（点此授权）",
                color = androidx.compose.ui.graphics.Color(0xFFFFD54F),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 150.dp)
                    .clickable { permLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO)) },
            )
        }
        if (recording) {
            Text(
                "● 录制中，松手结束",
                color = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 128.dp),
            )
        }
        error?.let {
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B), modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
        }
    }

    // 预览阶段：取消丢弃 / 完成上传
    val shot = captured
    if (shot != null) {
        val (name, mime, file) = shot
        Dialog(
            onDismissRequest = { runCatching { file.delete() }; captured = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            FullscreenDialogEffect()
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                if (mime == "video/mp4") {
                    AndroidView(
                        factory = { c ->
                            android.widget.VideoView(c).apply {
                                setVideoURI(android.net.Uri.fromFile(file))
                                setOnPreparedListener { it.isLooping = true }
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val bmp = remember(file.path) {
                        android.graphics.BitmapFactory.decodeFile(file.path)
                    }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // 左上：取消预览并丢弃
                Box(
                    Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(18.dp)
                        .size(38.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape)
                        .clickable {
                            runCatching { file.delete() }
                            captured = null
                        },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Text("✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp) }

                // 右下：完成
                Box(
                    Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(22.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF07C160), androidx.compose.foundation.shape.CircleShape)
                        .clickable {
                            val raw = runCatching { file.readBytes() }.getOrNull()
                            val bytes = if (raw != null && mime == "image/jpeg") compressJpeg(raw) else raw
                            if (bytes != null) {
                                onCaptured(name, mime, bytes)
                            } else {
                                error = "读取文件失败"
                            }
                            captured = null
                        }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Text("完成", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }

                Text(
                    if (mime == "video/mp4") "录像预览" else "照片预览",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(22.dp),
                )
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { activeRecording?.stop() }
            runCatching { camera?.cameraControl?.enableTorch(false) }
        }
    }
}

@Composable
private fun MediaStrip(
    items: List<JSONObject>,
    uploading: Boolean,
    onAdd: () -> Unit,
    onOpen: (JSONObject) -> Unit,
    onDelete: ((JSONObject) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { m ->
            val id = m.optLong("id")
            val kind = m.optString("kind")
            Box(Modifier.size(72.dp)) {
                if (kind == "video") {
                    RemoteVideoThumb(
                        url = m.optString("url"),
                        modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { onOpen(m) },
                    )
                } else {
                    RemoteImage(
                        url = m.optString("url"),
                        modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { onOpen(m) },
                    )
                }
                if (kind == "video") {
                    Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("▶", color = androidx.compose.ui.graphics.Color.White, fontSize = 22.sp)
                    }
                }
                if (onDelete != null) {
                    Box(
                        Modifier.align(androidx.compose.ui.Alignment.TopEnd).size(20.dp)
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape)
                            .clickable { onDelete(m) },
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { Text("×", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall) }
                }
                if (kind == "image") Unit
            }
        }
        Box(
            Modifier.size(72.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .clickable(onClick = onAdd),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { Text(if (uploading) "上传中…" else "+", style = MaterialTheme.typography.bodySmall) }
    }
    if (onDelete != null) {
        Text("点击缩略图预览，长按无效；点 × 删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun MediaViewerDialog(url: String, kind: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    FullscreenDialogEffect()

    val mediaId = Regex("/api/media/(\\d+)").find(url)?.groupValues?.getOrNull(1) ?: System.currentTimeMillis().toString()
    val ext = if (kind == "video") "mp4" else "jpg"
    val fileName = "单据媒体-$mediaId.$ext"

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (kind == "video") "video/mp4" else "image/jpeg")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        downloading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                        ?: throw IllegalStateException("无法写入所选文件")
                }
                error = "已保存"
            } catch (e: Exception) {
                error = "保存失败：" + (e.message ?: "")
            } finally {
                downloading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            if (kind == "video") {
                AndroidView(
                    factory = { c ->
                        android.widget.VideoView(c).apply {
                            setVideoURI(android.net.Uri.parse(url))
                            setOnPreparedListener { it.isLooping = false }
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RemoteImage(url, Modifier.fillMaxSize(), ContentScale.Fit)
            }

            // 顶部：关闭 / 下载
            Row(
                Modifier.align(androidx.compose.ui.Alignment.TopStart).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp)
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Text("✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 17.sp) }
                Box(
                    Modifier
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .clickable(enabled = !downloading) { saveLauncher.launch(fileName) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (downloading) "保存中…" else "下载",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    color = if (it == "已保存") androidx.compose.ui.graphics.Color(0xFF7CE38B) else androidx.compose.ui.graphics.Color(0xFFFF8A80),
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 26.dp),
                )
            }
        }
    }
}

@Composable
private fun UnitPicker(units: List<Pair<String, Double>>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val opts = units.ifEmpty { listOf(selected.ifBlank { "单位" } to 1.0) }
    Box {
        Row(
            Modifier
                .height(34.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(selected.ifBlank { "单位" }, style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            Text("▾", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            opts.forEach { (unit, conv) ->
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
    "delivery" -> "配送"
    "delivery_return" -> "配退"
    "pickup" -> "自送"
    "pickup_return" -> "自退"
    "receipt" -> "收款"
    "refund" -> "退款"
    "note" -> "备注"
    else -> "单据"
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

    PageScaffold("查询", onBack = onBack, scroll = false) { modifier ->
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

    PageScaffold("改当日单", onBack = onBack, scroll = false) { modifier ->
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
    var media by remember { mutableStateOf(listOf<JSONObject>()) }
    var viewing by remember { mutableStateOf<JSONObject?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var showTray by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun reloadMedia() {
        val oid = selected?.optLong("id") ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.orderMedia(oid) }.let { arr ->
                    (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        JSONObject(m.toString()).put("url", api.mediaUrl(m.optLong("id")))
                    }
                }
            }.onSuccess { media = it }.onFailure { error = it.message }
        }
    }

    fun uploadFiles(files: List<Triple<String, String, ByteArray>>) {
        val oid = selected?.optLong("id") ?: run { error = "请先选择单据"; return }
        if (files.isEmpty()) return
        uploading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { files.forEach { (n, m, b) -> api.uploadMedia(oid, n, m, b) } }
                reloadMedia()
            } catch (e: Exception) {
                error = e.message
            } finally {
                uploading = false
            }
        }
    }

    LaunchedEffect(selected) {
        media = emptyList()
        if (selected != null) reloadMedia()
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            uploading = true
            scope.launch {
                try {
                    val files = withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                            val name = queryDisplayName(ctx, uri) ?: ("media-" + System.currentTimeMillis())
                            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (bytes == null || bytes.size > 200 * 1024 * 1024) null else Triple(name, mime, bytes)
                        }
                    }
                    uploadFiles(files)
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    uploading = false
                }
            }
        }
    }

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

            selected?.let {
                SectionLabel("图片 / 视频")
                MediaStrip(
                    items = media,
                    uploading = uploading,
                    onAdd = { showTray = true },
                    onOpen = { viewing = it },
                    onDelete = { m ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { api.deleteMedia(m.optLong("id")) }
                                reloadMedia()
                            } catch (e: Exception) { error = e.message }
                        }
                    },
                )
            }
            if (showTray) {
                AlertDialog(
                    onDismissRequest = { showTray = false },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showTray = false }) { Text("取消") } },
                    title = { Text("添加图片 / 视频") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { showTray = false; pickMedia.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                                Text("从相册选择（可多选）")
                            }
                            OutlinedButton(onClick = { showTray = false; showCamera = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("打开相机（拍照 / 录像）")
                            }
                        }
                    },
                )
            }
            viewing?.let { m ->
                MediaViewerDialog(url = m.optString("url"), kind = m.optString("kind")) { viewing = null }
            }
            if (showCamera) {
                Dialog(
                    onDismissRequest = { showCamera = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                ) {
                    CameraCaptureScreen(
                        onClose = { showCamera = false },
                        onCaptured = { n, m, b ->
                            showCamera = false
                            uploadFiles(listOf(Triple(n, m, b)))
                        },
                    )
                }
            }
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

    PageScaffold("选择单据", onBack = onBack, scroll = false) { modifier ->
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
private fun SettlementScreen(api: CzxsApi, onMenu: () -> Unit, onSearch: () -> Unit) {
    var list by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            list = withContext(Dispatchers.IO) { api.settlement() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        } catch (e: Exception) { error = e.message }
    }

    AppScaffold("结算", onMenu = onMenu, onSearch = onSearch) { inner ->
        LazyColumn(Modifier.fillMaxWidth().padding(inner).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun statusLabel(s: String): String = when (s) {
    "submitted" -> "已提交"
    "archived" -> "已归档"
    "" -> "未知"
    else -> "未知状态"
}

private fun printStatusLabel(s: String): String = when (s) {
    "pending" -> "待打印"
    "printed" -> "已打印"
    "failed" -> "打印失败"
    else -> ""
}

@Composable
private fun OrderDetailDialog(
    api: CzxsApi,
    order: JSONObject,
    isAdmin: Boolean,
    onClose: () -> Unit,
    onEdit: (Long) -> Unit,
    onArchive: ((Long) -> Unit)? = null,
    onDelete: ((Long) -> Unit)? = null,
) {
    val type = order.optString("type")
    val id = order.optLong("id")
    val editable = isAdmin && (type == "delivery" || type == "delivery_return" || type == "pickup" || type == "pickup_return") &&
        order.optString("status") != "archived"
    var media by remember { mutableStateOf(listOf<JSONObject>()) }
    var viewing by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(id) {
        runCatching {
            withContext(Dispatchers.IO) { api.orderMedia(id) }.let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    JSONObject(m.toString()).put("url", api.mediaUrl(m.optLong("id")))
                }
            }
        }.onSuccess { media = it }
    }

    val payments = order.optJSONArray("payments") ?: JSONArray()
    val itemsArr = order.optJSONArray("items") ?: JSONArray()
    val amountLabelText = amountLabel(type)

    Dialog(onDismissRequest = onClose) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 头部
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(order.optString("order_no"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Chip(typeLabel(type), MaterialTheme.colorScheme.primary)
                            Chip(statusLabel(order.optString("status")), MaterialTheme.colorScheme.outline)
                            val ps = printStatusLabel(order.optString("print_status"))
                            if (ps.isNotBlank()) Chip(ps, MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Box(
                        Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Color(0xFFF2F4F7)).clickable(onClick = onClose),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { Text("✕", style = MaterialTheme.typography.bodyMedium) }
                }
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFEEF1F4))

                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 客户
                    Column(
                        Modifier.fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0xFFF9FAFB), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Text(order.optString("customer_name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(order.optString("customer_phone") + " · " + order.optString("customer_address").ifBlank { "无地址" }, style = MaterialTheme.typography.bodySmall)
                        Text(
                            order.optString("created_at").take(16).replace('T', ' ') +
                                (" · " + order.optString("account_name")).takeIf { order.optString("account_name").isNotBlank() }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    // 金额
                    Column(
                        Modifier.fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0xFFF9FAFB), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(amountLabelText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    order.optString("order_total"),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                val discount = order.optString("discount").toDoubleOrNull() ?: 0.0
                                if (discount > 0) {
                                    Text("优惠 ${order.optString("discount_type")} ${order.optString("discount")}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("累计结算 ${order.optString("cumulative_total")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }

                    // 明细
                    if (itemsArr.length() > 0) {
                        Text("明细（${itemsArr.length()} 项）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Column(
                            Modifier.fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                        ) {
                            for (i in 0 until itemsArr.length()) {
                                val line = itemsArr.getJSONObject(i)
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                            .background(androidx.compose.ui.graphics.Color(0xFFEEF7F5)),
                                        contentAlignment = androidx.compose.ui.Alignment.Center,
                                    ) { Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(line.optString("name"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "批号 ${line.optString("batch_tag").ifBlank { "无" }} · ${line.optString("input_unit")}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                        Text(
                                            "${line.optString("input_quantity")} × ${line.optString("unit_price")}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Text(line.optString("line_total"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 付款方式
                    if (payments.length() > 0) {
                        Text("付款方式", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            for (i in 0 until payments.length()) {
                                val pay = payments.getJSONObject(i)
                                Text(
                                    "${pay.optString("method")} ${pay.optString("amount")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .background(androidx.compose.ui.graphics.Color(0xFFF2F4F7), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    // 媒体
                    if (media.isNotEmpty()) {
                        Text("图片 / 视频（${media.size}）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        MediaStrip(items = media, uploading = false, onAdd = {}, onOpen = { viewing = it })
                    }

                    // 备注
                    if (order.optString("remark").isNotBlank()) {
                        Text("备注", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(order.optString("remark"), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // 底部操作
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFEEF1F4))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (editable) {
                        Button(onClick = { onEdit(id); onClose() }, modifier = Modifier.weight(1f)) { Text("编辑") }
                    }
                    if (isAdmin && order.optString("status") != "archived" && onArchive != null) {
                        OutlinedButton(onClick = { onArchive(id); onClose() }, modifier = Modifier.weight(1f)) { Text("归档") }
                    }
                    if (isAdmin && order.optString("status") != "archived" && onDelete != null) {
                        OutlinedButton(
                            onClick = { onDelete(id); onClose() },
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) { Text("删除") }
                    }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("关闭") }
                }
            }
        }
    }

    viewing?.let { m ->
        MediaViewerDialog(url = m.optString("url"), kind = m.optString("kind")) { viewing = null }
    }
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun OrdersScreen(api: CzxsApi, onMenu: () -> Unit, onSearch: () -> Unit, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            orders = withContext(Dispatchers.IO) { api.ordersAll() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    AppScaffold("单据库", onMenu = onMenu, onSearch = onSearch) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding)) {
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
        OrderDetailDialog(
            api = api,
            order = o,
            isAdmin = api.isAdmin,
            onClose = { detail = null },
            onEdit = onEdit,
            onArchive = { id ->
                scope.launch {
                    try { withContext(Dispatchers.IO) { api.archiveOrder(id) }; orders = orders.map { if (it.optLong("id") == id) JSONObject(it.toString()).put("status", "archived") else it } }
                    catch (e: Exception) { error = e.message }
                }
            },
            onDelete = { id ->
                scope.launch {
                    try { withContext(Dispatchers.IO) { api.deleteOrder(id) }; orders = orders.filterNot { it.optLong("id") == id } }
                    catch (e: Exception) { error = e.message }
                }
            },
        )
    }
}


// ---------- 打印 ----------
@Composable
private fun PrintScreen(api: CzxsApi, onMenu: () -> Unit, onSearch: () -> Unit) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            status = withContext(Dispatchers.IO) { api.printStatus() }
        } catch (e: Exception) { error = e.message }
    }

    AppScaffold("打印", onMenu = onMenu, onSearch = onSearch) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { ErrorText(it) }
            status?.let { s ->
                SectionLabel("打印队列")
                KeyValueRow("待打印", s.optString("pending", "0"))
                KeyValueRow("已打印", s.optString("printed", "0"))
                KeyValueRow("失败", s.optString("failed", "0"))
                Text("小票由服务端生成，客户端不直接驱动打印机。", style = MaterialTheme.typography.bodySmall)
            } ?: LoadingRow("读取中…")
        }
    }
}

// ---------- 型号库 ----------
private data class CardTint(
    val bg: androidx.compose.ui.graphics.Color,
    val title: androidx.compose.ui.graphics.Color,
    val l1: androidx.compose.ui.graphics.Color,
    val l2: androidx.compose.ui.graphics.Color,
    val l3: androidx.compose.ui.graphics.Color,
    val v1: androidx.compose.ui.graphics.Color,
    val v2: androidx.compose.ui.graphics.Color,
    val v3: androidx.compose.ui.graphics.Color,
)

// 7 色：大标题 1；小标题/值 三对，每对内部高对比（浅标签 + 深值）
private val cardTints = listOf(
    CardTint(
        bg = androidx.compose.ui.graphics.Color(0xFFE6F4F2),
        title = androidx.compose.ui.graphics.Color(0xFF0B3B38),
        l1 = androidx.compose.ui.graphics.Color(0xFF14B8A6), v1 = androidx.compose.ui.graphics.Color(0xFF134E4A),
        l2 = androidx.compose.ui.graphics.Color(0xFF0EA5E9), v2 = androidx.compose.ui.graphics.Color(0xFF0C4A6E),
        l3 = androidx.compose.ui.graphics.Color(0xFFF59E0B), v3 = androidx.compose.ui.graphics.Color(0xFF78350F),
    ),
    CardTint(
        bg = androidx.compose.ui.graphics.Color(0xFFFDF1E6),
        title = androidx.compose.ui.graphics.Color(0xFF43210A),
        l1 = androidx.compose.ui.graphics.Color(0xFFF97316), v1 = androidx.compose.ui.graphics.Color(0xFF7C2D12),
        l2 = androidx.compose.ui.graphics.Color(0xFFA855F7), v2 = androidx.compose.ui.graphics.Color(0xFF4C1D95),
        l3 = androidx.compose.ui.graphics.Color(0xFF10B981), v3 = androidx.compose.ui.graphics.Color(0xFF064E3B),
    ),
    CardTint(
        bg = androidx.compose.ui.graphics.Color(0xFFEDEBFB),
        title = androidx.compose.ui.graphics.Color(0xFF2A1B54),
        l1 = androidx.compose.ui.graphics.Color(0xFF8B5CF6), v1 = androidx.compose.ui.graphics.Color(0xFF4C1D95),
        l2 = androidx.compose.ui.graphics.Color(0xFFF43F5E), v2 = androidx.compose.ui.graphics.Color(0xFF881337),
        l3 = androidx.compose.ui.graphics.Color(0xFF06B6D4), v3 = androidx.compose.ui.graphics.Color(0xFF164E63),
    ),
    CardTint(
        bg = androidx.compose.ui.graphics.Color(0xFFE9F2E6),
        title = androidx.compose.ui.graphics.Color(0xFF1F3310),
        l1 = androidx.compose.ui.graphics.Color(0xFF84CC16), v1 = androidx.compose.ui.graphics.Color(0xFF365314),
        l2 = androidx.compose.ui.graphics.Color(0xFFF59E0B), v2 = androidx.compose.ui.graphics.Color(0xFF78350F),
        l3 = androidx.compose.ui.graphics.Color(0xFFA855F7), v3 = androidx.compose.ui.graphics.Color(0xFF4C1D95),
    ),
)

@Composable
private fun ConversionCapsule(
    unit: String,
    factor: String,
    settlementUnit: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .border(1.dp, valueColor.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            "换算:$unit",
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            "=$factor$settlementUnit",
            color = valueColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun Capsule(
    label: String,
    value: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    Row(
        modifier
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .border(1.dp, valueColor.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = labelColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
        Text(
            value,
            color = valueColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (suffix.isNotBlank()) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(3.dp))
            Text(suffix, color = valueColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun CategoriesScreen(api: CzxsApi, onMenu: () -> Unit, onSearch: () -> Unit) {
    var list by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<JSONObject?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var unitsS by remember { mutableStateOf(emptyList<String>()) }
    var unitsC by remember { mutableStateOf(emptyList<String>()) }
    var toggleReq by remember { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }
    var deleteReq by remember { mutableStateOf<JSONObject?>(null) }
    var dupMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                list = withContext(Dispatchers.IO) { api.categories() }.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            } catch (e: Exception) { error = e.message }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        runCatching {
            unitsS = withContext(Dispatchers.IO) { api.presets("settlement_units", emptyList()) }
            unitsC = withContext(Dispatchers.IO) { api.presets("conversion_units", emptyList()) }
        }
    }

    val filtered = list.filter {
        keyword.isBlank() || it.optString("group").contains(keyword) ||
            it.optString("name").contains(keyword) || it.optString("batch").contains(keyword)
    }

    AppScaffold("型号库", onMenu = onMenu, onSearch = onSearch) { inner ->
        Column(Modifier.fillMaxWidth().padding(inner).padding(PagePadding)) {
            error?.let { ErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppTextField("搜索品类/型号/批号", keyword, { keyword = it }, modifier = Modifier.weight(1f))
                if (api.isAdmin) Button(onClick = { editTarget = null; showEditor = true }) { Text("新增") }
            }
            Text(if (api.isAdmin) "管理员可维护型号库" else "业务员只读", style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered.size) { index ->
                    val c = filtered[index]
                    val tint = cardTints[index % cardTints.size]
                    Card(
                        onClick = { if (api.isAdmin) { editTarget = c; showEditor = true } },
                        modifier = Modifier.fillMaxWidth(),
                        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = tint.bg),
                    ) {
                        Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                            Text(
                                listOf(c.optString("group"), c.optString("name"), c.optString("batch").ifBlank { "无批号" })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = tint.title,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
                            androidx.compose.foundation.layout.FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Capsule("计价", c.optString("settlement_unit"), tint.l1, tint.v1)
                                ConversionCapsule(
                                    unit = c.optString("conversion_unit").ifBlank { "无" },
                                    factor = c.optString("conversion").ifBlank { "1" },
                                    settlementUnit = c.optString("settlement_unit"),
                                    labelColor = tint.l2,
                                    valueColor = tint.v2,
                                )
                                Capsule("默认价", c.optString("default_price").ifBlank { "无" }, tint.l3, tint.v3)
                                if (api.isAdmin) {
                                    Row(
                                        Modifier
                                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                                            .border(1.dp, MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                            .clickable { deleteReq = c }
                                            .padding(horizontal = 9.dp, vertical = 4.dp),
                                    ) {
                                        Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                androidx.compose.material3.Switch(
                                    checked = c.optString("status") == "active",
                                    onCheckedChange = { active -> toggleReq = c to active },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    dupMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { dupMsg = null },
            confirmButton = { TextButton(onClick = { dupMsg = null }) { Text("确定") } },
            title = { Text("重复商品") },
            text = { Text(msg) },
        )
    }

    toggleReq?.let { (cat, active) ->
        AlertDialog(
            onDismissRequest = { toggleReq = null },
            confirmButton = {
                TextButton(onClick = {
                    toggleReq = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.setCategoryStatus(cat.optLong("id"), active) }
                            reload()
                        } catch (e: Exception) { error = e.message }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { toggleReq = null }) { Text("取消") } },
            title = { Text(if (active) "启用型号" else "禁用型号") },
            text = {
                Text("确认将「${cat.optString("group")} · ${cat.optString("name")} · ${cat.optString("batch")}」设为${if (active) "启用" else "禁用"}？")
            },
        )
    }

    deleteReq?.let { cat ->
        AlertDialog(
            onDismissRequest = { deleteReq = null },
            confirmButton = {
                TextButton(onClick = {
                    deleteReq = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.deleteCategory(cat.optLong("id")) }
                            reload()
                        } catch (e: Exception) { error = e.message }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteReq = null }) { Text("取消") } },
            title = { Text("删除型号") },
            text = {
                Text("确认删除「${cat.optString("group")} · ${cat.optString("name")} · ${cat.optString("batch")}」？删除后不可恢复。")
            },
        )
    }

    if (showEditor) {
        CategoryEditorDialog(
            initial = editTarget,
            existingGroups = list.map { it.optString("group") }.distinct(),
            settlementUnits = unitsS,
            conversionUnits = unitsC,
            onDismiss = { showEditor = false },
            onSave = { body ->
                val g = body.optString("group")
                val n = body.optString("name")
                val b = body.optString("batch")
                val dup = list.firstOrNull {
                    it.optLong("id") != (editTarget?.optLong("id") ?: 0L) &&
                        it.optString("group") == g && it.optString("name") == n && it.optString("batch") == b
                }
                if (dup != null) {
                    dupMsg = "该商品已有，无需重复：" + "$g · $n · $b"
                } else {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val id = editTarget?.optLong("id") ?: 0L
                                if (id > 0) api.updateCategory(id, body) else api.createCategory(body)
                            }
                            showEditor = false
                            reload()
                        } catch (e: Exception) {
                            val m = e.message ?: ""
                            if (m.contains("已存在") || m.contains("重复") || m.contains("DUPLICATE")) {
                                dupMsg = "该商品已有，无需重复：" + "$g · $n · $b"
                            } else error = m
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun CategoryEditorDialog(
    initial: JSONObject?,
    existingGroups: List<String>,
    settlementUnits: List<String>,
    conversionUnits: List<String>,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit,
) {
    val tint = cardTints[0]
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
    var group by remember { mutableStateOf(initial?.optString("group") ?: "") }
    var name by remember { mutableStateOf(initial?.optString("name") ?: "") }
    var batch by remember { mutableStateOf(initial?.optString("batch") ?: "") }
    var unit by remember { mutableStateOf(initial?.optString("settlement_unit") ?: "") }
    var convUnit by remember { mutableStateOf(initial?.optString("conversion_unit") ?: "") }
    var conversion by remember { mutableStateOf(initial?.optString("conversion") ?: "") }
    var defaultPrice by remember { mutableStateOf(initial?.optString("default_price") ?: "") }
    var unitMenu by remember { mutableStateOf(false) }
    var convMenu by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showGroupMenu by remember { mutableStateOf(false) }
    val groupSuggestions = existingGroups.filter { it.contains(group, ignoreCase = true) && it != group }

    @Composable
    fun CapsuleField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
        Row(
            modifier
                .height(34.dp)
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f), pill)
                .border(1.dp, tint.v1.copy(alpha = 0.45f), pill)
                .padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = tint.title),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(tint.v1),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = tint.title.copy(alpha = 0.5f))
                    }
                    inner()
                },
            )
        }
    }

    @Composable
    fun CapsuleSelect(text: String, onClick: () -> Unit, modifier: Modifier) {
        Row(
            modifier
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f), pill)
                .border(1.dp, tint.v2.copy(alpha = 0.55f), pill)
                .clickable(onClick = onClick)
                .height(34.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = tint.title, maxLines = 1, modifier = Modifier.weight(1f))
            Text("▾", style = MaterialTheme.typography.bodySmall, color = tint.v2)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tint.bg,
        titleContentColor = tint.title,
        textContentColor = tint.title,
        confirmButton = {
            TextButton(onClick = {
                if (group.isBlank() || name.isBlank() || unit.isBlank()) {
                    err = "品类、型号、计价单位必填"
                    return@TextButton
                }
                onSave(JSONObject()
                    .put("group", group).put("name", name).put("batch", batch.ifBlank { name })
                    .put("settlement_unit", unit).put("conversion_unit", convUnit)
                    .put("conversion", conversion).put("default_price", defaultPrice))
            }) { Text("保存", color = tint.v1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = tint.v2) } },
        title = { Text(if (initial == null) "新增型号" else "编辑型号", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    CapsuleField(group, { group = it; showGroupMenu = true }, "品类", Modifier.fillMaxWidth())
                    DropdownMenu(
                        expanded = showGroupMenu && groupSuggestions.isNotEmpty(),
                        onDismissRequest = { showGroupMenu = false },
                    ) {
                        groupSuggestions.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g) },
                                onClick = { group = g; showGroupMenu = false },
                            )
                        }
                    }
                }
                CapsuleField(name, { name = it.uppercase() }, "型号", Modifier.fillMaxWidth())
                CapsuleField(batch, { batch = it.uppercase() }, "批号", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        CapsuleSelect("计价:${unit.ifBlank { "选择" }}", { unitMenu = true }, Modifier.fillMaxWidth())
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            settlementUnits.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { unitMenu = false; unit = u }) }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        CapsuleSelect("换算:${convUnit.ifBlank { "无" }}", { convMenu = true }, Modifier.fillMaxWidth())
                        DropdownMenu(expanded = convMenu, onDismissRequest = { convMenu = false }) {
                            DropdownMenuItem(text = { Text("无") }, onClick = { convMenu = false; convUnit = "" })
                            conversionUnits.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { convMenu = false; convUnit = u }) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CapsuleField(conversion, { conversion = it }, "换算系数", Modifier.weight(1f))
                    CapsuleField(defaultPrice, { defaultPrice = it }, "默认价", Modifier.weight(1f))
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

// ---------- 设置 ----------
@Composable
private fun SettingsScreen(api: CzxsApi, onMenu: () -> Unit, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    var server by remember { mutableStateOf(api.baseUrl) }
    var settings by remember { mutableStateOf(JSONObject()) }
    var message by remember { mutableStateOf<String?>(null) }
    var port by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf(api.securityEntry) }
    val editable = api.isAdmin

    LaunchedEffect(Unit) {
        runCatching {
            val st = withContext(Dispatchers.IO) { api.settings() }
            settings = st
            port = st.optString("api_port", "9527")
        }.onFailure { message = it.message }
    }

    AppScaffold("设置", onMenu = onMenu) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(PagePadding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel("账号")
            KeyValueRow("当前账号", "${api.accountName}（${if (api.isAdmin) "管理员" else "业务员"}）")

            SectionLabel("服务端")
            KeyValueRow("地址", api.baseUrl + "/" + api.securityEntry + GATEWAY_PREFIX)
            KeyValueRow("商家名称", settings.optString("merchant_name").ifBlank { "未设置" })
            Text("商家名称在安装向导中初始化，不可修改；如需更改需重装服务端。", style = MaterialTheme.typography.bodySmall)
            if (editable) {
                AppTextField("API 端口", port, { port = it }, numeric = true)
                AppTextField("安全入口（默认 chengtian）", entry, { entry = it })
                Button(onClick = {
                    api.setSecurityEntry(entry)
                    runCatching { api.saveSetting("security_entry", api.securityEntry) }
                        .onSuccess { message = "安全入口已保存：" + api.securityEntry }
                        .onFailure { message = "安全入口已保存到本机，服务端同步失败：${it.message}" }
                }) { Text("保存入口") }
                Button(onClick = {
                    runCatching { api.saveSetting("api_port", port) }
                        .onSuccess { message = "端口已保存，重启应用后生效" }
                        .onFailure { message = "保存失败：${it.message}" }
                }) { Text("保存端口") }
            } else {
                KeyValueRow("API 端口", settings.optString("api_port", "9527"))
            }

            SectionLabel("小票宽度")
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("80mm", "58mm").forEach { w ->
                        val selected = (settings.optString("print_width", "80mm")) == w
                        if (selected) Button(onClick = {}) { Text(w) } else OutlinedButton(onClick = {
                            runCatching { api.saveSetting("print_width", w) }
                                .onSuccess { settings = JSONObject(settings.toString()).put("print_width", w); message = "已保存" }
                                .onFailure { message = "保存失败：${it.message}" }
                        }) { Text(w) }
                    }
                }
            } else {
                KeyValueRow("小票宽度", settings.optString("print_width", "80mm"))
            }

            if (api.isAdmin) {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                var pendingKind by remember { mutableStateOf("model") }
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val bytes = withContext(Dispatchers.IO) { api.exportDatabase(pendingKind) }
                                withContext(Dispatchers.IO) {
                                    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入所选文件")
                                    out.use { it.write(bytes); it.flush() }
                                }
                                message = "已导出 ${bytes.size} 字节"
                            } catch (e: Exception) { message = "导出失败：${e.message}" }
                        }
                    }
                }
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val bytes = withContext(Dispatchers.IO) {
                                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                                }
                                withContext(Dispatchers.IO) { api.importDatabase(pendingKind, "import.sq3", bytes) }
                                message = "导入成功，请重启应用生效（账号不受影响）"
                            } catch (e: Exception) { message = "导入失败：${e.message}" }
                        }
                    }
                }

                SectionLabel("数据库（导入导出不含账号）")
                listOf("model" to "型号库", "docs" to "单据库").forEach { (kind, label) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            pendingKind = kind
                            val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                            exportLauncher.launch("${label}-${day}.sq3")
                        }) { Text("导出") }
                        OutlinedButton(onClick = { pendingKind = kind; importLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) }) { Text("导入") }
                    }
                }
            }

            PresetSection("计价单位预设", "settlement_units", settings, editable) { k, v ->
                runCatching { api.saveSetting(k, v) }
                    .onSuccess { settings = JSONObject(settings.toString()).put(k, v); message = "已保存" }
                    .onFailure { message = "保存失败：${it.message}" }
            }
            PresetSection("换算单位预设", "conversion_units", settings, editable) { k, v ->
                runCatching { api.saveSetting(k, v) }
                    .onSuccess { settings = JSONObject(settings.toString()).put(k, v); message = "已保存" }
                    .onFailure { message = "保存失败：${it.message}" }
            }
            PresetSection("付款方式预设", "payment_methods", settings, editable) { k, v ->
                runCatching { api.saveSetting(k, v) }
                    .onSuccess { settings = JSONObject(settings.toString()).put(k, v); message = "已保存" }
                    .onFailure { message = "保存失败：${it.message}" }
            }
            PresetSection("优惠方式预设", "discount_types", settings, editable) { k, v ->
                runCatching { api.saveSetting(k, v) }
                    .onSuccess { settings = JSONObject(settings.toString()).put(k, v); message = "已保存" }
                    .onFailure { message = "保存失败：${it.message}" }
            }

            SectionLabel("NAS 地址")
            AppTextField("http://192.168.1.20:9527", server, { server = it })
            Button(onClick = {
                runCatching { api.saveServer(server) }
                    .onSuccess { message = "已保存，返回登录页生效" }
                    .onFailure { message = "格式错误：${it.message}" }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存地址") }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(onClick = { api.logout(); onLoggedOut() }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        }
    }
}

@Composable
private fun PresetSection(
    label: String,
    key: String,
    settings: JSONObject,
    editable: Boolean,
    onSave: (String, String) -> Unit,
) {
    val items = settings.optString(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    var draft by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val commit: (List<String>) -> Unit = { onSave(key, it.joinToString(",")) }

    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(if (expanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                if (items.isEmpty()) {
                    Text("未设置", style = MaterialTheme.typography.bodySmall)
                }
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}. $item")
                        if (editable) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                OutlinedButton(onClick = {
                                    if (index > 0) {
                                        val list = items.toMutableList()
                                        val tmp = list[index - 1]
                                        list[index - 1] = list[index]
                                        list[index] = tmp
                                        commit(list)
                                    }
                                }) { Text("↑") }
                                OutlinedButton(onClick = {
                                    if (index < items.size - 1) {
                                        val list = items.toMutableList()
                                        val tmp = list[index + 1]
                                        list[index + 1] = list[index]
                                        list[index] = tmp
                                        commit(list)
                                    }
                                }) { Text("↓") }
                                OutlinedButton(onClick = { commit(items.filterIndexed { i, _ -> i != index }) }) { Text("删") }
                            }
                        }
                    }
                }
                if (editable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppTextField("新增一项", draft, { draft = it }, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            if (draft.isNotBlank()) {
                                commit(items + draft.trim())
                                draft = ""
                            }
                        }) { Text("添加") }
                    }
                }
            }
        }
    }
}
