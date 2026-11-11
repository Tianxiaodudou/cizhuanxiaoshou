/*
 * @file android-app/feature-settings/src/main/kotlin/com/jiancai/android/settings/SettingsRoute.kt
 * @role C12 设置：客户端配置（服务端地址）+ **只读**打印状态。
 * @exports PrintStatus, SettingsRoute
 * @depends core-network, core-ui
 * @calledBy android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt, android-app/core-ui/src/main/kotlin/com/jiancai/android/ui/UiKit.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.settings

import com.jiancai.android.network.ServerAddress
import com.jiancai.android.network.ServerAddressStore
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.ClientDenyPolicy
import com.jiancai.android.ui.FieldSpec
import com.jiancai.android.ui.FieldType
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.ShellTab
import com.jiancai.android.ui.UiButton

/**
 * 打印状态（**只读**，数值全部由服务端产出）。
 *
 * 客户端只展示「已打印几次 / 剩余几次」，不提供任何打印动作，也不连接打印机（铁律 6）。
 */
data class PrintStatus(
    val docNo: String,
    val printedCount: Int,
    val maxPrints: Int,
) {
    /** 恒定只读：没有任何修改入口。 */
    val readOnly: Boolean get() = true

    /** 剩余次数由服务端给的两个值相减展示（只用于显示，不落库、不参与判定）。 */
    val remaining: Int get() = (maxPrints - printedCount).coerceAtLeast(0)

    companion object {
        /** 用服务端返回的打印信息构造（客户端不自己数）。 */
        fun fromServer(docNo: String, printedCount: Int, maxPrints: Int): PrintStatus {
            require(docNo.isNotBlank()) { "缺少单据号" }
            require(printedCount >= 0) { "已打印次数不能为负" }
            require(maxPrints >= printedCount) { "打印上限不能小于已打印次数" }
            return PrintStatus(docNo, printedCount, maxPrints)
        }
    }
}

/**
 * C12 设置：客户端配置（服务端地址）+ **只读**打印状态。
 *
 * 硬边界（验收「打印状态只读」）：
 * - 没有任何「打印 / 补打 / 打印预览」入口：本模块的按钮只有保存地址；
 * - [PrintStatus] 只能由服务端数据构造，`readOnly` 恒为 true；
 * - 服务端地址的修改走 C00 的 [ServerAddressStore]（登录页也能改，两处同一个 store）。
 */
object SettingsRoute {

    /** 路由登记：底栏第三个 tab。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.SETTINGS, title = "设置", tab = ShellTab.SETTINGS)

    /** 当前服务端地址（只读展示）。 */
    fun serverAddress(addresses: ServerAddressStore): String = addresses.baseUrl()

    /** 修改服务端地址（唯一的设置写入口）。 */
    @UiButton(id = "settings_save_address", label = "保存服务端地址")
    fun saveServerAddress(addresses: ServerAddressStore, raw: String): ServerAddress = addresses.update(raw)

    /** 复位为默认地址。 */
    @UiButton(id = "settings_reset_address", label = "恢复默认地址")
    fun resetServerAddress(addresses: ServerAddressStore): ServerAddress =
        addresses.update(ServerAddress.DEFAULT)

    /** 只读打印状态（数值来自服务端）。 */
    fun printStatus(docNo: String, printedCount: Int, maxPrints: Int): PrintStatus =
        PrintStatus.fromServer(docNo, printedCount, maxPrints)

    /** 设置屏规格：地址可编辑，打印状态只读。 */
    fun screen(): ScreenSpec = ScreenSpec(
        id = entry.id,
        title = entry.title,
        fields = listOf(
            FieldSpec("server_address", "服务端地址", FieldType.TEXT),
            FieldSpec("print_status", "打印状态", FieldType.READONLY, readOnly = true, required = false),
        ),
        buttons = listOf(
            ButtonSpec("settings_save_address", "保存服务端地址", ButtonAction.Submit),
            ButtonSpec("settings_reset_address", "恢复默认地址", ButtonAction.Refresh),
        ),
    )

    /** 打印状态文案（只读展示，不含任何动作）。 */
    fun printStatusText(status: PrintStatus): String =
        ClientDenyPolicy.ALLOWED_READONLY_LABELS.last() + "：${status.remaining}"
}
