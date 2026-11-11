/*
 * @file android-app/feature-admin/src/main/kotlin/com/jiancai/android/admin/AdminRoute.kt
 * @role C11 管理后台：型号库 / 价格 / 结算审核 / 账号 四个子页。
 * @exports AdminRoute
 * @depends core-network, core-ui, shared
 * @calledBy android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.admin

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.UiButton
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.SettleDto

/**
 * C11 管理后台：型号库 / 价格 / 结算审核 / 账号 四个子页。
 *
 * 硬边界（验收「权限由服务端把关」）：
 * - 本模块**不判断角色、不做任何前端权限分支**：界面一律照常显示，越权由 S13 返回 `E403` 就地回显；
 * - 每个子页只有「调接口 + 展示服务端 DTO」，没有业务计算（改价只传新价格字符串）；
 * - 账号列表不含密码（`AccountDto` 本来就没有密码字段）。
 */
object AdminRoute {

    /** 路由登记。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.ADMIN, title = "管理后台")

    /** 子页 id（4 个固定子页）。 */
    enum class SubPage(val title: String, val id: String) {
        CATEGORY("型号库", "${AppRoutes.ADMIN}/category"),
        PRICE("价格", "${AppRoutes.ADMIN}/price"),
        SETTLE_AUDIT("结算审核", "${AppRoutes.ADMIN}/settle-audit"),
        ACCOUNT("账号", "${AppRoutes.ADMIN}/account"),
    }

    // —— 型号库 ——
    @UiButton(id = "admin_categories", label = "型号库")
    fun categories(client: ApiClient): ApiResult<List<CategoryDto>> =
        client.call(ApiPaths.categories(enabledOnly = false))

    @UiButton(id = "admin_category_save", label = "保存型号")
    fun saveCategory(client: ApiClient, cmd: CategoryCmd): ApiResult<CategoryDto> =
        if (cmd.id == null) {
            client.call(ApiPaths.createCategory(cmd))
        } else {
            client.call(ApiPaths.updateCategory(cmd))
        }

    // —— 价格 ——
    @UiButton(id = "admin_price_change", label = "改价")
    fun changePrice(client: ApiClient, cmd: ChangePriceCmd): ApiResult<PriceDto> =
        client.call(ApiPaths.changePrice(cmd))

    // —— 结算审核 ——
    @UiButton(id = "admin_settle_audit", label = "审核结算")
    fun auditSettlement(client: ApiClient, cmd: AuditSettleCmd): ApiResult<SettleDto> =
        client.call(ApiPaths.auditSettlement(cmd))

    // —— 账号 ——
    @UiButton(id = "admin_accounts", label = "账号列表")
    fun accounts(client: ApiClient): ApiResult<List<AccountDto>> = client.call(ApiPaths.accounts())

    @UiButton(id = "admin_account_create", label = "新增账号")
    fun createAccount(client: ApiClient, cmd: CreateAccountCmd): ApiResult<AccountDto> =
        client.call(ApiPaths.createAccount(cmd))

    /** 四个子页的屏规格（顺序固定，供管理后台首页渲染）。 */
    fun screens(): List<ScreenSpec> = SubPage.entries.map { page ->
        ScreenSpec(
            id = page.id,
            title = page.title,
            buttons = listOf(
                ButtonSpec(
                    id = "admin_open_${page.name.lowercase()}",
                    label = page.title,
                    action = ButtonAction.Navigate(page.id),
                ),
            ),
        )
    }
}
