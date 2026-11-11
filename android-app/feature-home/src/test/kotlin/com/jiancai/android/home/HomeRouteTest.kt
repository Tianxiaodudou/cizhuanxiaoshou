/*
 * @file android-app/feature-home/src/test/kotlin/com/jiancai/android/home/HomeRouteTest.kt
 * @role C05 验收：按钮跳转正常。
 * @exports HomeRouteTest
 * @depends core-ui
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.home

import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.UiButton
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C05 验收：按钮跳转正常。
 *
 * 断言：5 个按钮、id 唯一、每个都跳到正确路由、四个下单类型共用同一录入路由。
 */
class HomeRouteTest {

    @Test
    fun the_home_screen_has_exactly_five_buttons() {
        val buttons = HomeRoute.buttons()
        assertEquals(5, buttons.size)
        assertEquals(5, buttons.map { it.id }.distinct().size, "按钮 id 必须唯一")
    }

    @Test
    fun every_button_navigates_to_the_expected_route() {
        assertEquals(
            mapOf(
                "home_purchase" to AppRoutes.orderEntry("purchase"),
                "home_direct" to AppRoutes.orderEntry("direct"),
                "home_query" to AppRoutes.QUERY,
                "home_note" to AppRoutes.NOTE,
                "home_settle" to AppRoutes.SETTLE,
            ),
            HomeRoute.buttons().associate { it.id to (it.action as ButtonAction.Navigate).routeId },
        )
    }

    @Test
    fun all_four_order_types_share_one_entry_route() {
        val routes = listOf(
            AppRoutes.orderEntry("purchase"),
            AppRoutes.orderEntry("purchase_return"),
            AppRoutes.orderEntry("direct"),
            AppRoutes.orderEntry("direct_return"),
        )
        val paths = routes.map { it.substringBefore('?') }.distinct()
        assertEquals(listOf(AppRoutes.ORDER_ENTRY), paths, "4 类录入页必须共用同一个组件 / 路由")
        assertEquals(4, routes.distinct().size, "靠 orderType 区分四类单据")
    }

    @Test
    fun every_button_is_annotated_for_the_scan_script() {
        val annotated = HomeRoute::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(UiButton::class.java) }
            .map { it.id }

        assertEquals(HomeRoute.buttons().map { it.id }.toSet(), annotated.toSet())
    }

    @Test
    fun the_home_screen_never_contains_business_computation() {
        val sources = File("src/main/kotlin/com/jiancai/android/home").listFiles()!!
            .filter { it.extension == "kt" }
        assertTrue(sources.isNotEmpty())
        val text = sources.joinToString("\n") { it.readText() }

        listOf("ApiClient", "ApiPaths", "Money", "BigDecimal", "totalAmount").forEach { keyword ->
            assertFalse(text.contains(keyword), "首页不得调用接口 / 计算金额（发现 $keyword）")
        }
    }

    @Test
    fun the_home_entry_belongs_to_the_first_tab() {
        assertEquals(AppRoutes.HOME, HomeRoute.entry.id)
        assertTrue(HomeRoute.entry.requiresLogin)
        assertEquals(HomeRoute.entry.title, HomeRoute.screen().title)
    }
}
