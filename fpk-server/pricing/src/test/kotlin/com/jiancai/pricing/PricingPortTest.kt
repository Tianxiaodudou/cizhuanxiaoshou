/*
 * @file fpk-server/pricing/src/test/kotlin/com/jiancai/pricing/PricingPortTest.kt
 * @role S06 定价用例测试（验收：跨日锁、改价留痕、PriceChanged 触发重算）。
 * @exports PricingPortTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.pricing

import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S06 定价用例测试（验收：跨日锁、改价留痕、PriceChanged 触发重算）。
 *
 * 所有用例用独立内存库 + 固定时钟；跨日场景通过 `Fixture.pricingAt(...)` 换一个服务端日期，
 * 不修改系统时间、不依赖测试执行时刻。
 */
class PricingPortTest {

    @Test
    fun first_pricing_creates_an_unlocked_price_and_resolve_returns_it() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            val created = fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)

            assertTrue(created.id > 0)
            assertEquals(customer.id, created.customerId)
            assertEquals(category.id, created.categoryId)
            assertEquals("A", created.batchTag)
            assertEquals("12.30", created.unitPrice.amount)
            assertEquals(TODAY, created.effectiveDate)
            assertFalse(created.locked, "首次定价在定价当日必须是可改状态")
            assertEquals(NOW_TIMESTAMP, created.updatedAt)
            assertEquals(ADMIN_ID, created.updatedBy)

            assertEquals(created, fixture.pricing.resolve(customer.id, category.id, "A"))
            assertEquals(0L, fixture.logCount(), "首次定价不是改价，不产生留痕")
        }
    }

    @Test
    fun resolve_without_a_configured_price_is_rejected_with_e1002() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            assertPricingError(ErrorCode.E1002) {
                fixture.pricing.resolve(customer.id, category.id, "A")
            }
        }
    }

    @Test
    fun same_day_change_updates_the_price_and_appends_an_audit_log() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            val first = fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)

            val changed = fixture.pricing.changePrice(
                priceCmd(
                    customerId = customer.id,
                    categoryId = category.id,
                    newPrice = "13.00",
                    reason = "  补涨  ",
                ),
                adminId = ADMIN_ID + 1,
            )

            assertEquals(first.id, changed.id, "同一维度只允许一行价格")
            assertEquals("13.00", changed.unitPrice.amount)
            assertEquals(TODAY, changed.effectiveDate, "当日改价不更新定价日")
            assertFalse(changed.locked)
            assertEquals(ADMIN_ID + 1, changed.updatedBy)

            val logs = fixture.pricing.history(customer.id, category.id, "A")
            assertEquals(1, logs.size)
            val log = logs.single()
            assertEquals("12.30", log.oldPrice.amount)
            assertEquals("13.00", log.newPrice.amount)
            assertEquals(ADMIN_ID + 1, log.adminId)
            assertEquals("补涨", log.reason, "原因由服务端裁剪首尾空白")
            assertEquals(NOW_TIMESTAMP, log.changedAt)
            assertEquals("13.00", fixture.pricing.resolve(customer.id, category.id, "A").unitPrice.amount)
        }
    }

    @Test
    fun second_change_appends_another_log_without_losing_history() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, newPrice = "13.00", reason = "第一次"),
                ADMIN_ID,
            )
            fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, newPrice = "14.00", reason = "第二次"),
                ADMIN_ID,
            )

            val logs = fixture.pricing.history(customer.id, category.id, "A")

            assertEquals(2, logs.size, "每次改价都追加一条，不覆盖旧留痕")
            assertEquals("第二次", logs[0].reason, "最新在前")
            assertEquals("13.00", logs[0].oldPrice.amount)
            assertEquals("14.00", logs[0].newPrice.amount)
            assertEquals("第一次", logs[1].reason)
            assertEquals("12.30", logs[1].oldPrice.amount)
            assertEquals("13.00", logs[1].newPrice.amount)
        }
    }

    @Test
    fun cross_day_change_is_rejected_with_e1001_and_writes_nothing() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            val nextDay = fixture.pricingAt(TOMORROW_INSTANT)

            assertPricingError(ErrorCode.E1001) {
                nextDay.changePrice(
                    priceCmd(customer.id, category.id, newPrice = "99.00", reason = "跨日改价"),
                    ADMIN_ID,
                )
            }

            val resolved = nextDay.resolve(customer.id, category.id, "A")
            assertEquals("12.30", resolved.unitPrice.amount, "跨日改价必须整体失败，不留下半条数据")
            assertTrue(resolved.locked)
            assertEquals(0L, fixture.logCount())
        }
    }

    @Test
    fun resolve_reports_a_price_locked_after_the_pricing_day() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)

            val sameDay = fixture.pricing.resolve(customer.id, category.id, "A")
            val nextDay = fixture.pricingAt(TOMORROW_INSTANT).resolve(customer.id, category.id, "A")

            assertFalse(sameDay.locked)
            assertEquals(TODAY, sameDay.effectiveDate)
            assertTrue(nextDay.locked, "定价日不是今天即锁定；客户端不得自行比较日期")
            assertEquals(TODAY, nextDay.effectiveDate)
            assertEquals(sameDay.unitPrice, nextDay.unitPrice)
        }
    }

    @Test
    fun a_new_dimension_can_be_priced_on_a_later_day() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id, batchTag = "A"), ADMIN_ID)

            val nextDay = fixture.pricingAt(TOMORROW_INSTANT)
            val created = nextDay.changePrice(
                priceCmd(customer.id, category.id, batchTag = "B", newPrice = "21.00"),
                ADMIN_ID,
            )

            assertEquals("B", created.batchTag)
            assertEquals(TOMORROW, created.effectiveDate, "新维度的定价日是首次定价当天")
            assertFalse(created.locked)
            assertEquals(0L, fixture.logCount(), "首次定价不是改价，不产生留痕")
        }
    }

    @Test
    fun change_price_requires_a_non_blank_reason() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            assertPricingError(ErrorCode.E1003) {
                fixture.pricing.changePrice(
                    priceCmd(customer.id, category.id, reason = "   "),
                    ADMIN_ID,
                )
            }
            assertPricingError(ErrorCode.E1002) {
                fixture.pricing.resolve(customer.id, category.id, "A")
            }
        }
    }

    @Test
    fun non_positive_price_and_invalid_admin_are_rejected_with_e422() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            assertPricingError(ErrorCode.E422) {
                fixture.pricing.changePrice(priceCmd(customer.id, category.id, newPrice = "0.00"), ADMIN_ID)
            }
            assertPricingError(ErrorCode.E422) {
                fixture.pricing.changePrice(priceCmd(customer.id, category.id, newPrice = "-1.00"), ADMIN_ID)
            }
            assertPricingError(ErrorCode.E422) {
                fixture.pricing.changePrice(priceCmd(customer.id, category.id), 0)
            }
            assertEquals(0L, fixture.logCount())
            assertPricingError(ErrorCode.E1002) {
                fixture.pricing.resolve(customer.id, category.id, "A")
            }
        }
    }

    @Test
    fun unknown_customer_or_category_is_rejected_with_e404() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()

            assertPricingError(ErrorCode.E404) {
                fixture.pricing.changePrice(priceCmd(customerId = 9_999L, categoryId = category.id), ADMIN_ID)
            }
            assertPricingError(ErrorCode.E404) {
                fixture.pricing.changePrice(priceCmd(customerId = customer.id, categoryId = 9_999L), ADMIN_ID)
            }
            assertPricingError(ErrorCode.E404) {
                fixture.pricing.resolve(customer.id, 9_999L, "A")
            }
        }
    }

    @Test
    fun batch_tag_must_match_the_category_and_is_case_insensitive() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(batchTags = listOf("A", "B"), batchTagRequired = true)

            assertPricingError(ErrorCode.E422) {
                fixture.pricing.changePrice(priceCmd(customer.id, category.id, batchTag = "C"), ADMIN_ID)
            }
            assertPricingError(ErrorCode.E422) {
                fixture.pricing.changePrice(priceCmd(customer.id, category.id, batchTag = "  "), ADMIN_ID)
            }

            val created = fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, batchTag = "a"),
                ADMIN_ID,
            )
            assertEquals("A", created.batchTag, "保留型号库里的规范写法")

            val resolved = fixture.pricing.resolve(customer.id, category.id, "a")
            assertEquals(created.id, resolved.id, "批号大小写不敏感，同一维度只有一行")
            assertEquals("A", resolved.batchTag)
        }
    }

    @Test
    fun optional_batch_tag_can_be_empty_when_the_category_allows_it() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(batchTagRequired = false, batchTags = emptyList())

            val created = fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, batchTag = ""),
                ADMIN_ID,
            )
            assertEquals("", created.batchTag)
            assertEquals(created, fixture.pricing.resolve(customer.id, category.id, ""))

            assertPricingError(ErrorCode.E422) {
                fixture.pricing.changePrice(
                    priceCmd(customer.id, category.id, batchTag = "A", newPrice = "20.00"),
                    ADMIN_ID,
                )
            }
        }
    }

    @Test
    fun same_price_is_an_idempotent_no_op_without_log_or_event() {
        val listener = RecordingListener()
        Fixture(listOf(listener)).use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            val first = fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            listener.events.clear()

            val repeated = fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID + 1)

            assertEquals(first, repeated)
            assertTrue(listener.events.isEmpty(), "同价重复提交不应触发订单重算")
            assertTrue(fixture.pricing.history(customer.id, category.id, "A").isEmpty())
            assertEquals(0L, fixture.logCount())
        }
    }

    @Test
    fun price_changed_is_published_once_with_old_and_new_price() {
        val listener = RecordingListener()
        Fixture(listOf(listener)).use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            assertTrue(listener.events.isEmpty(), "首次定价没有旧价，不派发 PriceChanged")

            fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, newPrice = "15.00", reason = "调价"),
                ADMIN_ID,
            )

            assertEquals(1, listener.events.size, "PriceChanged 必须触发一次重算")
            val event = listener.events.single()
            assertEquals(customer.id, event.customerId)
            assertEquals(category.id, event.categoryId)
            assertEquals("A", event.batchTag)
            assertEquals("12.30", event.oldPrice.amount)
            assertEquals("15.00", event.newPrice.amount)
            assertEquals(ADMIN_ID, event.adminId)
            assertEquals("调价", event.reason)
            assertEquals(NOW_TIMESTAMP, event.changedAt)
        }
    }

    @Test
    fun a_failing_listener_rolls_back_the_price_change_and_the_log() {
        val failing = PriceChangeListener { throw IllegalStateException("订单重算失败") }
        Fixture(listOf(failing)).use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)

            assertFailsWith<IllegalStateException> {
                fixture.pricing.changePrice(
                    priceCmd(customer.id, category.id, newPrice = "18.00"),
                    ADMIN_ID,
                )
            }

            assertEquals("12.30", fixture.pricing.resolve(customer.id, category.id, "A").unitPrice.amount)
            assertTrue(fixture.pricing.history(customer.id, category.id, "A").isEmpty())
            assertEquals(0L, fixture.logCount())
        }
    }

    @Test
    fun history_is_readable_after_master_data_is_deleted() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, newPrice = "16.00"),
                ADMIN_ID,
            )
            fixture.categories.delete(category.id)

            val logs = fixture.pricing.history(customer.id, category.id, "A")

            assertEquals(1, logs.size, "主数据删除不能连带删除改价留痕")
            assertEquals("16.00", logs.single().newPrice.amount)
        }
    }

    @Test
    fun a_caller_rollback_discards_both_the_price_change_and_the_log() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)

            assertFailsWith<IllegalStateException> {
                fixture.db.txRunner.run { tx ->
                    fixture.pricing.changePrice(
                        tx,
                        priceCmd(customer.id, category.id, newPrice = "20.00"),
                        ADMIN_ID,
                    )
                    error("调用方事务失败")
                }
            }

            assertEquals("12.30", fixture.pricing.resolve(customer.id, category.id, "A").unitPrice.amount)
            assertTrue(fixture.pricing.history(customer.id, category.id, "A").isEmpty())
            assertEquals(0L, fixture.logCount())
        }
    }

    @Test
    fun convenience_overloads_open_exactly_one_transaction_and_tx_overloads_reuse_the_caller_transaction() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            val counting = CountingTxRunner(fixture.db.txRunner)
            val pricing = PriceService(
                txRunner = counting,
                customers = fixture.customers,
                categories = fixture.categories,
                listeners = emptyList(),
                clock = fixture.clock,
            )

            pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            assertEquals(1, counting.runs, "便捷改价只开一个事务")

            counting.reset()
            pricing.resolve(customer.id, category.id, "A")
            assertEquals(1, counting.runs, "便捷解析只开一个只读事务")

            counting.reset()
            counting.run { tx ->
                pricing.changePrice(
                    tx,
                    priceCmd(customer.id, category.id, newPrice = "17.00"),
                    ADMIN_ID,
                )
            }
            assertEquals(1, counting.runs, "带 tx 重载复用调用方事务，不再自开事务")
        }
    }

    @Test
    fun history_is_case_insensitive_and_validates_ids() {
        Fixture().use { fixture ->
            assertPricingError(ErrorCode.E422) {
                fixture.pricing.history(0, 1, "A")
            }
            assertPricingError(ErrorCode.E422) {
                fixture.pricing.history(1, 0, "A")
            }
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.pricing.changePrice(priceCmd(customer.id, category.id), ADMIN_ID)
            fixture.pricing.changePrice(
                priceCmd(customer.id, category.id, newPrice = "19.00"),
                ADMIN_ID,
            )

            val logs = fixture.pricing.history(customer.id, category.id, "a")
            assertEquals(1, logs.size, "批号大小写不敏感")
            assertTrue(logs.single().id > 0)
        }
    }

    private fun Fixture.logCount(): Long =
        db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM price_change_log") }

    private fun assertPricingError(expected: ErrorCode, block: () -> Unit) {
        val failure = assertFailsWith<PricingException> { block() }
        assertEquals(expected, failure.code, "错误码应为 $expected，实际 ${failure.code}：${failure.message}")
    }

    private companion object {
        const val ADMIN_ID = 7L
    }
}
