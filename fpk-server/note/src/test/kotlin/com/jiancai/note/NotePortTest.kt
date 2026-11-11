/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   31 行  类 NotePortTest
 * 第   34 行  函数 create_links_the_source_order_and_records_the_diff
 * 第   64 行  函数 create_never_changes_the_source_order
 * 第   85 行  函数 a_previous_day_order_can_still_be_linked
 * 第  110 行  函数 an_unknown_source_order_is_rejected_with_e4003_and_consumes_no_serial
 * 第  136 行  函数 blank_fields_and_bad_operators_are_rejected_with_e422
 * 第  166 行  函数 the_diff_amount_is_normalized_to_the_shared_money_format
 * 第  181 行  函数 two_notes_may_reference_the_same_order_with_distinct_numbers
 * 第  199 行  函数 a_caller_rollback_discards_the_note_and_the_note_serial
 * 第  230 行  函数 convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction
 */

package com.jiancai.note

import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S10 创建用例：关联原单、不改原单、走 S07 取号、事务与校验。
 */
class NotePortTest {

    @Test
    fun create_links_the_source_order_and_records_the_diff() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)

            val note = fixture.newNote(
                sourceOrderNo = order.orderNo,
                diffType = DiffType.PRICE,
                diffAmount = "12.30",
                remark = "实际到货少 1 吨",
            )

            assertEquals("BZ${SERIAL_TODAY}0001", note.noteNo, "备注单号必须由 S07 按备注单前缀产出")
            assertEquals(order.orderNo, note.sourceOrderNo)
            assertEquals(customer.id, note.customerId, "客户 id 必须取自原单快照")
            assertEquals(DiffType.PRICE, note.diffType)
            assertEquals("12.30", note.diffAmount.amount)
            assertEquals("实际到货少 1 吨", note.remark)
            assertFalse(note.voided)
            assertNull(note.voidedAt)
            assertNull(note.voidedBy)
            assertNull(note.voidReason)
            assertEquals(NOW_TIMESTAMP, note.createdAt, "时间戳由服务端时钟按契约格式产出")
            assertEquals(OPERATOR_ID, note.createdBy)
        }
    }

    @Test
    fun create_never_changes_the_source_order() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val receivableBefore = fixture.orders.getReceivable(order.orderNo)

            fixture.newNote(order.orderNo)

            assertEquals(order, fixture.order(order.orderNo), "备注单不得改动原单的任何字段")
            assertEquals(
                receivableBefore,
                fixture.orders.getReceivable(order.orderNo),
                "备注单不得改动原单应收",
            )
            assertEquals(1L, fixture.noteRows())
        }
    }

    @Test
    fun a_previous_day_order_can_still_be_linked() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)

            val note = fixture.notesAt(TOMORROW_INSTANT).create(
                CreateNoteCmd(
                    sourceOrderNo = order.orderNo,
                    diffType = DiffType.QUANTITY,
                    diffAmount = Money.ofPlain("3.00"),
                    remark = "次日补录差异",
                ),
                OPERATOR_ID,
            )

            assertEquals("BZ${SERIAL_TOMORROW}0001", note.noteNo)
            assertEquals(customer.id, note.customerId)
            assertEquals(TOMORROW_TIMESTAMP, note.createdAt)
            assertEquals(order, fixture.order(order.orderNo), "补录备注也不得改动历史原单")
        }
    }

    @Test
    fun an_unknown_source_order_is_rejected_with_e4003_and_consumes_no_serial() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)

            assertNoteError(ErrorCode.E4003) {
                fixture.notes.create(
                    CreateNoteCmd(
                        sourceOrderNo = "PS202611119999",
                        diffType = DiffType.OTHER,
                        diffAmount = Money.ofPlain("1.00"),
                        remark = "不存在",
                    ),
                    OPERATOR_ID,
                )
            }
            assertEquals(0L, fixture.noteRows())

            // 失败发生在 S07 取号之前，所以下一张合法备注单仍从 0001 开始。
            assertEquals("BZ${SERIAL_TODAY}0001", fixture.newNote(order.orderNo).noteNo)
        }
    }

    @Test
    fun blank_fields_and_bad_operators_are_rejected_with_e422() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val valid = CreateNoteCmd(
                sourceOrderNo = order.orderNo,
                diffType = DiffType.OTHER,
                diffAmount = Money.ofPlain("1.00"),
                remark = "差异",
            )

            assertNoteError(ErrorCode.E422) {
                fixture.notes.create(valid.copy(sourceOrderNo = "   "), OPERATOR_ID)
            }
            assertNoteError(ErrorCode.E422) {
                fixture.notes.create(valid.copy(remark = "   "), OPERATOR_ID)
            }
            assertNoteError(ErrorCode.E422) {
                fixture.notes.create(valid.copy(remark = "bad\u0001text"), OPERATOR_ID)
            }
            assertNoteError(ErrorCode.E422) {
                fixture.notes.create(valid, 0L)
            }
            assertEquals(0L, fixture.noteRows(), "校验失败不得落库")
        }
    }

    @Test
    fun the_diff_amount_is_normalized_to_the_shared_money_format() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)

            val note = fixture.newNote(order.orderNo, diffAmount = "-5", remark = "  负数差异  ")

            assertEquals("-5.00", note.diffAmount.amount)
            assertEquals("负数差异", note.remark)
        }
    }

    @Test
    fun two_notes_may_reference_the_same_order_with_distinct_numbers() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)

            val first = fixture.newNote(order.orderNo, diffType = DiffType.PRICE)
            val second = fixture.newNote(order.orderNo, diffType = DiffType.FREIGHT)

            assertEquals("BZ${SERIAL_TODAY}0001", first.noteNo)
            assertEquals("BZ${SERIAL_TODAY}0002", second.noteNo)
            assertEquals(2L, fixture.noteRows())
            assertEquals(order, fixture.order(order.orderNo))
        }
    }

    @Test
    fun a_caller_rollback_discards_the_note_and_the_note_serial() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val command = CreateNoteCmd(
                sourceOrderNo = order.orderNo,
                diffType = DiffType.PRICE,
                diffAmount = Money.ofPlain("1.00"),
                remark = "会被回滚",
            )

            val failure = runCatching {
                fixture.txRunner.run { tx ->
                    fixture.notes.create(tx, command, OPERATOR_ID)
                    error("boom")
                }
            }

            assertTrue(failure.isFailure)
            assertEquals(0L, fixture.noteRows(), "外层回滚必须丢弃备注单")
            assertEquals(
                "BZ${SERIAL_TODAY}0001",
                fixture.newNote(order.orderNo).noteNo,
                "外层回滚必须同时丢弃 S07 的取号账本，重试不空号",
            )
        }
    }

    @Test
    fun convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val counting = CountingTxRunner(fixture.txRunner)
            val notes = NoteService(counting, fixture.orders, fixture.serial, fixture.clock)
            val command = CreateNoteCmd(
                sourceOrderNo = order.orderNo,
                diffType = DiffType.PRICE,
                diffAmount = Money.ofPlain("1.00"),
                remark = "事务计数",
            )

            counting.reset()
            notes.create(command, OPERATOR_ID)
            assertEquals(1, counting.runs, "便捷重载只开一个事务")

            counting.reset()
            counting.run { tx -> notes.create(tx, command.copy(diffType = DiffType.OTHER), OPERATOR_ID) }
            assertEquals(1, counting.runs, "带 tx 重载复用调用方事务，不再自开")
        }
    }
}
