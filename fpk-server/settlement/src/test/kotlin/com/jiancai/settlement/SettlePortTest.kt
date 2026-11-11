/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   55 行  类 SettlePortTest
 * 第   60 行  函数 submitting_a_receipt_returns_a_server_generated_settle_no_and_submitted_status
 * 第   83 行  函数 submitting_never_touches_the_orders_received_amount_or_status
 * 第  101 行  函数 a_receipt_over_the_outstanding_balance_is_rejected_with_e5002_and_writes_nothing
 * 第  114 行  函数 a_zero_or_negative_amount_is_rejected_with_e422
 * 第  127 行  函数 approving_a_submitted_settlement_records_the_auditor_and_moves_the_balance
 * 第  149 行  函数 a_settlement_can_only_be_audited_once
 * 第  162 行  函数 a_rejected_settlement_is_not_counted_as_received_and_releases_the_balance
 * 第  180 行  函数 approval_rechecks_the_balance_and_blocks_overlapping_receipts
 * 第  195 行  函数 a_partial_receipt_can_be_followed_by_the_remaining_balance
 * 第  211 行  函数 a_refund_on_a_return_order_reduces_the_customer_balance
 * 第  242 行  函数 a_refund_cannot_exceed_the_amount_owed_to_the_customer
 * 第  266 行  函数 a_refund_on_a_positive_order_is_rejected_with_e5002
 * 第  284 行  函数 the_same_request_id_returns_the_same_settlement_and_consumes_one_serial
 * 第  301 行  函数 reusing_a_request_id_for_a_different_payload_is_rejected_with_e5004
 * 第  314 行  函数 an_unknown_order_settlement_or_operator_is_rejected_with_the_owning_modules_code
 * 第  330 行  函数 invalid_identifiers_are_rejected_with_e422_before_any_write
 * 第  355 行  函数 the_balance_rereads_the_receivable_from_s08_every_time
 * 第  373 行  函数 an_empty_customer_has_a_zero_balance
 * 第  387 行  函数 an_invalid_customer_id_is_rejected_with_e422
 * 第  396 行  函数 the_callers_transaction_rolls_back_the_settlement_and_the_serial
 * 第  423 行  函数 convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction
 * 第  452 行  函数 the_settle_dto_exposes_only_display_fields_and_never_the_idempotency_key
 */

package com.jiancai.settlement

import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S11 结算主流程测试：
 * 1. 提交即「当场判定」，金额不得超过待结余额；
 * 2. 审核流 `SUBMITTED → APPROVED / REJECTED`，只有 `APPROVED` 计入已收；
 * 3. 已收 / 未收读模型的应收每一次都从 S08 重新读取；
 * 4. **已收金额不被改动**：任何结算动作都不回写订单的已收 / 状态；
 * 5. 幂等、事务复用与回滚。
 */
class SettlePortTest {

    // —— 提交：服务端产出与当场判定 ——

    @Test
    fun submitting_a_receipt_returns_a_server_generated_settle_no_and_submitted_status() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            val settle = fixture.submit(seeded.orderNo, "24.60", remark = "现金收款")

            assertEquals("JS202611110001", settle.settleNo, "结算单号只能来自 S07 的 JS 序列")
            assertEquals(SettleStatus.SUBMITTED, settle.status, "提交后进入待审核，已收尚未变化")
            assertEquals(SettleDirection.RECEIPT, settle.direction)
            assertEquals("24.60", settle.amount.amount)
            assertEquals("24.60", settle.amount.display())
            assertEquals(seeded.orderNo, settle.orderNo)
            assertEquals(seeded.customerId, settle.customerId)
            assertEquals(seeded.customerName, settle.customerName, "客户名称是下单时快照")
            assertEquals(NOW_TIMESTAMP, settle.submittedAt)
            assertEquals(OPERATOR_ID, settle.submittedBy)
            assertNull(settle.auditedAt)
            assertNull(settle.auditedBy)
            assertEquals("现金收款", settle.remark)
        }
    }

    @Test
    fun submitting_never_touches_the_orders_received_amount_or_status() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val before = fixture.order(seeded.orderNo)
            assertEquals("0.00", before.receivedAmount.amount)

            fixture.submit(seeded.orderNo, "10.00")
            fixture.approve("JS202611110001")

            val after = fixture.order(seeded.orderNo)
            assertEquals("0.00", after.receivedAmount.amount, "已收金额永远不动（归 S11 派生，不回写订单）")
            assertEquals(OrderStatus.CREATED, after.status, "订单状态不被结算改写")
            assertEquals(before.receivableAmount, after.receivableAmount, "应收仍由 S08 产出")
            assertEquals(before.version, after.version, "结算不推进订单版本号")
        }
    }

    @Test
    fun a_receipt_over_the_outstanding_balance_is_rejected_with_e5002_and_writes_nothing() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            assertSettleError(ErrorCode.E5002) { fixture.submit(seeded.orderNo, "24.61") }
            assertEquals(0L, fixture.settleRows(), "判定失败不写库")

            val accepted = fixture.submit(seeded.orderNo, "24.60")
            assertEquals("JS202611110001", accepted.settleNo, "失败请求不得消耗 S07 的结算单号")
        }
    }

    @Test
    fun a_zero_or_negative_amount_is_rejected_with_e422() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            assertSettleError(ErrorCode.E422) { fixture.submit(seeded.orderNo, "0.00") }
            assertSettleError(ErrorCode.E422) { fixture.submit(seeded.orderNo, "-1.00") }
            assertEquals(0L, fixture.settleRows())
        }
    }

    // —— 审核流 ——

    @Test
    fun approving_a_submitted_settlement_records_the_auditor_and_moves_the_balance() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val submitted = fixture.submit(seeded.orderNo, "10.00", remark = "先付一部分")
            assertEquals("0.00", fixture.balance(seeded.customerId).receivedAmount.amount)

            val approved = fixture.approve(submitted.settleNo, remark = "核对无误")

            assertEquals(SettleStatus.APPROVED, approved.status)
            assertEquals(NOW_TIMESTAMP, approved.auditedAt)
            assertEquals(AUDITOR_ID, approved.auditedBy)
            assertEquals("核对无误", approved.remark, "审核备注优先展示，提交备注仍在库内")
            assertEquals("先付一部分", fixture.stored(submitted.settleNo).remark, "提交事实不被审核覆盖")

            val balance = fixture.balance(seeded.customerId)
            assertEquals("24.60", balance.receivableAmount.amount)
            assertEquals("10.00", balance.receivedAmount.amount)
            assertEquals("14.60", balance.unreceivedAmount.amount)
        }
    }

    @Test
    fun a_settlement_can_only_be_audited_once() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val settle = fixture.submit(seeded.orderNo, "10.00")
            fixture.approve(settle.settleNo)

            assertSettleError(ErrorCode.E5003) { fixture.approve(settle.settleNo) }
            assertSettleError(ErrorCode.E5003) { fixture.reject(settle.settleNo) }
            assertEquals(SettleStatus.APPROVED, fixture.stored(settle.settleNo).status)
        }
    }

    @Test
    fun a_rejected_settlement_is_not_counted_as_received_and_releases_the_balance() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val settle = fixture.submit(seeded.orderNo, "10.00")

            val rejected = fixture.reject(settle.settleNo, remark = "金额有误")

            assertEquals(SettleStatus.REJECTED, rejected.status)
            assertEquals("金额有误", rejected.remark)
            assertEquals("0.00", fixture.balance(seeded.customerId).receivedAmount.amount)
            assertEquals("24.60", fixture.balance(seeded.customerId).unreceivedAmount.amount)

            val retry = fixture.submit(seeded.orderNo, "24.60", requestId = "settle-req-2")
            assertEquals(SettleStatus.SUBMITTED, retry.status, "驳回后余额全部释放")
        }
    }

    @Test
    fun approval_rechecks_the_balance_and_blocks_overlapping_receipts() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val first = fixture.submit(seeded.orderNo, "20.00", requestId = "settle-req-1")
            val second = fixture.submit(seeded.orderNo, "20.00", requestId = "settle-req-2")

            fixture.approve(first.settleNo)
            assertSettleError(ErrorCode.E5002) { fixture.approve(second.settleNo) }

            assertEquals(SettleStatus.SUBMITTED, fixture.stored(second.settleNo).status, "审核失败不改状态")
            assertEquals("20.00", fixture.balance(seeded.customerId).receivedAmount.amount)
        }
    }

    @Test
    fun a_partial_receipt_can_be_followed_by_the_remaining_balance() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            fixture.approve(fixture.submit(seeded.orderNo, "10.00", requestId = "settle-req-1").settleNo)
            fixture.approve(fixture.submit(seeded.orderNo, "14.60", requestId = "settle-req-2").settleNo)

            val balance = fixture.balance(seeded.customerId)
            assertEquals("24.60", balance.receivedAmount.amount)
            assertEquals("0.00", balance.unreceivedAmount.amount)
            assertEquals("0.00", fixture.order(seeded.orderNo).receivedAmount.amount, "订单侧已收仍为 0.00")
        }
    }

    // —— 退款（退货单的应收为负）——

    @Test
    fun a_refund_on_a_return_order_reduces_the_customer_balance() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            fixture.approve(fixture.submit(seeded.orderNo, "24.60", requestId = "settle-req-1").settleNo)

            val returned = fixture.newOrder(
                seeded.customerId,
                seeded.categoryId,
                quantity = "1",
                orderType = OrderType.PURCHASE_RETURN,
                requestId = "order-req-2",
            )
            assertEquals("-12.30", returned.receivableAmount.amount, "退货单应收为负")

            val refund = fixture.submit(
                returned.orderNo,
                "12.30",
                direction = SettleDirection.REFUND,
                requestId = "settle-req-2",
            )
            fixture.approve(refund.settleNo)

            val balance = fixture.balance(seeded.customerId)
            assertEquals("12.30", balance.receivableAmount.amount, "应收从 S08 重新读取：24.60 − 12.30")
            assertEquals("12.30", balance.receivedAmount.amount, "已收：24.60 收款 − 12.30 退款")
            assertEquals("0.00", balance.unreceivedAmount.amount)
            assertEquals("0.00", fixture.order(returned.orderNo).receivedAmount.amount)
        }
    }

    @Test
    fun a_refund_cannot_exceed_the_amount_owed_to_the_customer() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val returned = fixture.newOrder(
                seeded.customerId,
                seeded.categoryId,
                quantity = "1",
                orderType = OrderType.PURCHASE_RETURN,
                requestId = "order-req-2",
            )

            assertSettleError(ErrorCode.E5002) {
                fixture.submit(
                    returned.orderNo,
                    "12.31",
                    direction = SettleDirection.REFUND,
                    requestId = "settle-req-1",
                )
            }
            assertEquals(0L, fixture.settleRows())
        }
    }

    @Test
    fun a_refund_on_a_positive_order_is_rejected_with_e5002() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            assertSettleError(ErrorCode.E5002) {
                fixture.submit(
                    seeded.orderNo,
                    "5.00",
                    direction = SettleDirection.REFUND,
                    requestId = "settle-req-1",
                )
            }
        }
    }

    // —— 幂等 / 入参 / 未知资源 ——

    @Test
    fun the_same_request_id_returns_the_same_settlement_and_consumes_one_serial() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            val first = fixture.submit(seeded.orderNo, "10.00", requestId = "same-req")
            val replay = fixture.submit(seeded.orderNo, "10.00", requestId = "same-req")

            assertEquals(first.settleNo, replay.settleNo)
            assertEquals(1L, fixture.settleRows())
            assertEquals("JS202611110001", replay.settleNo, "重放不再取号")

            val next = fixture.submit(seeded.orderNo, "5.00", requestId = "next-req")
            assertEquals("JS202611110002", next.settleNo, "下一次提交才消耗新号")
        }
    }

    @Test
    fun reusing_a_request_id_for_a_different_payload_is_rejected_with_e5004() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            fixture.submit(seeded.orderNo, "10.00", requestId = "reused-req")

            assertSettleError(ErrorCode.E5004) {
                fixture.submit(seeded.orderNo, "11.00", requestId = "reused-req")
            }
            assertEquals(1L, fixture.settleRows(), "载荷冲突不写第二条")
        }
    }

    @Test
    fun an_unknown_order_settlement_or_operator_is_rejected_with_the_owning_modules_code() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            assertSettleError(ErrorCode.E2001) {
                fixture.submit("PS202611119999", "1.00", requestId = "missing-order")
            }
            assertSettleError(ErrorCode.E5001) { fixture.approve("JS202611119999") }
            assertSettleError(ErrorCode.E6005) {
                fixture.submit(seeded.orderNo, "1.00", submittedBy = UNKNOWN_ACCOUNT_ID)
            }
            assertEquals(0L, fixture.settleRows())
        }
    }

    @Test
    fun invalid_identifiers_are_rejected_with_e422_before_any_write() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            assertSettleError(ErrorCode.E422) {
                fixture.settles.submit(
                    SubmitSettleCmd(
                        clientRequestId = "   ",
                        orderNo = seeded.orderNo,
                        direction = SettleDirection.RECEIPT,
                        amount = Money.ofPlain("1.00"),
                    ),
                    OPERATOR_ID,
                )
            }
            assertSettleError(ErrorCode.E422) {
                fixture.submit("   ", "1.00", requestId = "blank-order")
            }
            assertEquals(0L, fixture.settleRows())
        }
    }

    // —— 读模型 ——

    @Test
    fun the_balance_rereads_the_receivable_from_s08_every_time() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            fixture.approve(fixture.submit(seeded.orderNo, "10.00", requestId = "settle-req-1").settleNo)

            assertEquals("24.60", fixture.balance(seeded.customerId).receivableAmount.amount)
            assertEquals("10.00", fixture.balance(seeded.customerId).receivedAmount.amount)

            fixture.newOrder(seeded.customerId, seeded.categoryId, requestId = "order-req-2")

            val balance = fixture.balance(seeded.customerId)
            assertEquals("49.20", balance.receivableAmount.amount, "新增订单后应收从 S08 重新读取")
            assertEquals("10.00", balance.receivedAmount.amount, "已收只随审核通过的结算变化")
            assertEquals("39.20", balance.unreceivedAmount.amount)
        }
    }

    @Test
    fun an_empty_customer_has_a_zero_balance() {
        Fixture().use { fixture ->
            val customer = fixture.customer()

            val balance: ReceivableDto = fixture.balance(customer.id)

            assertEquals("0.00", balance.receivableAmount.amount)
            assertEquals("0.00", balance.receivedAmount.amount)
            assertEquals("0.00", balance.unreceivedAmount.amount)
            assertEquals("", balance.customerName, "没有订单 / 结算快照时客户名留空，不越界读客户表")
        }
    }

    @Test
    fun an_invalid_customer_id_is_rejected_with_e422() {
        Fixture().use { fixture ->
            assertSettleError(ErrorCode.E422) { fixture.balance(0) }
        }
    }

    // —— 事务 ——

    @Test
    fun the_callers_transaction_rolls_back_the_settlement_and_the_serial() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()

            assertFailsWith<IllegalStateException> {
                fixture.txRunner.run { tx ->
                    fixture.settles.submit(
                        tx,
                        SubmitSettleCmd(
                            clientRequestId = "rollback-req",
                            orderNo = seeded.orderNo,
                            direction = SettleDirection.RECEIPT,
                            amount = Money.ofPlain("10.00"),
                        ),
                        OPERATOR_ID,
                    )
                    throw IllegalStateException("调用方回滚")
                }
            }

            assertEquals(0L, fixture.settleRows(), "外层回滚必须丢弃结算记录")
            val retry = fixture.submit(seeded.orderNo, "10.00", requestId = "rollback-req")
            assertEquals("JS202611110001", retry.settleNo, "取号账本随事务回滚，号可复用")
        }
    }

    @Test
    fun convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction() {
        Fixture().use { fixture ->
            val seeded = fixture.seedPurchase()
            val counting = CountingTxRunner(fixture.txRunner)
            val service = SettleService(counting, fixture.orders, fixture.accounts, fixture.serial, fixture.clock)

            service.getBalance(seeded.customerId)
            assertEquals(1, counting.runs, "便捷读模型自开一个事务")

            counting.reset()
            fixture.txRunner.run { tx ->
                service.submit(
                    tx,
                    SubmitSettleCmd(
                        clientRequestId = "tx-reuse",
                        orderNo = seeded.orderNo,
                        direction = SettleDirection.RECEIPT,
                        amount = Money.ofPlain("10.00"),
                    ),
                    OPERATOR_ID,
                )
            }
            assertEquals(0, counting.runs, "带 tx 的重载不得再自开事务")
        }
    }

    // —— DTO 边界 ——

    @Test
    fun the_settle_dto_exposes_only_display_fields_and_never_the_idempotency_key() {
        val names = SettleDto::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(names.any { it.contains("clientrequestid") }, "幂等键是服务端内部字段，不下发")
        assertTrue(names.any { it.contains("settleno") })
        assertTrue(names.any { it.contains("amount") })
        assertTrue(names.any { it.contains("status") })
    }
}
