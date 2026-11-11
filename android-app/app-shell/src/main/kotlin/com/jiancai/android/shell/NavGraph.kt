/*
 * @file android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt
 * @role 导航图：把各 feature 登记的路由装配起来（验收「路由跳转正常」）。
 * @exports NavGraph, AppShell, TokenBridge, ClientAssembly
 * @depends core-network, core-session, core-ui, feature-login, feature-home, feature-order-entry, feature-query, feature-today, feature-note, feature-settle, feature-admin, feature-settings
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/data/AppContainer.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.shell

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.HttpApiClient
import com.jiancai.android.network.InMemoryServerAddressStore
import com.jiancai.android.network.ServerAddressStore
import com.jiancai.android.session.InMemorySessionStore
import com.jiancai.android.session.LaunchDestination
import com.jiancai.android.session.SessionGate
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ShellTab
import com.jiancai.android.login.LoginRoute
import com.jiancai.android.home.HomeRoute
import com.jiancai.android.orderentry.OrderEntryRoute
import com.jiancai.android.query.QueryRoute
import com.jiancai.android.today.TodayRoute
import com.jiancai.android.note.NoteRoute
import com.jiancai.android.settle.SettleRoute
import com.jiancai.android.admin.AdminRoute
import com.jiancai.android.settings.SettingsRoute

/**
 * 导航图：把各 feature 登记的路由装配起来（验收「路由跳转正常」）。
 *
 * 硬边界：
 * - **feature 之间互不 import**：导航图由 shell 单方向聚合，feature 只引用 C02 的 `AppRoutes` id；
 * - 「先去登录页还是首页」只问 C01 的 `SessionGate.destination()`，shell 不看 token；
 * - 底栏固定 3 个 tab（首页 / 今日 / 设置），其余入口从首页按钮进入。
 */
class NavGraph(entries: List<RouteEntry>) {

    /** 全部路由（按 id 索引；id 重复直接失败）。 */
    private val byId: Map<String, RouteEntry>

    init {
        val duplicates = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "路由 id 重复：$duplicates" }
        byId = entries.associateBy { it.id }
    }

    /** 底栏 tab（固定 3 个，顺序即显示顺序）。 */
    fun tabs(): List<ShellTab> = ShellTab.entries

    /** 某个 tab 下的路由。 */
    fun routesOf(tab: ShellTab): List<RouteEntry> = byId.values.filter { it.tab == tab }

    /** 按 id 取路由；未知 id 抛异常（路由表与按钮注解不一致是开发期错误）。 */
    fun route(id: String): RouteEntry =
        byId[id.substringBefore('?')] ?: throw IllegalArgumentException("未注册的路由：$id")

    /** 全部路由 id（自检 / 扫描用）。 */
    fun routeIds(): Set<String> = byId.keys

    /**
     * 启动落地页：登录成功与否交给 C01 判断。
     *
     * @return 要落地的路由 id
     */
    fun startDestination(gate: SessionGate): String =
        when (gate.destination()) {
            LaunchDestination.LOGIN -> AppRoutes.LOGIN
            LaunchDestination.HOME -> AppRoutes.HOME
        }

    companion object {
        /** 组合根调用：把全部 feature 的路由登记进来。 */
        fun assemble(): NavGraph = NavGraph(
            listOf(
                LoginRoute.entry,
                HomeRoute.entry,
                OrderEntryRoute.ENTRY,
                QueryRoute.entry,
                TodayRoute.entry,
                NoteRoute.entry,
                SettleRoute.entry,
                AdminRoute.entry,
                SettingsRoute.entry,
            ),
        )
    }
}

/**
 * 应用装配（组合根）：把 C00 / C01 / 导航图接起来。
 *
 * Android 侧只在这里 `setContent { AppShell(...) }`；其余模块不互相装配。
 */
class AppShell(
    val addresses: ServerAddressStore,
    val client: ApiClient,
    val session: SessionGate,
) {
    /** 导航图（feature 只登记，不互相装配）。 */
    val navGraph: NavGraph = NavGraph.assemble()

    /** 启动时该显示哪一页（判断只在 C01）。 */
    fun startDestination(): String = navGraph.startDestination(session)
}

/**
 * token 桥：C00 需要 [com.jiancai.android.network.TokenProvider]，而 C01 的 `SessionGate`
 * 又需要 `ApiClient`（构造上的环）。组合根用一个可回填的桥打破这个环——
 * 桥本身不判断登录态，只把请求转给 C01。
 */
class TokenBridge : com.jiancai.android.network.TokenProvider {

    /** 由组合根在 `SessionGate` 构造后回填。 */
    var session: SessionGate? = null

    override fun token(): String? = session?.token()
}

/** 默认装配：真实 HTTP 传输 + 内存会话（真实工程换持久化 `SessionStore` / 地址存储）。 */
object ClientAssembly {

    fun create(addresses: ServerAddressStore = InMemoryServerAddressStore()): AppShell {
        val bridge = TokenBridge()
        val client = HttpApiClient(addresses, bridge)
        val session = SessionGate(client, InMemorySessionStore())
        bridge.session = session
        return AppShell(addresses, client, session)
    }
}
