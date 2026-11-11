/*
 * @file fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleMath.kt
 * @role 结算金额派生（内部）：只做「服务端算金额」，不解析单号、不读写表。
 * @exports （无）
 * @depends shared
 * @calledBy fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.settlement

import com.jiancai.shared.dto.Money
import com.jiancai.shared.enums.SettleDirection
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 结算金额派生（内部）：只做「服务端算金额」，不解析单号、不读写表。
 *
 * 口径：
 * - 已收 = Σ 审核通过的收款 − Σ 审核通过的退款（按订单 / 按客户两种口径复用本函数）；
 * - 待结 = 应收（运行时从 S08 重新读取）− 已收；
 * - 收款要求 `待结 > 0` 且 `金额 ≤ 待结`；退款要求 `待结 < 0` 且 `金额 ≤ −待结`；
 * - 服务端只产出金额字符串，客户端只展示，不参与任何计算（铁律 5）。
 *
 * 金额全部经 [Money.ofPlain] 归一化为两位小数；不使用浮点数，避免精度丢失。
 */
internal object SettleMath {

    /** `Money` 的零值。 */
    val ZERO: Money = Money.ofPlain("0.00")

    /** 已收金额：审核通过的收款为正、退款为负。 */
    fun received(entries: List<StoredSettle>): Money =
        money(
            entries.fold(BigDecimal.ZERO) { total, entry ->
                total + signedValue(entry.direction, entry.amount)
            },
        )

    /** 求和（应收 / 未收读模型用）。 */
    fun sum(values: List<Money>): Money =
        money(values.fold(BigDecimal.ZERO) { total, value -> total + BigDecimal(value.amount) })

    /** 差：`minuend − subtrahend`。 */
    fun subtract(minuend: Money, subtrahend: Money): Money =
        money(BigDecimal(minuend.amount).subtract(BigDecimal(subtrahend.amount)))

    /** 取反：`−value`。 */
    fun negate(value: Money): Money = money(BigDecimal(value.amount).negate())

    /** 金额比较：返回负 / 零 / 正。 */
    fun compare(left: Money, right: Money): Int =
        BigDecimal(left.amount).compareTo(BigDecimal(right.amount))

    /** 统一收口：两位小数、`HALF_UP`、经共享契约归一化。 */
    fun money(value: BigDecimal): Money =
        Money.ofPlain(value.setScale(2, RoundingMode.HALF_UP).toPlainString())

    private fun signedValue(direction: SettleDirection, amount: Money): BigDecimal {
        val value = BigDecimal(amount.amount)
        return when (direction) {
            SettleDirection.RECEIPT -> value
            SettleDirection.REFUND -> value.negate()
        }
    }
}
