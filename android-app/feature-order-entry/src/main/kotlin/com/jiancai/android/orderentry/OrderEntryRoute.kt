/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   53 行  类 OrderFormLine
 * 第   65 行  类 OrderFormState
 * 第   85 行  对象 OrderFormComponent
 * 第   88 行  函数 empty
 * 第   91 行  函数 withCustomer
 * 第   98 行  函数 addLine
 * 第  101 行  函数 replaceLine
 * 第  104 行  函数 removeLine
 * 第  107 行  函数 withRemark
 * 第  110 行  函数 localValidationFailure
 * 第  122 行  函数 toCommand
 * 第  150 行  函数 prefillFrom
 * 第  162 行  函数 screen
 * 第  183 行  函数 toFormLine
 * 第  190 行  函数 failure
 * 第  203 行  类 OrderEntryRoute
 * 第  208 行  函数 form
 * 第  211 行  函数 lookupCustomer
 * 第  219 行  函数 submit
 * 第  232 行  对象 ?
 */

package com.jiancai.android.orderentry

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
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit

/**
 * 录入页的一行明细（客户端只保存用户输入的型号 / 批号 / 单位 / 数量）。
 *
 * 价格与金额不在这里：它们由服务端按定价规则产出，客户端只展示（铁律 5）。
 */
data class OrderFormLine(
    val categoryId: Long,
    val batchTag: String = "",
    val unit: PricingUnit = PricingUnit.PIECE,
    val quantity: String = "",
)

/**
 * 录入页状态（4 类单据共用同一份结构，只有 [orderType] 不同）。
 *
 * @property submitted 服务端返回的订单（含单号 / 金额），只读展示
 */
data class OrderFormState(
    val orderType: OrderType,
    val customerPhone: String = "",
    val customerId: Long? = null,
    val customerName: String = "",
    val customerAddress: String? = null,
    val lines: List<OrderFormLine> = emptyList(),
    val remark: String = "",
    val submitted: OrderDto? = null,
    val failure: ApiResult.Failure? = null,
)

/**
 * 四类录入页共用的表单组件（验收「4 类页共用组件」）。
 *
 * 硬边界：
 * - 只拼 `CreateOrderCmd`（型号 / 批号 / 单位 / 数量 / 备注），**绝不上传单价、金额、单号、日期**；
 * - 不计算总额 / 应收：这些字段从服务端返回的 [OrderDto] 里读出来只读展示；
 * - 任何类型的单据都走同一个 [toCommand]，靠 [OrderFormState.orderType] 区分。
 */
object OrderFormComponent {

    /** 新建一张空表单。 */
    fun empty(orderType: OrderType): OrderFormState = OrderFormState(orderType = orderType)

    /** 选定客户（客户资料来自服务端）。 */
    fun withCustomer(state: OrderFormState, customer: CustomerDto): OrderFormState = state.copy(
        customerPhone = customer.phone,
        customerId = customer.id,
        customerName = customer.name,
        customerAddress = customer.address,
    )

    fun addLine(state: OrderFormState, line: OrderFormLine): OrderFormState =
        state.copy(lines = state.lines + line, failure = null)

    fun replaceLine(state: OrderFormState, index: Int, line: OrderFormLine): OrderFormState =
        state.copy(lines = state.lines.mapIndexed { i, current -> if (i == index) line else current })

    fun removeLine(state: OrderFormState, index: Int): OrderFormState =
        state.copy(lines = state.lines.filterIndexed { i, _ -> i != index })

    fun withRemark(state: OrderFormState, remark: String): OrderFormState = state.copy(remark = remark)

    /** 本地必填校验（只是省一次往返，业务校验仍在服务端）。 */
    fun localValidationFailure(state: OrderFormState): ApiResult.Failure? = when {
        state.customerId == null -> failure("请先选择客户")
        state.lines.isEmpty() -> failure("请至少录入一行明细")
        state.lines.any { it.quantity.isBlank() } -> failure("数量不能为空")
        else -> null
    }

    /**
     * 生成下单入参（唯一出口）。
     *
     * @param clientRequestId 幂等键：同一张单重试必须复用同一个 id（服务端据此去重）
     */
    fun toCommand(
        state: OrderFormState,
        clientRequestId: String,
        sourceOrderNo: String? = null,
    ): CreateOrderCmd {
        val customerId = requireNotNull(state.customerId) { "生成命令前必须先通过本地校验" }
        return CreateOrderCmd(
            clientRequestId = clientRequestId,
            orderType = state.orderType,
            customerId = customerId,
            lines = state.lines.map { line ->
                OrderLineCmd(
                    categoryId = line.categoryId,
                    batchTag = line.batchTag,
                    unit = line.unit,
                    quantity = line.quantity,
                )
            },
            remark = state.remark.ifBlank { null },
            sourceOrderNo = sourceOrderNo,
        )
    }

    /**
     * 用服务端订单回填表单（今日改单 / 退货单复用同一屏）。
     *
     * 明细逐字复制服务端产出的数量字符串，不做任何重新计算。
     */
    fun prefillFrom(order: OrderDto): OrderFormState = OrderFormState(
        orderType = order.orderType,
        customerPhone = order.customerPhone,
        customerId = order.customerId,
        customerName = order.customerName,
        customerAddress = order.customerAddress,
        lines = order.lines.map { it.toFormLine() },
        remark = order.remark.orEmpty(),
        submitted = order,
    )

    /** 屏规格：金额 / 单号字段一律只读（服务端产出）。 */
    fun screen(state: OrderFormState): ScreenSpec = ScreenSpec(
        id = AppRoutes.ORDER_ENTRY,
        title = "${state.orderType.label}录入",
        fields = listOf(
            FieldSpec("customer_phone", "客户电话", FieldType.PHONE),
            FieldSpec("customer_name", "客户", FieldType.TEXT, readOnly = true, required = false),
            FieldSpec("category", "型号", FieldType.SELECT),
            FieldSpec("batch_tag", "批号", FieldType.TEXT, required = false),
            FieldSpec("unit", "单位", FieldType.SELECT),
            FieldSpec("quantity", "数量", FieldType.DECIMAL),
            FieldSpec("order_no", "单号", FieldType.READONLY, readOnly = true, required = false),
            FieldSpec("total_amount", "总额", FieldType.READONLY, readOnly = true, required = false),
            FieldSpec("receivable_amount", "应收", FieldType.READONLY, readOnly = true, required = false),
            FieldSpec("remark", "备注", FieldType.MULTILINE, required = false),
        ),
        buttons = listOf(
            ButtonSpec("submit", "提交", ButtonAction.Submit),
            ButtonSpec("cancel", "取消", ButtonAction.Cancel),
        ),
    )

    private fun LineDto.toFormLine(): OrderFormLine = OrderFormLine(
        categoryId = categoryId,
        batchTag = batchTag,
        unit = unit,
        quantity = quantity,
    )

    private fun failure(message: String): ApiResult.Failure = ApiResult.Failure(
        wireCode = ErrorCode.E422.name,
        code = ErrorCode.E422,
        message = message,
        httpStatus = 422,
    )
}

/**
 * C06 下单录入页：4 类单据共用，只传 [orderType]（验收「4 类页共用组件；提交走服务端」）。
 *
 * 依赖：C00（接口）、C02（组件）。
 */
class OrderEntryRoute(
    private val client: ApiClient,
    val orderType: OrderType,
) {
    /** 初始表单。 */
    fun form(): OrderFormState = OrderFormComponent.empty(orderType)

    /** 按电话查客户（服务端聚合接口，客户端不查本地库）。 */
    fun lookupCustomer(phone: String): ApiResult<CustomerDto> = client.call(ApiPaths.customerByPhone(phone.trim()))

    /**
     * 提交录入（唯一写入口）。
     *
     * @param sourceOrderNo 退货单必须带原单号；其它类型为 null
     */
    @UiButton(id = "submit", label = "提交")
    fun submit(
        state: OrderFormState,
        clientRequestId: String,
        sourceOrderNo: String? = null,
    ): ApiResult<OrderDto> {
        OrderFormComponent.localValidationFailure(state)?.let { return it }
        val command = OrderFormComponent.toCommand(state, clientRequestId, sourceOrderNo)
        return when (val result = client.call(ApiPaths.createOrder(command))) {
            is ApiResult.Success -> result
            is ApiResult.Failure -> result
        }
    }

    companion object {
        /** 路由登记：四类单据共用一个路由 id（靠 `orderType` 区分）。 */
        val ENTRY: RouteEntry = RouteEntry(id = AppRoutes.ORDER_ENTRY, title = "下单录入")
    }
}
