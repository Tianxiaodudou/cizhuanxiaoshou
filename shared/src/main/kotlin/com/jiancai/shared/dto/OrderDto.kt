package com.jiancai.shared.dto

import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 订单抬头（唯一出参 DTO，S08 order 产出；S09/S10/S11/S12 与 C06/C07/C08 消费）。
 *
 * 金额（总额 / 应收 / 已收）与状态全部由服务端派生，客户端只展示。
 * [customerAddress] 是下单时的地址快照，不随客户资料修改而变化。
 * [version] 用于乐观锁，客户端改单时必须原样回传（见 [UpdateOrderCmd]）。
 */
@Serializable
data class OrderDto(
    @SerialName("order_no") val orderNo: String,
    @SerialName("order_type") val orderType: OrderType,
    @SerialName("status") val status: OrderStatus,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String,
    @SerialName("customer_address") val customerAddress: String? = null,
    @SerialName("order_date") val orderDate: String,
    @SerialName("lines") val lines: List<LineDto>,
    @SerialName("total_amount") val totalAmount: Money,
    @SerialName("receivable_amount") val receivableAmount: Money,
    @SerialName("received_amount") val receivedAmount: Money,
    @SerialName("remark") val remark: String? = null,
    @SerialName("version") val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: Long,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * 订单明细行（唯一出参 DTO）。
 *
 * 单价与金额由服务端按 S06 定价计算后下发，客户端只展示；
 * [quantity] 为十进制字符串（最多 3 位小数），由用户录入、服务端校验。
 */
@Serializable
data class LineDto(
    @SerialName("seq") val seq: Int,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("category_name") val categoryName: String,
    @SerialName("model") val model: String,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("unit") val unit: PricingUnit,
    @SerialName("quantity") val quantity: String,
    @SerialName("unit_price") val unitPrice: Money,
    @SerialName("amount") val amount: Money,
    @SerialName("packing_spec") val packingSpec: PackingSpec? = null,
)

/**
 * 下单入参（S08，4 类录入页共用；`orderType` 决定单号前缀与业务规则）。
 *
 * [clientRequestId] 是幂等键：S08 按它去重，重复提交返回同一张订单；
 * 客户端不得上传单价 / 金额 / 单号 / 日期，这些一律由服务端产出。
 */
@Serializable
data class CreateOrderCmd(
    @SerialName(FIELD_CLIENT_REQUEST_ID) val clientRequestId: String,
    @SerialName("order_type") val orderType: OrderType,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("lines") val lines: List<OrderLineCmd>,
    @SerialName("remark") val remark: String? = null,
)

/** 改单入参（S08，仅当日单；[version] 为乐观锁版本）。 */
@Serializable
data class UpdateOrderCmd(
    @SerialName("order_no") val orderNo: String,
    @SerialName("version") val version: Long,
    @SerialName("lines") val lines: List<OrderLineCmd>,
    @SerialName("remark") val remark: String? = null,
)

/** 订单行入参：只传型号 + 批号 + 单位 + 数量，价格由服务端解析。 */
@Serializable
data class OrderLineCmd(
    @SerialName("category_id") val categoryId: Long,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("unit") val unit: PricingUnit,
    @SerialName("quantity") val quantity: String,
)

/** 订单查询入参（C07 按电话查历史 / 其它列表页复用；日期交给服务端解析）。 */
@Serializable
data class OrderQuery(
    @SerialName("phone") val phone: String? = null,
    @SerialName("customer_id") val customerId: Long? = null,
    @SerialName("order_type") val orderType: OrderType? = null,
    @SerialName("from_date") val fromDate: String? = null,
    @SerialName("to_date") val toDate: String? = null,
    @SerialName("page") val page: Int = DEFAULT_PAGE,
    @SerialName("page_size") val pageSize: Int = DEFAULT_PAGE_SIZE,
)
