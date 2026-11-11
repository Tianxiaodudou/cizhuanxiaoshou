/*
 * @file fpk-server/order/src/test/kotlin/com/jiancai/order/OrderPortTest.kt
 * @role S08 订单用例测试（验收：幂等、乐观锁、改当日单、应收派生、改价重算）。
 * @exports OrderPortTest
 * @depends pricing, shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.order

import com.jiancai.pricing.PriceChanged
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S08 订单用例测试（验收：幂等、乐观锁、改当日单、应收派生、改价重算）。
 *
 * 所有用例用独立内存库 + 固定时钟；跨日场景通过 `Fixture.ordersAt(...)` 换一个服务端日期，
 * 不修改系统时间、不依赖测试执行时刻。
 */
class OrderPortTest {

    // —— 建单 / 服务端产出 ——

    @Test
    fun create_takes_the_number_from_serial_and_derives_amounts_and_receivable() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            val order = fixture.newOrder(customer.id, category.id, quantity = "2")

            assertTrue(
                Regex("""PS\d{12}""").matches(order.orderNo),
                "单号必须由 S07 按 PS + yyyyMMdd + 4 位序号产出：${order.orderNo}",
            )
            assertEquals(OrderType.PURCHASE, order.orderType)
            assertEquals(OrderStatus.CREATED, order.status)
            assertEquals(customer.id, order.customerId)
            assertEquals(customer.name, order.customerName)
            assertEquals(customer.phone, order.customerPhone)
            assertEquals(customer.address, order.customerAddress, "地址是下单时的客户快照")
            assertEquals(TODAY, order.orderDate)
            assertEquals("24.60", order.totalAmount.amount)
            assertEquals("24.60", order.receivableAmount.amount)
            assertEquals("0.00", order.receivedAmount.amount)
            assertEquals(1L, order.version)
            assertEquals(OPERATOR_ID, order.createdBy)
            assertEquals(NOW_TIMESTAMP, order.createdAt)
            assertEquals(null, order.updatedAt)

            val line = order.lines.single()
            assertEquals(1, line.seq)
            assertEquals(category.id, line.categoryId)
            assertEquals("水泥", line.categoryName)
            assertEquals("P.O 42.5", line.model)
            assertEquals("A", line.batchTag)
            assertEquals(PricingUnit.TON, line.unit)
            assertEquals("2", line.quantity)
            assertEquals("12.30", line.unitPrice.amount)
            assertEquals("24.60", line.amount.amount)
            assertEquals("50kg/袋", line.packingSpec?.specText)

            assertEquals("24.60", fixture.orders.getReceivable(order.orderNo).amount)
            assertEquals(1L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM order_header") })
            assertEquals(1L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM order_line") })
        }
    }

    @Test
    fun all_four_order_types_use_the_serial_prefix_and_return_orders_are_negative_receivable() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            val cases = listOf(
                Triple(OrderType.PURCHASE, "PS", "24.60"),
                Triple(OrderType.PURCHASE_RETURN, "PT", "-24.60"),
                Triple(OrderType.DIRECT, "ZS", "24.60"),
                Triple(OrderType.DIRECT_RETURN, "ZT", "-24.60"),
            )
            cases.forEachIndexed { index, (type, prefix, receivable) ->
                val order = fixture.newOrder(
                    customerId = customer.id,
                    categoryId = category.id,
                    orderType = type,
                    requestId = "prefix-$index",
                )
                assertTrue(order.orderNo.startsWith(prefix), "${type.label} 单号前缀应为 $prefix：${order.orderNo}")
                assertEquals("24.60", order.totalAmount.amount, "退货单总额仍为正数")
                assertEquals(receivable, order.receivableAmount.amount, "退货单应收为负")
                assertEquals(receivable, fixture.orders.getReceivable(order.orderNo).amount)
            }
        }
    }

    @Test
    fun quantity_is_normalized_and_amounts_round_half_up_to_cents() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            val order = fixture.newOrder(customer.id, category.id, quantity = "0.333")

            assertEquals("0.333", order.lines.single().quantity)
            assertEquals("4.10", order.lines.single().amount.amount, "4.0959 四舍五入为 4.10")
            assertEquals("4.10", order.totalAmount.amount)

            val trailing = fixture.newOrder(
                customer.id,
                category.id,
                quantity = "1.500",
                requestId = "trailing",
            )
            assertEquals("1.5", trailing.lines.single().quantity, "尾零由服务端归一")
            assertEquals("18.45", trailing.lines.single().amount.amount)
        }
    }

    // —— 幂等 ——

    @Test
    fun same_client_request_id_returns_the_same_order_without_duplicating_rows_or_serials() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            val first = fixture.newOrder(customer.id, category.id, requestId = "same-request", quantity = "2")
            val second = fixture.newOrder(customer.id, category.id, requestId = "same-request", quantity = "9")

            assertEquals(first.orderNo, second.orderNo, "重复提交必须返回同一张订单")
            assertEquals(first, second)
            assertEquals("2", second.lines.single().quantity, "重复提交不得覆盖首单明细")
            assertEquals(1L, second.version)
            assertEquals(1L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM order_header") })
            assertEquals(1L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM serial_number") })
        }
    }

    // —— 改单 / 乐观锁 / 当日单 ——

    @Test
    fun update_recomputes_amounts_receivable_and_bumps_version() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id, quantity = "2")

            val updated = fixture.orders.update(
                updateCmd(created, quantity = "3", remark = "  改数量  "),
            )

            assertEquals(created.orderNo, updated.orderNo)
            assertEquals(2L, updated.version)
            assertEquals("36.90", updated.totalAmount.amount)
            assertEquals("36.90", updated.receivableAmount.amount)
            assertEquals("36.90", fixture.orders.getReceivable(created.orderNo).amount)
            assertEquals("3", updated.lines.single().quantity)
            assertEquals("36.90", updated.lines.single().amount.amount)
            assertEquals("改数量", updated.remark, "备注由服务端裁剪首尾空白")
            assertEquals(NOW_TIMESTAMP, updated.updatedAt)
        }
    }

    @Test
    fun a_stale_version_is_rejected_with_e2002_and_writes_nothing() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id, quantity = "2")

            val updated = fixture.orders.update(updateCmd(created, quantity = "3"))
            assertEquals(2L, updated.version)

            assertOrderError(ErrorCode.E2002) {
                fixture.orders.update(updateCmd(created, quantity = "5", version = created.version))
            }

            val current = fixture.orders.today().single()
            assertEquals(2L, current.version, "失败请求不得推进版本")
            assertEquals("36.90", current.totalAmount.amount, "失败请求不得改金额")
            assertEquals("3", current.lines.single().quantity)
        }
    }

    @Test
    fun only_same_day_orders_can_be_updated() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id)

            assertOrderError(ErrorCode.E2003) {
                fixture.ordersAt(TOMORROW_INSTANT).update(updateCmd(created, quantity = "4"))
            }

            assertTrue(fixture.ordersAt(TOMORROW_INSTANT).today().isEmpty(), "次日没有订单")
            assertEquals(1, fixture.orders.today().size)
            assertEquals(1L, fixture.orders.today().single().version)
        }
    }

    @Test
    fun a_settled_or_void_order_cannot_be_updated() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id)

            fixture.txRunner.run { tx ->
                tx.exec("UPDATE order_header SET status = 'SETTLED' WHERE order_no = ?", created.orderNo)
            }
            assertOrderError(ErrorCode.E2004) {
                fixture.orders.update(updateCmd(created, quantity = "3"))
            }

            fixture.txRunner.run { tx ->
                tx.exec("UPDATE order_header SET status = 'VOID' WHERE order_no = ?", created.orderNo)
            }
            assertOrderError(ErrorCode.E2003) {
                fixture.orders.update(updateCmd(created, quantity = "3"))
            }
        }
    }

    @Test
    fun update_of_an_unknown_order_is_rejected_with_e2001() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            assertOrderError(ErrorCode.E2001) {
                fixture.orders.update(
                    UpdateOrderCmd(
                        orderNo = "PS202611119999",
                        version = 1,
                        lines = listOf(com.jiancai.shared.dto.OrderLineCmd(category.id, "A", PricingUnit.TON, "2")),
                    ),
                )
            }
            assertOrderError(ErrorCode.E2001) { fixture.orders.getReceivable("PS202611119999") }
            assertEquals(customer.id, customer.id)
        }
    }

    // —— 入参校验 ——

    @Test
    fun empty_lines_fail_with_e2005() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            assertOrderError(ErrorCode.E2005) {
                fixture.orders.create(
                    createCmd(customer.id, category.id, lines = emptyList()),
                    OPERATOR_ID,
                )
            }
            assertEquals(0L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM order_header") })
        }
    }

    @Test
    fun quantities_must_be_positive_and_at_most_three_decimals() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            listOf(
                "0" to ErrorCode.E2006,
                "-1" to ErrorCode.E2006,
                "-0.5" to ErrorCode.E2006,
                "0.000" to ErrorCode.E2006,
                "1.2345" to ErrorCode.E422,
                "abc" to ErrorCode.E422,
                "1e3" to ErrorCode.E422,
            ).forEachIndexed { index, (quantity, code) ->
                assertOrderError(code) {
                    fixture.newOrder(
                        customer.id,
                        category.id,
                        quantity = quantity,
                        requestId = "quantity-$index",
                    )
                }
            }
        }
    }

    @Test
    fun batch_tag_must_match_the_model_and_is_case_insensitive() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            assertOrderError(ErrorCode.E2007) {
                fixture.newOrder(customer.id, category.id, batchTag = "C", requestId = "batch-c")
            }
            assertOrderError(ErrorCode.E2007) {
                fixture.newOrder(customer.id, category.id, batchTag = "", requestId = "batch-empty")
            }

            val lower = fixture.newOrder(customer.id, category.id, batchTag = "a", requestId = "batch-lower")
            assertEquals("A", lower.lines.single().batchTag, "批号保留型号库里的规范写法")

            val noTagCategory = fixture.category(
                categoryName = "瓷砖",
                model = "800×800",
                unit = PricingUnit.PIECE,
                batchTagRequired = false,
                batchTags = emptyList(),
                packingSpec = null,
            )
            fixture.price(customer.id, noTagCategory.id, unitPrice = "5.00", batchTag = "")
            assertOrderError(ErrorCode.E2007) {
                fixture.newOrder(
                    customer.id,
                    noTagCategory.id,
                    batchTag = "A",
                    unit = PricingUnit.PIECE,
                    requestId = "no-tag-a",
                )
            }
            val noTag = fixture.newOrder(
                customer.id,
                noTagCategory.id,
                batchTag = "",
                unit = PricingUnit.PIECE,
                requestId = "no-tag-ok",
            )
            assertEquals("", noTag.lines.single().batchTag)
        }
    }

    @Test
    fun the_line_unit_must_match_the_model_unit() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            assertOrderError(ErrorCode.E422) {
                fixture.newOrder(customer.id, category.id, unit = PricingUnit.PIECE)
            }
        }
    }

    @Test
    fun a_missing_price_is_reported_with_e1002_and_an_unknown_customer_with_e404() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            val unpriced = fixture.customer(phone = "13900000000", name = "李四")

            assertOrderError(ErrorCode.E1002) {
                fixture.newOrder(unpriced.id, category.id, requestId = "no-price")
            }
            assertOrderError(ErrorCode.E404) {
                fixture.orders.create(createCmd(9999, category.id, requestId = "no-customer"), OPERATOR_ID)
            }
            assertEquals(customer.id, customer.id)
        }
    }

    @Test
    fun return_orders_persist_source_order_no_and_keep_it_immutable() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val original = fixture.newOrder(
                customer.id,
                category.id,
                quantity = "10",
                requestId = "source-original",
            )

            val returned = fixture.newOrder(
                customerId = customer.id,
                categoryId = category.id,
                quantity = "4",
                orderType = OrderType.PURCHASE_RETURN,
                requestId = "source-return",
                sourceOrderNo = original.orderNo,
            )

            assertEquals(original.orderNo, returned.sourceOrderNo)
            assertNull(original.sourceOrderNo, "正向单不能凭空带原单号")
            assertEquals(
                original.orderNo,
                fixture.txRunner.run { tx ->
                    tx.queryString("SELECT source_order_no FROM order_header WHERE order_no = ?", returned.orderNo)
                },
            )

            val updated = fixture.orders.update(updateCmd(returned, quantity = "3"))
            assertEquals(original.orderNo, updated.sourceOrderNo, "改单不可变更原单号")
            assertEquals(2L, updated.version)
        }
    }

    @Test
    fun non_return_orders_cannot_carry_a_source_order_no() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val original = fixture.newOrder(customer.id, category.id, requestId = "source-for-non-return")

            assertOrderError(ErrorCode.E422) {
                fixture.newOrder(
                    customerId = customer.id,
                    categoryId = category.id,
                    orderType = OrderType.PURCHASE,
                    requestId = "non-return-with-source",
                    sourceOrderNo = original.orderNo,
                )
            }
        }
    }

    @Test
    fun return_orders_reject_missing_cross_customer_mistyped_or_void_sources() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val other = fixture.customer(phone = "13900000000", name = "李四")
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            fixture.price(other.id, category.id, "12.30")

            assertOrderError(ErrorCode.E3005) {
                fixture.newOrder(
                    customerId = customer.id,
                    categoryId = category.id,
                    orderType = OrderType.PURCHASE_RETURN,
                    requestId = "source-missing",
                    sourceOrderNo = "PS202611119999",
                )
            }

            val foreign = fixture.newOrder(other.id, category.id, requestId = "source-foreign")
            assertOrderError(ErrorCode.E3005) {
                fixture.newOrder(
                    customerId = customer.id,
                    categoryId = category.id,
                    orderType = OrderType.PURCHASE_RETURN,
                    requestId = "source-other-customer",
                    sourceOrderNo = foreign.orderNo,
                )
            }

            val direct = fixture.newOrder(
                customer.id,
                category.id,
                orderType = OrderType.DIRECT,
                requestId = "source-direct",
            )
            assertOrderError(ErrorCode.E3005) {
                fixture.newOrder(
                    customerId = customer.id,
                    categoryId = category.id,
                    orderType = OrderType.PURCHASE_RETURN,
                    requestId = "source-wrong-type",
                    sourceOrderNo = direct.orderNo,
                )
            }

            val directReturn = fixture.newOrder(
                customerId = customer.id,
                categoryId = category.id,
                orderType = OrderType.DIRECT_RETURN,
                requestId = "source-direct-return",
                sourceOrderNo = direct.orderNo,
            )
            assertEquals(direct.orderNo, directReturn.sourceOrderNo)

            val toVoid = fixture.newOrder(customer.id, category.id, requestId = "source-to-void")
            fixture.txRunner.run { tx ->
                tx.exec("UPDATE order_header SET status = 'VOID' WHERE order_no = ?", toVoid.orderNo)
            }
            assertOrderError(ErrorCode.E3004) {
                fixture.newOrder(
                    customerId = customer.id,
                    categoryId = category.id,
                    orderType = OrderType.PURCHASE_RETURN,
                    requestId = "source-void",
                    sourceOrderNo = toVoid.orderNo,
                )
            }
        }
    }

    @Test
    fun get_recent_supports_exact_order_no_lookup() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val first = fixture.newOrder(customer.id, category.id, requestId = "lookup-1")
            fixture.newOrder(customer.id, category.id, requestId = "lookup-2")

            val page = fixture.orders.getRecent(OrderQuery(orderNo = first.orderNo))
            assertEquals(1L, page.total)
            assertEquals(first.orderNo, page.items.single().orderNo)
            assertEquals(0L, fixture.orders.getRecent(OrderQuery(orderNo = "PS202611119999")).total)
            assertOrderError(ErrorCode.E422) {
                fixture.orders.getRecent(OrderQuery(orderNo = "   "))
            }
        }
    }

    // —— 查询 / 应收 ——

    @Test
    fun today_lists_only_the_server_business_date() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id)

            val today = fixture.orders.today()
            assertEquals(1, today.size)
            assertEquals(order.orderNo, today.single().orderNo)
            assertTrue(fixture.ordersAt(TOMORROW_INSTANT).today().isEmpty())
        }
    }

    @Test
    fun get_recent_filters_by_phone_type_dates_and_pages() {
        Fixture().use { fixture ->
            val first = fixture.customer(phone = "13800000001", name = "甲")
            val second = fixture.customer(phone = "13800000002", name = "乙")
            val category = fixture.category()
            fixture.price(first.id, category.id, "12.30")
            fixture.price(second.id, category.id, "12.30")

            val one = fixture.newOrder(first.id, category.id, requestId = "r1")
            val two = fixture.newOrder(first.id, category.id, requestId = "r2")
            val three = fixture.newOrder(
                second.id,
                category.id,
                orderType = OrderType.PURCHASE_RETURN,
                requestId = "r3",
            )

            val pageOne = fixture.orders.getRecent(
                OrderQuery(phone = "13800000001", page = 1, pageSize = 1),
            )
            val pageTwo = fixture.orders.getRecent(
                OrderQuery(phone = "13800000001", page = 2, pageSize = 1),
            )
            assertEquals(2L, pageOne.total)
            assertEquals(1, pageOne.items.size)
            assertEquals(1, pageTwo.items.size)
            assertEquals(
                setOf(one.orderNo, two.orderNo),
                setOf(pageOne.items.single().orderNo, pageTwo.items.single().orderNo),
            )

            assertEquals(3L, fixture.orders.getRecent(OrderQuery()).total)
            assertEquals(1L, fixture.orders.getRecent(OrderQuery(customerId = second.id)).total)
            assertEquals(
                setOf(three.orderNo),
                fixture.orders.getRecent(OrderQuery(orderType = OrderType.PURCHASE_RETURN)).items.map { it.orderNo }.toSet(),
            )
            assertEquals(
                3L,
                fixture.orders.getRecent(OrderQuery(fromDate = TODAY, toDate = TODAY)).total,
            )
            assertEquals(0L, fixture.orders.getRecent(OrderQuery(phone = "13999999999")).total)
        }
    }

    @Test
    fun get_recent_rejects_bad_dates_and_paging_with_e422() {
        Fixture().use { fixture ->
            assertOrderError(ErrorCode.E422) {
                fixture.orders.getRecent(OrderQuery(fromDate = "2026-13-01"))
            }
            assertOrderError(ErrorCode.E422) {
                fixture.orders.getRecent(OrderQuery(fromDate = TOMORROW, toDate = TODAY))
            }
            assertOrderError(ErrorCode.E422) { fixture.orders.getRecent(OrderQuery(page = 0)) }
            assertOrderError(ErrorCode.E422) { fixture.orders.getRecent(OrderQuery(pageSize = 0)) }
            assertOrderError(ErrorCode.E422) { fixture.orders.getRecent(OrderQuery(pageSize = 201)) }
            assertOrderError(ErrorCode.E422) { fixture.orders.getRecent(OrderQuery(customerId = 0)) }
            assertOrderError(ErrorCode.E422) {
                fixture.orders.getRecent(OrderQuery(phone = "138-0000-0000"))
            }
        }
    }

    // —— PriceChanged 重算 ——

    @Test
    fun a_price_change_recalculates_same_day_orders_and_bumps_the_version() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id, quantity = "2")
            assertEquals("24.60", created.totalAmount.amount)

            fixture.price(customer.id, category.id, "13.00", reason = "涨 0.70")

            val updated = fixture.orders.today().single()
            assertEquals(created.orderNo, updated.orderNo)
            assertEquals(2L, updated.version)
            assertEquals("13.00", updated.lines.single().unitPrice.amount)
            assertEquals("26.00", updated.lines.single().amount.amount)
            assertEquals("26.00", updated.totalAmount.amount)
            assertEquals("26.00", updated.receivableAmount.amount)
            assertEquals("26.00", fixture.orders.getReceivable(created.orderNo).amount)
        }
    }

    @Test
    fun a_price_change_does_not_touch_other_customers_orders() {
        Fixture().use { fixture ->
            val first = fixture.customer(phone = "13800000001", name = "甲")
            val second = fixture.customer(phone = "13800000002", name = "乙")
            val category = fixture.category()
            fixture.price(first.id, category.id, "12.30")
            fixture.price(second.id, category.id, "20.00")

            val firstOrder = fixture.newOrder(first.id, category.id, requestId = "a")
            val secondOrder = fixture.newOrder(second.id, category.id, requestId = "b")

            fixture.price(first.id, category.id, "13.00", reason = "只改甲")

            val untouched = fixture.orders.getRecent(OrderQuery(customerId = second.id)).items.single()
            assertEquals(secondOrder.version, untouched.version)
            assertEquals("20.00", untouched.lines.single().unitPrice.amount)
            assertEquals("13.00", fixture.orders.today().first { it.orderNo == firstOrder.orderNo }
                .lines.single().unitPrice.amount)
        }
    }

    @Test
    fun a_price_change_skips_settled_orders() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id, quantity = "2")
            fixture.txRunner.run { tx ->
                tx.exec("UPDATE order_header SET status = 'SETTLED' WHERE order_no = ?", created.orderNo)
            }

            fixture.price(customer.id, category.id, "13.00", reason = "结清后改价")

            val reloaded = fixture.orders.today().single()
            assertEquals(1L, reloaded.version)
            assertEquals("12.30", reloaded.lines.single().unitPrice.amount)
            assertEquals("24.60", reloaded.totalAmount.amount)
        }
    }

    @Test
    fun a_price_change_skips_historical_orders() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id, quantity = "2")
            fixture.txRunner.run { tx ->
                tx.exec("UPDATE order_header SET order_date = ? WHERE order_no = ?", "2026-11-10", created.orderNo)
            }

            fixture.price(customer.id, category.id, "13.00", reason = "只改当日单")

            val reloaded = fixture.orders.getRecent(OrderQuery(customerId = customer.id)).items.single()
            assertEquals(1L, reloaded.version)
            assertEquals("12.30", reloaded.lines.single().unitPrice.amount)
            assertEquals("24.60", reloaded.totalAmount.amount)
        }
    }

    @Test
    fun replaying_the_same_price_event_is_idempotent() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            fixture.newOrder(customer.id, category.id, quantity = "2")
            fixture.price(customer.id, category.id, "13.00", reason = "涨 0.70")
            val afterChange = fixture.orders.today().single()
            assertEquals(2L, afterChange.version)

            fixture.recalculator.onPriceChanged(
                PriceChanged(
                    priceId = 1L,
                    customerId = customer.id,
                    categoryId = category.id,
                    batchTag = "A",
                    oldPrice = Money.ofPlain("12.30"),
                    newPrice = Money.ofPlain("13.00"),
                    adminId = ADMIN_ID,
                    reason = "重放事件",
                    changedAt = NOW_TIMESTAMP,
                ),
            )

            val replay = fixture.orders.today().single()
            assertEquals(2L, replay.version, "金额未变化时不推进版本")
            assertEquals("26.00", replay.totalAmount.amount)
        }
    }

    @Test
    fun a_multi_line_order_sums_amounts_and_recalculates_only_the_matching_lines() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val cement = fixture.category(categoryName = "水泥", model = "P.O 42.5")
            val brick = fixture.category(
                categoryName = "砖",
                model = "红砖",
                unit = PricingUnit.PIECE,
                batchTags = emptyList(),
                batchTagRequired = false,
                packingSpec = null,
            )
            fixture.price(customer.id, cement.id, "12.30")
            fixture.price(customer.id, brick.id, unitPrice = "5.00", batchTag = "")

            val created = fixture.orders.create(
                createCmd(
                    customerId = customer.id,
                    categoryId = cement.id,
                    requestId = "multi",
                    lines = listOf(
                        com.jiancai.shared.dto.OrderLineCmd(cement.id, "A", PricingUnit.TON, "2"),
                        com.jiancai.shared.dto.OrderLineCmd(brick.id, "", PricingUnit.PIECE, "3"),
                    ),
                ),
                OPERATOR_ID,
            )
            assertEquals(2, created.lines.size)
            assertEquals("39.60", created.totalAmount.amount, "24.60 + 15.00")

            fixture.price(customer.id, cement.id, "13.00", reason = "只改水泥")

            val updated = fixture.orders.today().single()
            assertEquals(2L, updated.version)
            assertEquals("13.00", updated.lines[0].unitPrice.amount)
            assertEquals("26.00", updated.lines[0].amount.amount)
            assertEquals("5.00", updated.lines[1].unitPrice.amount)
            assertEquals("15.00", updated.lines[1].amount.amount)
            assertEquals("41.00", updated.totalAmount.amount)
        }
    }

    @Test
    fun the_receivable_of_a_void_order_is_zero() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val created = fixture.newOrder(customer.id, category.id, quantity = "2")
            assertEquals("24.60", fixture.orders.getReceivable(created.orderNo).amount)

            fixture.txRunner.run { tx ->
                tx.exec("UPDATE order_header SET status = 'VOID' WHERE order_no = ?", created.orderNo)
            }

            assertEquals("0.00", fixture.orders.getReceivable(created.orderNo).amount)
        }
    }

    // —— 备注单号（S10 经 S08 取号）——

    @Test
    fun note_numbers_use_the_bz_prefix_and_roll_back_with_the_callers_transaction() {
        Fixture().use { fixture ->
            val first = fixture.orders.nextNoteNo()
            val second = fixture.orders.nextNoteNo()

            assertTrue(Regex("""BZ\d{12}""").matches(first), "备注单号格式：BZ + yyyyMMdd + 4 位序号：$first")
            assertTrue(second.endsWith("0002"), "同一前缀按日递增：$second")
            assertEquals(2L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM serial_number") })

            assertFailsWith<IllegalStateException> {
                fixture.txRunner.run { tx ->
                    fixture.orders.nextNoteNo(tx)
                    throw IllegalStateException("note write failed")
                }
            }

            val third = fixture.orders.nextNoteNo()
            assertTrue(third.endsWith("0003"), "回滚的号可复用：$third")
            assertEquals(3L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM serial_number") })
        }
    }

    // —— 事务（铁律 4）——

    @Test
    fun convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")

            val counting = CountingTxRunner(fixture.txRunner)
            val service = OrderService(
                txRunner = counting,
                serials = fixture.serial,
                prices = fixture.pricing,
                customers = fixture.customers,
                categories = fixture.categories,
                clock = fixture.clock,
            )

            val created = service.create(createCmd(customer.id, category.id, requestId = "counted"), OPERATOR_ID)
            assertEquals(1, counting.runs)
            assertEquals(1, service.today().size)
            assertEquals(2, counting.runs)
            service.getRecent(OrderQuery())
            assertEquals(3, counting.runs)
            service.getReceivable(created.orderNo)
            assertEquals(4, counting.runs)

            counting.reset()
            val second = fixture.txRunner.run { tx ->
                service.create(tx, createCmd(customer.id, category.id, requestId = "counted-in-tx"), OPERATOR_ID)
            }
            assertEquals(0, counting.runs, "带 tx 的重载不得再开事务")
            fixture.txRunner.run { tx -> service.update(tx, updateCmd(second, quantity = "4")) }
            assertEquals(0, counting.runs, "改单的 tx 重载同样复用调用方事务")

            counting.reset()
            fixture.txRunner.run { tx -> service.nextNoteNo(tx) }
            assertEquals(0, counting.runs, "备注单号的 tx 重载同样复用调用方事务")
        }
    }

    @Test
    fun caller_rollback_discards_the_order_the_serial_and_the_idempotency_key() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val cmd = createCmd(customer.id, category.id, requestId = "rollback")

            assertFailsWith<IllegalStateException> {
                fixture.txRunner.run { tx ->
                    fixture.orders.create(tx, cmd, OPERATOR_ID)
                    throw IllegalStateException("boom")
                }
            }

            assertEquals(0L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM order_header") })
            assertEquals(0L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM serial_counter") })
            assertEquals(0L, fixture.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM serial_number") })

            val created = fixture.orders.create(cmd, OPERATOR_ID)
            assertTrue(created.orderNo.endsWith("0001"), "回滚后序号可复用：${created.orderNo}")
            assertEquals(1, fixture.orders.today().size)
        }
    }
}
