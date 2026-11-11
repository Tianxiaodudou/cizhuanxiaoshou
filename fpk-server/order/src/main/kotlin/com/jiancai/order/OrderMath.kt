/*
 * @file fpk-server/order/src/main/kotlin/com/jiancai/order/OrderMath.kt
 * @role 订单金额派生（内部）：只做「服务端算金额」，不解析单价、不生成单号。
 * @exports （无）
 * @depends shared
 * @calledBy fpk-server/order/src/main/kotlin/com/jiancai/order/OrderPriceRecalculator.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.order

import com.jiancai.shared.dto.Money
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 订单金额派生（内部）：只做「服务端算金额」，不解析单价、不生成单号。
 *
 * 口径：
 * - 单价只能来自 S06 `PricePort.resolve`，本类只把它乘以数量并四舍五入到分；
 * - 明细金额 = 单价 × 数量，`HALF_UP` 保留两位小数；
 * - 订单总额 = 明细金额之和（同为两位小数）；
 * - 应收 = 总额按单据方向取符号：采购 / 直送为正（客户应付），退货为负（应退客户）；
 * - 状态 = 已收 / 应收的纯函数：未收 → `CREATED`；部分 → `PARTIALLY_SETTLED`；收满 → `SETTLED`。
 *
 * 所有结果都经 [Money.ofPlain] 归一化，客户端只展示，不参与任何计算（铁律 5）。
 */
internal object OrderMath {

    /** 明细金额：单价 × 数量，四舍五入到分。数量已由 Service 校验为 > 0。 */
    fun amount(unitPrice: Money, quantity: String): Money {
        val value = BigDecimal(unitPrice.amount)
            .multiply(BigDecimal(quantity))
            .setScale(2, RoundingMode.HALF_UP)
        return Money.ofPlain(value.toPlainString())
    }

    /** 订单总额：明细金额求和（每条已是两位小数）。 */
    fun total(amounts: List<Money>): Money {
        val value = amounts.fold(BigDecimal.ZERO) { sum, amount -> sum + BigDecimal(amount.amount) }
            .setScale(2, RoundingMode.HALF_UP)
        return Money.ofPlain(value.toPlainString())
    }

    /**
     * 应收金额：采购 / 直送为正，退货为负。
     *
     * 退货单是「商家应退客户」，因此应收为负数，供 S11 结算时与原单应收冲抵。
     */
    fun receivable(orderType: OrderType, total: Money): Money {
        if (!isReturn(orderType)) return total
        val negative = BigDecimal(total.amount).negate().setScale(2, RoundingMode.HALF_UP)
        return Money.ofPlain(negative.toPlainString())
    }

    /** 退货类单据：采购退货 / 直送退货。 */
    fun isReturn(orderType: OrderType): Boolean =
        orderType == OrderType.PURCHASE_RETURN || orderType == OrderType.DIRECT_RETURN

    /**
     * 状态派生：只根据「已收 vs 应收」计算，不信任客户端传入的状态。
     *
     * v1.1.0 订单模块不接收结算回写，`receivedAmount` 建单时为 `0.00`，因此新单状态恒为
     * `CREATED`；未来 S11 回写已收后，本函数自然产出部分结清 / 已结清。
     */
    fun status(received: Money, receivable: Money): OrderStatus {
        if (BigDecimal(received.amount).signum() == 0) return OrderStatus.CREATED
        return if (BigDecimal(received.amount) >= BigDecimal(receivable.amount)) {
            OrderStatus.SETTLED
        } else {
            OrderStatus.PARTIALLY_SETTLED
        }
    }
}
