/*
 * @file fpk-server/order/src/main/kotlin/com/jiancai/order/OrderPriceRecalculator.kt
 * @role `PriceChanged` 的 S08 侧实现：重算受影响的**当日订单**（验收「改价联动重算」）。
 * @exports OrderPriceRecalculator
 * @depends db-core, pricing, shared
 * @calledBy （无）
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.order

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.pricing.PriceChanged
import com.jiancai.pricing.PriceChangeListener
import com.jiancai.shared.dto.BatchTagRules
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.errors.ErrorCode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * `PriceChanged` 的 S08 侧实现：重算受影响的**当日订单**（验收「改价联动重算」）。
 *
 * 为什么单独一个类而不是让 [OrderService] 同时实现监听器：
 * `PriceService` 构造时要注入监听器，而 [OrderService] 构造时要注入 `PricePort`，
 * 直接互相引用会形成构造环。组合根按下面顺序装配即可：
 *
 * ```
 * val recalculator = OrderPriceRecalculator(db.txRunner, clock)
 * val pricing = PriceService(db.txRunner, customers, categories, clock, listOf(recalculator))
 * val order = OrderService(db.txRunner, serial, pricing, customers, categories, clock)
 * ```
 *
 * 事务口径：S06 在**改价事务内**同步调用 [onPriceChanged]，本类用同一个 `txRunner.run {}`
 * 包住重算——同一线程的嵌套 `run` 复用 S06 的事务（db-core 的 SAVEPOINT 语义），
 * 因此「改价 + 留痕 + 订单重算」要么全部提交、要么全部回滚。重算失败会让 S06 的改价一起回滚，
 * 所以本类只做可回滚的数据库写入，不做任何外部副作用。
 *
 * 重算口径：
 * - 只重算 `order_date` = 服务端今天、状态为 `CREATED` / `PARTIALLY_SETTLED` 的订单；
 * - 只替换命中价格维度（客户 + 型号 + 批号）的明细单价与金额，数量 / 型号快照 / 备注不变；
 * - 总额、应收、状态按新明细重新派生，`version + 1` 让客户端知道订单已变化；
 * - 若某订单的金额没有任何变化（重复事件 / 同价事件），跳过且不推进版本，保证幂等。
 *
 * @param txRunner db-core 的事务 Port（与 S06 改价共享同一事务）
 * @param clock 服务端时钟；业务日期决定「当日订单」范围
 */
class OrderPriceRecalculator(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : PriceChangeListener {

    private val repository = OrderRepository()

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.DATE)

    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)

    override fun onPriceChanged(event: PriceChanged) {
        // 嵌套事务：S06 已在外层事务内，这里复用同一事务（SAVEPOINT），不会提前提交。
        txRunner.run { tx -> recalculate(tx, event) }
    }

    private fun recalculate(tx: TxContext, event: PriceChanged) {
        val orderDate = LocalDate.now(clock).format(dateFormat)
        val orderNos = repository.findSameDayOrderNosByPrice(
            tx = tx,
            customerId = event.customerId,
            categoryId = event.categoryId,
            batchTag = event.batchTag,
            orderDate = orderDate,
            editableStatuses = EDITABLE_STATUSES,
        )

        orderNos.forEach { orderNo ->
            val header = repository.findByOrderNo(tx, orderNo) ?: return@forEach
            val lines = repository.findLines(tx, orderNo)
            var changed = false

            val updatedLines = lines.map { line ->
                val hit = line.categoryId == event.categoryId &&
                    BatchTagRules.same(line.batchTag, event.batchTag)
                if (!hit) return@map line

                val newAmount = OrderMath.amount(event.newPrice, line.quantity)
                if (line.unitPrice == event.newPrice && line.amount == newAmount) {
                    return@map line
                }
                repository.updateLinePricing(tx, orderNo, line.seq, event.newPrice, newAmount)
                changed = true
                line.copy(unitPrice = event.newPrice, amount = newAmount)
            }

            // 重复事件 / 同价事件：金额没有变化，不推进版本，保证监听器幂等。
            if (!changed) return@forEach

            val total = OrderMath.total(updatedLines.map { it.amount })
            val receivable = OrderMath.receivable(header.orderType, total)
            val updated = repository.updateHeader(
                tx = tx,
                orderNo = orderNo,
                expectedVersion = header.version,
                newVersion = header.version + 1,
                status = OrderMath.status(header.receivedAmount, receivable),
                totalAmount = total,
                receivableAmount = receivable,
                remark = header.remark,
                updatedAt = OffsetDateTime.now(clock).format(timestampFormat),
            )
            if (!updated) {
                throw OrderException(
                    ErrorCode.E2002,
                    "改价重算失败：订单已被并发修改（$orderNo，版本 ${header.version}）",
                )
            }
        }
    }

    private companion object {
        /** 只有未结清、未作废的订单参与价格重算。 */
        val EDITABLE_STATUSES: List<OrderStatus> = listOf(
            OrderStatus.CREATED,
            OrderStatus.PARTIALLY_SETTLED,
        )
    }
}
