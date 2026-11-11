/*
 * @file android-app/feature-query/src/main/kotlin/com/jiancai/android/query/QueryRoute.kt
 * @role C07 历史查询：按电话查历史订单。
 * @exports QueryRoute
 * @depends core-network, core-ui, shared
 * @calledBy android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.query

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.FieldSpec
import com.jiancai.android.ui.FieldType
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.UiButton
import com.jiancai.shared.dto.DEFAULT_PAGE_SIZE
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.errors.ErrorCode

/**
 * C07 历史查询：按电话查历史订单。
 *
 * 硬边界（验收「只调聚合接口」）：
 * - 只调用服务端的订单聚合查询（`/orders?phone=`），**不拼 SQL、不连库、不在本地过滤**；
 * - 结果直接展示服务端 DTO（含金额 / 状态），客户端零计算；
 * - 没有电话时不发请求，就地提示。
 */
object QueryRoute {

    /** 路由登记：底栏第二个 tab「今日」之外，历史查询在首页按钮进入。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.QUERY, title = "历史查询")

    /** 按电话查询（唯一取数入口）。 */
    @UiButton(id = "query_search", label = "查询")
    fun search(client: ApiClient, phone: String, page: Int = 1): ApiResult<PageDto<OrderDto>> {
        val normalized = phone.trim()
        if (normalized.isEmpty()) {
            return ApiResult.Failure(
                wireCode = ErrorCode.E422.name,
                code = ErrorCode.E422,
                message = "请输入客户电话",
                httpStatus = 422,
            )
        }
        return client.call(
            ApiPaths.orders(
                OrderQuery(phone = normalized, page = page, pageSize = DEFAULT_PAGE_SIZE),
            ),
        )
    }

    /** 查询屏规格：电话输入 + 查询按钮 + 分页按钮。 */
    fun screen(): ScreenSpec = ScreenSpec(
        id = entry.id,
        title = entry.title,
        fields = listOf(
            FieldSpec("phone", "客户电话", FieldType.PHONE),
        ),
        buttons = listOf(
            ButtonSpec("query_search", "查询", ButtonAction.Submit),
            ButtonSpec("query_more", "加载更多", ButtonAction.Refresh),
        ),
    )
}
