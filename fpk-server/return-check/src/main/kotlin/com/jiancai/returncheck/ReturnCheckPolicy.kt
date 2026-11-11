/*
 * @file fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnCheckPolicy.kt
 * @role 退单校验策略（S09 的**唯一**规则开关，独立于 Port 与读取路径，可整体替换升级）。
 * @exports ReturnCheckPolicy
 * @depends shared
 * @calledBy fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnCheckService.kt, fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnRules.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.returncheck

import com.jiancai.shared.enums.OrderType

/**
 * 退单校验策略（S09 的**唯一**规则开关，独立于 Port 与读取路径，可整体替换升级）。
 *
 * 设计意图（验收「规则可独立升级」）：本模块特有的判定口径（哪些单据可退、作废退货单是否占额度、
 * 是否强制要求退货单显式关联原单、行数 / 翻页上限）全部收敛在这一个不可变 data class 里，
 * [ReturnRules] 只读它、[ReturnCheckService] 只透传它。业务口径变化时只改这里（或组合根注入
 * 另一个实例），Port 签名（`availableBatches` / `validate`）与读取路径完全不动。
 *
 * 注意：**跨端共享的口径不在本策略里**，它们以 shared 为唯一真源，本模块只调用、不复制：
 * - 数量格式 / 小数位上限 → `Quantity.ofPlain` + `WireFormat.QUANTITY_MAX_SCALE`；
 * - 批号 trim / 大小写比较 → `BatchTagRules` + `WireFormat.BATCH_TAG_*`。
 *
 * @property returnableOrderTypes 允许退货的原单类型（默认只有采购 / 直送；退货单不能再退）
 * @property returnOrderTypes 计入「已退数量」的单据类型（默认两种退货单）
 * @property countVoidReturnOrders 已作废的退货单是否仍占用可退数量（默认否：作废不占额度）
 * @property requireSourceOrderLink 是否只承认显式关联到本原单（`source_order_no` 命中）的退货单；
 *   默认 `false` 兼容 S08 尚未回填 `source_order_no` 的历史数据（见 module.md 落地假设 2）
 * @property maxLines 单次校验允许的明细行数（与 S08 的 200 行上限一致，超出抛 `E413`）
 * @property pageSize 翻退货单时的每页条数（S08 `getRecent` 上限 200）
 * @property maxReturnOrderPages 翻退货单的最大页数（超出抛 `E413`，避免无限翻页）
 */
data class ReturnCheckPolicy(
    val returnableOrderTypes: Set<OrderType> = setOf(OrderType.PURCHASE, OrderType.DIRECT),
    val returnOrderTypes: Set<OrderType> = setOf(OrderType.PURCHASE_RETURN, OrderType.DIRECT_RETURN),
    val countVoidReturnOrders: Boolean = false,
    val requireSourceOrderLink: Boolean = false,
    val maxLines: Int = 200,
    val pageSize: Int = 200,
    val maxReturnOrderPages: Int = 50,
) {

    init {
        require(returnableOrderTypes.isNotEmpty()) { "returnableOrderTypes 不能为空" }
        require(returnOrderTypes.isNotEmpty()) { "returnOrderTypes 不能为空" }
        require(maxLines in 1..MAX_LINES) { "maxLines 必须在 1–$MAX_LINES：$maxLines" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize 必须在 1–$MAX_PAGE_SIZE：$pageSize" }
        require(maxReturnOrderPages >= 1) { "maxReturnOrderPages 必须 ≥ 1：$maxReturnOrderPages" }
    }

    companion object {
        /** S08 `getRecent` 的每页上限（超出 S08 会抛 `E422`），策略不得超过。 */
        const val MAX_PAGE_SIZE: Int = 200

        /** S08 单张订单的明细行上限，策略不得超过。 */
        const val MAX_LINES: Int = 200
    }
}
