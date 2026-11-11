/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   38 行  对象 TodayRoute
 * 第   45 行  函数 load
 * 第   53 行  函数 edit
 * 第   61 行  函数 save
 * 第   78 行  函数 screen
 */

package com.jiancai.android.today

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.android.orderentry.OrderFormComponent
import com.jiancai.android.orderentry.OrderFormState
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.ShellTab
import com.jiancai.android.ui.UiButton
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.dto.OrderLineCmd

/**
 * C08 今日改单：当日单列表 + 编辑。
 *
 * 硬边界（验收「复用录入壳；跨日不可改」）：
 * - 「今天」由**服务端**决定（`/orders/today`），客户端不看本地日期、不比对日期字符串；
 *   跨日锁定是服务端 `E2003`，客户端只把失败文案回显；
 * - 编辑复用 C06 的 `OrderFormComponent.prefillFrom`（同一套录入壳，不重写表单）；
 * - 提交改单带服务端给的 `version`（乐观锁），客户端不自己生成版本号。
 */
object TodayRoute {

    /** 路由登记：底栏第二个 tab。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.TODAY, title = "今日改单", tab = ShellTab.TODAY)

    /** 拉取服务端「今天」的订单（唯一取数入口）。 */
    @UiButton(id = "today_refresh", label = "刷新")
    fun load(client: ApiClient): ApiResult<List<OrderDto>> = client.call(ApiPaths.todayOrders())

    /**
     * 打开编辑：用服务端订单回填录入壳。
     *
     * 不做任何「是不是今天」的判断——服务端在 `update` 时用 `E2003` 拒绝跨日改单。
     */
    @UiButton(id = "today_edit", label = "编辑")
    fun edit(order: OrderDto): OrderFormState = OrderFormComponent.prefillFrom(order)

    /**
     * 保存改单。
     *
     * @param version 服务端下发的乐观锁版本（原样回传，不自增）
     */
    @UiButton(id = "today_save", label = "保存")
    fun save(
        client: ApiClient,
        order: OrderDto,
        lines: List<OrderLineCmd>,
        remark: String?,
    ): ApiResult<OrderDto> = client.call(
        ApiPaths.updateOrder(
            UpdateOrderCmd(
                orderNo = order.orderNo,
                version = order.version,
                lines = lines,
                remark = remark?.ifBlank { null },
            ),
        ),
    )

    /** 列表屏规格。 */
    fun screen(): ScreenSpec = ScreenSpec(
        id = entry.id,
        title = entry.title,
        buttons = listOf(
            ButtonSpec("today_refresh", "刷新", ButtonAction.Refresh),
            ButtonSpec("today_edit", "编辑", ButtonAction.Navigate(AppRoutes.ORDER_ENTRY)),
        ),
    )
}
