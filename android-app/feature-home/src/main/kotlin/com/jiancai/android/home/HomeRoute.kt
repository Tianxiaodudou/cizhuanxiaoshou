/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   32 行  对象 HomeRoute
 * 第   38 行  函数 purchase
 * 第   41 行  函数 direct
 * 第   44 行  函数 query
 * 第   47 行  函数 note
 * 第   50 行  函数 settle
 * 第   53 行  函数 buttons
 * 第   62 行  函数 screen
 */

package com.jiancai.android.home

import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.ShellTab
import com.jiancai.android.ui.UiButton
import com.jiancai.shared.enums.OrderType

/**
 * C05 首页：5 个入口按钮。
 *
 * 硬边界：本模块**没有任何业务计算**（不读订单、不算金额），只把点击变成导航意图。
 * 5 个按钮 = 采购下单 / 直送下单 / 历史查询 / 差异备注 / 结算上传。
 * 四个下单类型共用同一个录入页（[AppRoutes.ORDER_ENTRY]），只带不同的 `orderType`。
 */
object HomeRoute {

    /** 路由登记：首页是底栏第一个 tab。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.HOME, title = "首页", tab = ShellTab.HOME)

    @UiButton(id = "home_purchase", label = "采购下单")
    fun purchase(): ButtonAction = ButtonAction.Navigate(AppRoutes.orderEntry(OrderType.PURCHASE.name.lowercase()))

    @UiButton(id = "home_direct", label = "直送下单")
    fun direct(): ButtonAction = ButtonAction.Navigate(AppRoutes.orderEntry(OrderType.DIRECT.name.lowercase()))

    @UiButton(id = "home_query", label = "历史查询")
    fun query(): ButtonAction = ButtonAction.Navigate(AppRoutes.QUERY)

    @UiButton(id = "home_note", label = "差异备注")
    fun note(): ButtonAction = ButtonAction.Navigate(AppRoutes.NOTE)

    @UiButton(id = "home_settle", label = "结算上传")
    fun settle(): ButtonAction = ButtonAction.Navigate(AppRoutes.SETTLE)

    /** 首页 5 个按钮（顺序即界面顺序）。 */
    fun buttons(): List<ButtonSpec> = listOf(
        ButtonSpec("home_purchase", "采购下单", purchase()),
        ButtonSpec("home_direct", "直送下单", direct()),
        ButtonSpec("home_query", "历史查询", query()),
        ButtonSpec("home_note", "差异备注", note()),
        ButtonSpec("home_settle", "结算上传", settle()),
    )

    /** 首页屏规格。 */
    fun screen(): ScreenSpec = ScreenSpec(id = entry.id, title = entry.title, buttons = buttons())
}
