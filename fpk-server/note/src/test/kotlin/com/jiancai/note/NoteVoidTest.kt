/*
 * @file fpk-server/note/src/test/kotlin/com/jiancai/note/NoteVoidTest.kt
 * @role S10 作废用例：只作废备注单、保留原始数据、不动原单。
 * @exports NoteVoidTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.note

import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.VoidNoteCmd
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S10 作废用例：只作废备注单、保留原始数据、不动原单。
 */
class NoteVoidTest {

    @Test
    fun void_marks_the_note_without_touching_the_source_order() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.newNote(order.orderNo, diffType = DiffType.QUANTITY, diffAmount = "8.80")
            val receivableBefore = fixture.orders.getReceivable(order.orderNo)

            val voided = fixture.notes.void(VoidNoteCmd(note.noteNo, "客户取消差异"), VOIDER_ID)

            assertEquals(note.noteNo, voided.noteNo, "作废不得改单号")
            assertEquals(note.sourceOrderNo, voided.sourceOrderNo)
            assertEquals(note.customerId, voided.customerId)
            assertEquals(note.diffType, voided.diffType)
            assertEquals(note.diffAmount, voided.diffAmount, "作废不得改差异金额")
            assertEquals(note.remark, voided.remark, "作废不得改差异备注")
            assertEquals(note.createdAt, voided.createdAt)
            assertEquals(note.createdBy, voided.createdBy)
            assertTrue(voided.voided)
            assertEquals(NOW_TIMESTAMP, voided.voidedAt)
            assertEquals(VOIDER_ID, voided.voidedBy)
            assertEquals("客户取消差异", voided.voidReason)

            assertEquals(order, fixture.order(order.orderNo), "作废备注单不得改动原单")
            assertEquals(
                receivableBefore,
                fixture.orders.getReceivable(order.orderNo),
                "作废备注单不得改动原单应收",
            )
            assertEquals(1L, fixture.noteRows(), "作废只更新标记，不删除行")
        }
    }

    @Test
    fun void_keeps_the_original_diff_data_in_storage() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.newNote(order.orderNo, diffType = DiffType.FREIGHT, diffAmount = "6.50")

            fixture.notes.void(VoidNoteCmd(note.noteNo, "录错了"), VOIDER_ID)

            val stored = fixture.stored(note.noteNo)
            assertTrue(stored.voided)
            assertEquals("6.50", stored.diffAmount.amount)
            assertEquals(DiffType.FREIGHT, stored.diffType)
            assertEquals(order.orderNo, stored.sourceOrderNo)
            assertEquals("录错了", stored.voidReason)
            assertEquals(VOIDER_ID, stored.voidedBy)
        }
    }

    @Test
    fun a_voided_note_cannot_be_voided_again_with_e4002() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.newNote(order.orderNo)

            fixture.notes.void(VoidNoteCmd(note.noteNo, "第一次作废"), VOIDER_ID)
            assertNoteError(ErrorCode.E4002) {
                fixture.notes.void(VoidNoteCmd(note.noteNo, "第二次作废"), VOIDER_ID)
            }

            val stored = fixture.stored(note.noteNo)
            assertTrue(stored.voided)
            assertEquals("第一次作废", stored.voidReason, "重复作废不得覆盖首次留痕")
        }
    }

    @Test
    fun void_rejects_an_unknown_note_with_e4001() {
        Fixture().use { fixture ->
            assertNoteError(ErrorCode.E4001) {
                fixture.notes.void(VoidNoteCmd("BZ202611119999", "不存在"), VOIDER_ID)
            }
            assertEquals(0L, fixture.noteRows())
        }
    }

    @Test
    fun void_rejects_a_blank_reason_or_operator_with_e422_and_writes_nothing() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.newNote(order.orderNo)

            assertNoteError(ErrorCode.E422) {
                fixture.notes.void(VoidNoteCmd(note.noteNo, "   "), VOIDER_ID)
            }
            assertNoteError(ErrorCode.E422) {
                fixture.notes.void(VoidNoteCmd(note.noteNo, "bad\u0001reason"), VOIDER_ID)
            }
            assertNoteError(ErrorCode.E422) {
                fixture.notes.void(VoidNoteCmd(note.noteNo, "合法原因"), 0L)
            }

            val stored = fixture.stored(note.noteNo)
            assertFalse(stored.voided)
            assertNull(stored.voidedAt)
            assertNull(stored.voidedBy)
            assertNull(stored.voidReason)
        }
    }

    @Test
    fun a_caller_rollback_keeps_the_note_active() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id)
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.newNote(order.orderNo)

            val failure = runCatching {
                fixture.txRunner.run { tx ->
                    fixture.notes.void(tx, VoidNoteCmd(note.noteNo, "回滚的作废"), VOIDER_ID)
                    error("boom")
                }
            }

            assertTrue(failure.isFailure)
            val stored = fixture.stored(note.noteNo)
            assertFalse(stored.voided, "外层回滚必须撤销作废")
            assertNull(stored.voidReason)
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
            val note = notes.create(
                CreateNoteCmd(
                    sourceOrderNo = order.orderNo,
                    diffType = DiffType.PRICE,
                    diffAmount = Money.ofPlain("1.00"),
                    remark = "事务计数",
                ),
                OPERATOR_ID,
            )

            counting.reset()
            notes.void(VoidNoteCmd(note.noteNo, "便捷重载"), VOIDER_ID)
            assertEquals(1, counting.runs, "便捷重载只开一个事务")

            val second = notes.create(
                CreateNoteCmd(
                    sourceOrderNo = order.orderNo,
                    diffType = DiffType.OTHER,
                    diffAmount = Money.ofPlain("2.00"),
                    remark = "第二个",
                ),
                OPERATOR_ID,
            )
            counting.reset()
            counting.run { tx -> notes.void(tx, VoidNoteCmd(second.noteNo, "带 tx"), VOIDER_ID) }
            assertEquals(1, counting.runs, "带 tx 重载复用调用方事务，不再自开")
        }
    }
}
