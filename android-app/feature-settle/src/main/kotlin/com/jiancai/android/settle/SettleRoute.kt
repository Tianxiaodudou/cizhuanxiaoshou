/*
 * @file android-app/feature-settle/src/main/kotlin/com/jiancai/android/settle/SettleRoute.kt
 * @role C10 结算上传：提交后**当场回显**服务端结果。
 * @exports SettleOutcome, SettleRoute
 * @depends core-network, core-ui, shared
 * @calledBy android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.settle

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
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.errors.ErrorCode

/**
 * 一次结算上传的当场结果（成功 / 失败都直接来自服务端）。
 *
 * @property settle 服务端判定后的结算单（含状态）
 * @property failure 服务端拒绝时的错误（文案原样展示）
 */
data class SettleOutcome(
    val settle: SettleDto? = null,
    val failure: ApiResult.Failure? = null,
) {
    /** 是否上传成功（唯一判据：服务端返回了结算单）。 */
    val success: Boolean get() = settle != null
}

/**
 * C10 结算上传：提交后**当场回显**服务端结果。
 *
 * 硬边界（验收「上传与回显正确」+ 禁止客户端判定结算）：
 * - 结算是否成立、金额是否超未收，全部由 S11 判定；客户端只上传「订单号 + 方向 + 用户填的金额」；
 * - 不在本地比较金额、不自己算已收 / 未收（那是 S11 的读模型 `ReceivableDto`）；
 * - 成功后只展示服务端返回的 `SettleDto`，不修改任何本地状态为「已收」。
 */
object SettleRoute {

    /** 路由登记。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.SETTLE, title = "结算上传")

    /**
     * 上传一笔结算（唯一写入口）。
     *
     * @param amountText 用户输入的收款 / 退款金额（两位小数）；服务端规范化并校验上限
     * @param clientRequestId 幂等键：重试必须复用，避免重复结算
     */
    @UiButton(id = "settle_submit", label = "上传结算")
    fun submit(
        client: ApiClient,
        orderNo: String,
        direction: SettleDirection,
        amountText: String,
        remark: String?,
        clientRequestId: String,
    ): SettleOutcome {
        if (orderNo.isBlank()) return rejected("请填写订单号")
        if (clientRequestId.isBlank()) return rejected("缺少幂等键")
        val amount = runCatching { Money.ofPlain(amountText) }.getOrElse { return rejected("金额格式非法") }

        val result = client.call(
            ApiPaths.submitSettlement(
                SubmitSettleCmd(
                    clientRequestId = clientRequestId.trim(),
                    orderNo = orderNo.trim(),
                    direction = direction,
                    amount = amount,
                    remark = remark?.trim()?.ifBlank { null },
                ),
            ),
        )
        return when (result) {
            is ApiResult.Success -> SettleOutcome(settle = result.value)
            is ApiResult.Failure -> SettleOutcome(failure = result)
        }
    }

    /** 结算屏规格：只读金额展示来自服务端读模型。 */
    fun screen(): ScreenSpec = ScreenSpec(
        id = entry.id,
        title = entry.title,
        fields = listOf(
            FieldSpec("order_no", "订单号", FieldType.TEXT),
            FieldSpec("direction", "收 / 退", FieldType.SELECT),
            FieldSpec("amount", "金额", FieldType.DECIMAL),
            FieldSpec("unreceived_amount", "未收金额", FieldType.READONLY, readOnly = true, required = false),
            FieldSpec("remark", "备注", FieldType.MULTILINE, required = false),
        ),
        buttons = listOf(ButtonSpec("settle_submit", "上传结算", ButtonAction.Submit)),
    )

    private fun rejected(message: String): SettleOutcome = SettleOutcome(
        failure = ApiResult.Failure(
            wireCode = ErrorCode.E422.name,
            code = ErrorCode.E422,
            message = message,
            httpStatus = 422,
        ),
    )
}
