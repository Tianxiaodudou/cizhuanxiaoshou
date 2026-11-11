/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   50 行  类 NavGraphTest
 * 第   52 行  类 FakeClient
 * 第   53 行  函数 call
 * 第   56 行  函数 serverAddress
 * 第   70 行  函数 graph
 * 第   73 行  函数 all_registered_routes_are_assembled
 * 第   92 行  函数 the_bottom_bar_has_exactly_three_tabs
 * 第   97 行  函数 each_tab_owns_its_routes
 * 第  105 行  函数 the_start_destination_is_decided_by_the_session_gate_only
 * 第  116 行  函数 the_shell_never_reads_the_token_itself
 * 第  131 行  函数 features_never_import_each_other_ui
 * 第  167 行  函数 duplicate_or_unknown_routes_are_rejected
 * 第  177 行  函数 the_composition_root_wires_the_client_and_the_session
 * 第  186 行  函数 a_login_round_trip_navigates_to_home_through_the_gate
 */

package com.jiancai.android.shell

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.android.network.InMemoryServerAddressStore
import com.jiancai.android.session.InMemorySessionStore
import com.jiancai.android.session.LaunchDestination
import com.jiancai.android.session.Session
import com.jiancai.android.session.SessionGate
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ShellTab
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.enums.Role
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C03 验收：路由跳转正常。
 *
 * 断言：9 条路由装配成功、底栏 3 tab、启动落地由 C01 决定、feature 之间不互相 import UI。
 */
class NavGraphTest {

    private class FakeClient : ApiClient {
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> =
            ApiResult.Failure("E401", com.jiancai.shared.errors.ErrorCode.E401, "登录已失效", 401)

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    private val account = AccountDto(
        id = 1,
        username = "admin",
        displayName = "管理员",
        role = Role.ADMIN,
        enabled = true,
        createdAt = "2026-11-01T09:00:00+08:00",
    )

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Shanghai"))

    private fun graph(): NavGraph = NavGraph.assemble()

    @Test
    fun all_registered_routes_are_assembled() {
        val ids = graph().routeIds()
        assertEquals(
            setOf(
                AppRoutes.LOGIN,
                AppRoutes.HOME,
                AppRoutes.ORDER_ENTRY,
                AppRoutes.QUERY,
                AppRoutes.TODAY,
                AppRoutes.NOTE,
                AppRoutes.SETTLE,
                AppRoutes.ADMIN,
                AppRoutes.SETTINGS,
            ),
            ids,
        )
    }

    @Test
    fun the_bottom_bar_has_exactly_three_tabs() {
        assertEquals(listOf(ShellTab.HOME, ShellTab.TODAY, ShellTab.SETTINGS), graph().tabs())
    }

    @Test
    fun each_tab_owns_its_routes() {
        val graph = graph()
        assertEquals(listOf(AppRoutes.HOME), graph.routesOf(ShellTab.HOME).map { it.id })
        assertEquals(listOf(AppRoutes.TODAY), graph.routesOf(ShellTab.TODAY).map { it.id })
        assertEquals(listOf(AppRoutes.SETTINGS), graph.routesOf(ShellTab.SETTINGS).map { it.id })
    }

    @Test
    fun the_start_destination_is_decided_by_the_session_gate_only() {
        val guestGate = SessionGate(FakeClient(), clock = clock)
        assertEquals(AppRoutes.LOGIN, graph().startDestination(guestGate))
        assertEquals(LaunchDestination.LOGIN, guestGate.destination())

        val store = InMemorySessionStore(Session("t", account, "2099-01-01T00:00:00+08:00"))
        val signedInGate = SessionGate(FakeClient(), store, clock)
        assertEquals(AppRoutes.HOME, graph().startDestination(signedInGate))
    }

    @Test
    fun the_shell_never_reads_the_token_itself() {
        val sources = File("src/main/kotlin/com/jiancai/android/shell").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        // 唯一允许的 token 相关代码是「把 token 请求转给 C01」的桥
        assertTrue(text.contains("session?.token()"), "桥只转调 C01")
        assertTrue(
            !text.contains("LoginResultDto") && !text.contains("Authorization"),
            "shell 不得自己处理登录凭证",
        )
        assertTrue(!text.contains("HomeRoute.buttons"), "shell 不复制 feature 的 UI 细节")
    }

    @Test
    fun features_never_import_each_other_ui() {
        val featuresRoot = File("../..")
        val packageToModule = mapOf(
            "home" to "feature-home",
            "login" to "feature-login",
            "orderentry" to "feature-order-entry",
            "query" to "feature-query",
            "today" to "feature-today",
            "note" to "feature-note",
            "settle" to "feature-settle",
            "admin" to "feature-admin",
            "settings" to "feature-settings",
        )
        // 卡上明确要求 C08 复用 C06 的录入壳，这是唯一允许的 feature → feature 依赖。
        val allowed = setOf("feature-today" to "feature-order-entry")
        val violations = mutableListOf<String>()

        featuresRoot.listFiles { file -> file.isDirectory && file.name.startsWith("feature-") }
            ?.forEach { module ->
                module.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val text = file.readText()
                        Regex("""import com\.jiancai\.android\.(\w+)\.""").findAll(text).forEach { match ->
                            val target = packageToModule[match.groupValues[1]] ?: return@forEach
                            if (target == module.name) return@forEach
                            if ((module.name to target) in allowed) return@forEach
                            violations += "${module.name} → $target (${file.name})"
                        }
                    }
            }

        assertTrue(violations.isEmpty(), "feature 之间不得互相 import UI：$violations")
    }

    @Test
    fun duplicate_or_unknown_routes_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            NavGraph(listOf(RouteEntry("/x", "x"), RouteEntry("/x", "y")))
        }
        assertFailsWith<IllegalArgumentException> { graph().route("/nope") }
        assertEquals(AppRoutes.HOME, graph().route("/home").id)
        assertEquals(AppRoutes.ORDER_ENTRY, graph().route(AppRoutes.orderEntry("purchase")).id)
    }

    @Test
    fun the_composition_root_wires_the_client_and_the_session() {
        val shell = ClientAssembly.create(InMemoryServerAddressStore())
        assertEquals("http://127.0.0.1:8080", shell.client.serverAddress())
        assertEquals(AppRoutes.LOGIN, shell.startDestination())
        shell.session.logout()
        assertEquals(AppRoutes.LOGIN, shell.startDestination())
    }

    @Test
    fun a_login_round_trip_navigates_to_home_through_the_gate() {
        val store = InMemorySessionStore()
        val gate = SessionGate(FakeClient(), store, clock)
        store.save(Session("t", account, "2099-01-01T00:00:00+08:00"))
        assertEquals(AppRoutes.HOME, graph().startDestination(gate))
        assertEquals(Role.ADMIN, (gate.state() as com.jiancai.android.session.SessionState.Authenticated).account.role)
        assertEquals("t", gate.token())
    }
}
