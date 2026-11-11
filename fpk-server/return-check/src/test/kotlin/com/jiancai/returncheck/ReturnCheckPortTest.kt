/*
 * @file fpk-server/return-check/src/test/kotlin/com/jiancai/returncheck/ReturnCheckPortTest.kt
 * @role 集成用例：真实 S04 / S05 / S06 / S07 / S08（同一内存库），本模块只经 `OrderPort` 读取。
 * @exports ReturnCheckPortTest
 * @depends db-core, order, shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.returncheck

import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.order.OrderPort
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 集成用例：真实 S04 / S05 / S06 / S07 / S08（同一内存库），本模块只经 `OrderPort` 读取。
 *
 * 覆盖验收：
 * - 可退数量 = 原单数量 − 该客户已开退货单数量（按型号 + 批号 + 单位聚合）；
 * - 批次校验（不在原单上 / 超可退量 / 格式非法 / 单据类型不可退）全部给出 S09 错误码；
 * - 只读：validate 不建单、不改单、不写任何表；
 * - 事务：带 tx 重载复用调用方事务，便捷重载自开一个只读事务。
 */
class ReturnCheckPortTest {

    // —— 可退数量计算 ——

    @Test
    fun available_batches_subtract_returns_of_the_same_customer_and_batch() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(batchTags = listOf("A", "B"))
            fixture.price(customer.id, category.id, batchTag = "A")
            fixture.price(customer.id, category.id, batchTag = "B")

            val original = fixture.order(
                customerId = customer.id,
                lines = listOf(lineCmd(category.id, "10", "A"), lineCmd(category.id, "5", "B")),
            )
            fixture.order(
                customerId = customer.id,
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(lineCmd(category.id, "4", "A")),
            )

            val batches = fixture.returnCheck().availableBatches(original)

            assertEquals(2, batches.size)
            val batchA = batches.single { it.batchTag == "A" }
            assertEquals("10", batchA.orderedQuantity)
            assertEquals("4", batchA.returnedQuantity)
            assertEquals("6", batchA.returnableQuantity)
            assertEquals(original.orderNo, batchA.orderNo)
            assertEquals("5", batches.single { it.batchTag == "B" }.returnableQuantity)
        }
    }

    @Test
    fun available_batches_aggregate_repeated_batch_lines_on_the_original_order() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)

            val original = fixture.order(
                customerId = customer.id,
                lines = listOf(lineCmd(category.id, "3", "A"), lineCmd(category.id, "2", "A")),
            )

            val batches = fixture.returnCheck().availableBatches(original)

            assertEquals(1, batches.size, "同型号同批号的多行合成一个批次")
            assertEquals("5", batches.single().orderedQuantity)
            assertEquals("5", batches.single().returnableQuantity)
        }
    }

    @Test
    fun returns_of_another_customer_do_not_consume_the_returnable_quantity() {
        Fixture().use { fixture ->
            val first = fixture.customer(phone = "13800000000", name = "张三")
            val second = fixture.customer(phone = "13900000000", name = "李四")
            val category = fixture.category()
            fixture.price(first.id, category.id)
            fixture.price(second.id, category.id)

            val original = fixture.order(customerId = first.id, lines = listOf(lineCmd(category.id, "10")))
            fixture.order(
                customerId = second.id,
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(lineCmd(category.id, "6")),
            )

            val batch = fixture.returnCheck().availableBatches(original).single()
            assertEquals("0", batch.returnedQuantity, "别人的退货不能算到当前客户头上")
            assertEquals("10", batch.returnableQuantity)
        }
    }

    @Test
    fun a_return_order_itself_offers_no_returnable_batches() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val returnOrder = fixture.order(
                customerId = customer.id,
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(lineCmd(category.id, "2")),
            )

            assertTrue(fixture.returnCheck().availableBatches(returnOrder).isEmpty(), "退货单不能再退")
        }
    }

    // —— 批次校验 ——

    @Test
    fun validate_accepts_a_return_within_the_available_quantity() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "10")))
            val port = fixture.returnCheck()

            val result = port.validate(original, listOf(lineCmd(category.id, "2.5")))

            val line = result.lines.single()
            assertEquals(original.orderNo, result.orderNo)
            assertEquals("2.5", line.requestedQuantity)
            assertEquals("10", line.returnableQuantity)
            assertEquals("7.5", line.remainingQuantity)
            assertEquals("A", line.batchTag)
        }
    }

    @Test
    fun validate_rejects_a_batch_that_is_not_on_the_original_order_with_e3005() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(batchTags = listOf("A", "B"))
            fixture.price(customer.id, category.id, batchTag = "A")
            fixture.price(customer.id, category.id, batchTag = "B")
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "3", "A")))

            assertReturnError(ErrorCode.E3005) {
                fixture.returnCheck().validate(original, listOf(lineCmd(category.id, "1", "B")))
            }
        }
    }

    @Test
    fun validate_rejects_a_voided_original_with_e3004() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "3")))

            // 订单仍存在（getReceivable 通过），但状态是 VOID：1.1.0 起用专用码 E3004。
            val voided = original.copy(status = OrderStatus.VOID)

            assertReturnError(ErrorCode.E3004) {
                fixture.returnCheck().validate(voided, listOf(lineCmd(category.id, "1")))
            }
        }
    }

    @Test
    fun validate_rejects_a_fully_returned_batch_with_e3002() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "4")))
            fixture.order(
                customerId = customer.id,
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(lineCmd(category.id, "4")),
            )

            assertReturnError(ErrorCode.E3002) {
                fixture.returnCheck().validate(original, listOf(lineCmd(category.id, "1")))
            }
        }
    }

    @Test
    fun validate_counts_previous_returns_before_rejecting_with_e3003() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "10")))
            fixture.order(
                customerId = customer.id,
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(lineCmd(category.id, "8")),
            )

            assertReturnError(ErrorCode.E3003) {
                fixture.returnCheck().validate(original, listOf(lineCmd(category.id, "3")))
            }
            // 边界值（正好退完）允许通过。
            val result = fixture.returnCheck().validate(original, listOf(lineCmd(category.id, "2")))
            assertEquals("0", result.lines.single().remainingQuantity)
        }
    }

    @Test
    fun a_missing_original_order_is_reported_as_e3001() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val ghost = orderDto(orderNo = "PS202611119999", customerId = customer.id)

            assertReturnError(ErrorCode.E3001) { fixture.returnCheck().availableBatches(ghost) }
            assertReturnError(ErrorCode.E3001) {
                fixture.returnCheck().validate(ghost, listOf(lineCmd(categoryId = 101, quantity = "1")))
            }
        }
    }

    // —— 只读 / 事务 ——

    @Test
    fun validate_and_availability_never_create_or_change_an_order() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "10")))
            val before = fixture.orders.getRecent(OrderQuery(customerId = customer.id)).total

            val port = fixture.returnCheck()
            port.availableBatches(original)
            port.validate(original, listOf(lineCmd(category.id, "1")))

            assertEquals(before, fixture.orders.getRecent(OrderQuery(customerId = customer.id)).total)
            assertEquals(1L, original.version, "本模块不改订单版本")
        }
    }

    @Test
    fun convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val original = fixture.order(customerId = customer.id, lines = listOf(lineCmd(category.id, "10")))

            val counting = CountingTxRunner(fixture.txRunner)
            val port = ReturnCheckService(counting, fixture.orders)

            port.availableBatches(original)
            assertEquals(1, counting.runs, "便捷重载自己开一个只读事务")

            counting.reset()
            fixture.txRunner.run { tx -> port.availableBatches(tx, original) }
            assertEquals(0, counting.runs, "带 tx 重载必须复用调用方事务，不再自开事务")

            counting.reset()
            port.validate(original, listOf(lineCmd(category.id, "1")))
            assertEquals(1, counting.runs)

            counting.reset()
            fixture.txRunner.run { tx -> port.validate(tx, original, listOf(lineCmd(category.id, "1"))) }
            assertEquals(0, counting.runs)
        }
    }

    // —— 翻页（用假 Port，避免为了翻页造 200+ 张真实订单）——

    @Test
    fun return_orders_are_read_page_by_page_until_the_total_is_reached() {
        val customerId = 42L
        val categoryId = 7L
        val returns = (1..250).map { index ->
            orderDto(
                orderNo = "PT2026111%05d".format(index),
                orderType = OrderType.PURCHASE_RETURN,
                customerId = customerId,
                lines = listOf(lineDto(categoryId = categoryId, quantity = "1")),
            )
        }
        val fake = FakeOrderPort(returns)
        DbCore.inMemory().use { db ->
            val port = ReturnCheckService(db.txRunner, fake, ReturnCheckPolicy(pageSize = 100))
            val original = orderDto(
                orderNo = "PS202611110001",
                customerId = customerId,
                lines = listOf(lineDto(categoryId = categoryId, quantity = "300")),
            )

            val batch = port.availableBatches(original).single()

            assertEquals("250", batch.returnedQuantity, "250 张退货单要全部读到，而不是只看第一页")
            assertEquals("50", batch.returnableQuantity)
            // 采购退货 3 页（250 条 / 每页 100）+ 直送退货 1 次空页。
            assertEquals(4, fake.getRecentCalls)
        }
    }

    @Test
    fun runaway_paging_is_stopped_with_e413_instead_of_looping_forever() {
        val returns = (1..250).map { index ->
            orderDto(
                orderNo = "PT2026111%05d".format(index),
                orderType = OrderType.PURCHASE_RETURN,
                customerId = 42L,
                lines = listOf(lineDto(quantity = "1")),
            )
        }
        val fake = FakeOrderPort(returns)
        DbCore.inMemory().use { db ->
            val port = ReturnCheckService(
                db.txRunner,
                fake,
                ReturnCheckPolicy(pageSize = 10, maxReturnOrderPages = 2),
            )

            assertReturnError(ErrorCode.E413) { port.availableBatches(orderDto(customerId = 42L)) }
        }
    }

    @Test
    fun returns_linked_to_another_order_do_not_consume_the_returnable_quantity() {
        val customerId = 42L
        val returns = listOf(
            orderDto(
                orderNo = "PT202611110001",
                orderType = OrderType.PURCHASE_RETURN,
                customerId = customerId,
                sourceOrderNo = "PS202611110999",
                lines = listOf(lineDto(quantity = "6")),
            ),
        )
        val fake = FakeOrderPort(returns)
        DbCore.inMemory().use { db ->
            val port = ReturnCheckService(db.txRunner, fake)
            val original = orderDto(
                orderNo = "PS202611110001",
                customerId = customerId,
                lines = listOf(lineDto(quantity = "10")),
            )

            val batch = port.availableBatches(original).single()

            assertEquals("0", batch.returnedQuantity, "1.1.0 的 sourceOrderNo 指向别的原单，不能扣这张单")
            assertEquals("10", batch.returnableQuantity)
        }
    }

    @Test
    fun returns_linked_to_this_order_are_subtracted_even_when_strict() {
        val customerId = 42L
        val returns = listOf(
            orderDto(
                orderNo = "PT202611110001",
                orderType = OrderType.PURCHASE_RETURN,
                customerId = customerId,
                sourceOrderNo = "PS202611110001",
                lines = listOf(lineDto(quantity = "6")),
            ),
        )
        val fake = FakeOrderPort(returns)
        DbCore.inMemory().use { db ->
            val port = ReturnCheckService(
                db.txRunner,
                fake,
                ReturnCheckPolicy(requireSourceOrderLink = true),
            )
            val original = orderDto(
                orderNo = "PS202611110001",
                customerId = customerId,
                lines = listOf(lineDto(quantity = "10")),
            )

            val batch = port.availableBatches(original).single()

            assertEquals("6", batch.returnedQuantity)
            assertEquals("4", batch.returnableQuantity)
        }
    }

    /**
     * 假 Port：只实现本模块用得到的两个读方法，其余写方法一律失败。
     *
     * 这不是 Mock 框架，而是「本模块不得写订单」的运行时断言。
     */
    private class FakeOrderPort(private val returnOrders: List<OrderDto>) : OrderPort {

        var getRecentCalls: Int = 0
            private set

        private fun pick(query: OrderQuery): PageDto<OrderDto> {
            val matched = returnOrders.filter { order ->
                (query.customerId == null || order.customerId == query.customerId) &&
                    (query.orderType == null || order.orderType == query.orderType)
            }
            val from = ((query.page - 1) * query.pageSize).coerceAtLeast(0)
            val to = (from + query.pageSize).coerceAtMost(matched.size)
            val items = if (from >= matched.size) emptyList() else matched.subList(from, to)
            return PageDto(items = items, total = matched.size.toLong(), page = query.page, pageSize = query.pageSize)
        }

        override fun getRecent(tx: TxContext, query: OrderQuery): PageDto<OrderDto> {
            getRecentCalls += 1
            return pick(query)
        }

        override fun getRecent(query: OrderQuery): PageDto<OrderDto> = pick(query)

        // 1.2.0 起 OrderPort 追加按单号只读查询；S09 只读，直接按单号过滤。
        override fun get(tx: TxContext, orderNo: String): OrderDto? =
            returnOrders.firstOrNull { it.orderNo == orderNo }

        override fun get(orderNo: String): OrderDto? =
            returnOrders.firstOrNull { it.orderNo == orderNo }

        override fun getReceivable(tx: TxContext, orderNo: String): Money = Money.ofPlain("0.00")

        override fun getReceivable(orderNo: String): Money = Money.ofPlain("0.00")

        override fun today(tx: TxContext): List<OrderDto> = emptyList()

        override fun today(): List<OrderDto> = emptyList()

        override fun create(tx: TxContext, cmd: CreateOrderCmd, createdBy: Long): OrderDto =
            throw UnsupportedOperationException("S09 不得建单")

        override fun create(cmd: CreateOrderCmd, createdBy: Long): OrderDto =
            throw UnsupportedOperationException("S09 不得建单")

        override fun update(tx: TxContext, cmd: UpdateOrderCmd): OrderDto =
            throw UnsupportedOperationException("S09 不得改单")

        override fun update(cmd: UpdateOrderCmd): OrderDto =
            throw UnsupportedOperationException("S09 不得改单")

        // 1.1.0 起 OrderPort 新增取号方法（S10 用）；S09 只读，一律不实现。
        override fun nextNoteNo(tx: TxContext): String =
            throw UnsupportedOperationException("S09 不取号（铁律 8：取号只归 S07 / S08）")

        override fun nextNoteNo(): String =
            throw UnsupportedOperationException("S09 不取号（铁律 8：取号只归 S07 / S08）")
    }
}
