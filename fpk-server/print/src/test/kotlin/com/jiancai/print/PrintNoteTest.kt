/*
 * @file fpk-server/print/src/test/kotlin/com/jiancai/print/PrintNoteTest.kt
 * @role 备注单打印测试（`PrintPort.render(noteNo)`）。
 * @exports PrintNoteTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.print

import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.enums.DocType
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 备注单打印测试（`PrintPort.render(noteNo)`）。
 *
 * S10 当前没有读 Port，用例用内存映射模拟组合根的 `NoteReadPort` 适配器；
 * 订单侧仍走真实 S08，验证备注单抬头能带出原单客户快照。
 */
class PrintNoteTest {

    @Test
    fun a_note_is_rendered_from_the_note_read_port_and_marks_the_source_order() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.note(
                order = order,
                diffType = DiffType.QUANTITY,
                diffAmount = "-12.30",
                remark = "少 5 件",
            )

            val doc = fixture.print.render(note.noteNo, PRINTER_ID)

            assertEquals(DocType.NOTE, doc.docType)
            assertEquals("备注单", doc.title)
            assertEquals(note.noteNo, doc.docNo)
            assertEquals(order.customerName, doc.header.customerName)
            assertEquals(order.customerPhone, doc.header.customerPhone)
            assertEquals(order.orderNo, doc.header.sourceOrderNo)
            assertTrue(doc.items.isEmpty())
            assertNull(doc.totals)

            val noteBlock = requireNotNull(doc.note)
            assertEquals("数量差异", noteBlock.diffTypeLabel)
            assertEquals("-12.30", noteBlock.diffAmount.display())
            assertEquals("少 5 件", noteBlock.remark)
            assertFalse(noteBlock.voided)

            assertTrue(doc.content.contains("原单: ${order.orderNo}"))
            assertTrue(doc.content.contains("差异类型: 数量差异"))
            assertTrue(doc.content.contains("差异金额: -12.30"))
            assertTrue(doc.content.contains("打印次数: 1"))
            assertEquals(1L, fixture.printRows(note.noteNo))
        }
    }

    @Test
    fun a_voided_note_is_marked_and_keeps_its_void_reason() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.note(
                order = order,
                voided = true,
                voidReason = "客户取消",
            )

            val doc = fixture.print.render(note.noteNo)

            assertEquals("已作废", doc.header.statusLabel)
            assertTrue(requireNotNull(doc.note).voided)
            assertTrue(doc.content.contains("已作废"))
            assertTrue(doc.content.contains("作废原因: 客户取消"))
        }
    }

    @Test
    fun a_note_without_a_readable_source_order_still_prints_its_own_fields() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.note(
                order = order,
                sourceOrderNo = "PS202611119999",
                remark = "原单已归档",
            )

            val doc = fixture.print.render(note.noteNo)

            assertEquals("客户 #${order.customerId}", doc.header.customerName)
            assertEquals("", doc.header.customerPhone)
            assertEquals("PS202611119999", doc.header.sourceOrderNo)
            assertTrue(doc.content.contains(note.noteNo))
            assertTrue(doc.content.contains("原单已归档"))
        }
    }

    @Test
    fun unknown_note_number_is_e7001() {
        Fixture().use { fixture ->
            assertPrintError(ErrorCode.E7001) {
                fixture.print.render("BZ202611119999", PRINTER_ID)
            }
            assertEquals(0L, fixture.printRows("BZ202611119999"))
        }
    }

    @Test
    fun without_a_note_read_port_a_note_number_is_still_reported_as_missing() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id)
            val note = fixture.note(order)

            val service = PrintService(
                txRunner = fixture.txRunner,
                orders = fixture.orders,
                config = fixture.config,
                notes = null,
                clock = fixture.clock,
            )

            assertPrintError(ErrorCode.E7001) { service.render(note.noteNo) }
            assertEquals(0L, fixture.printRows(note.noteNo))
        }
    }
}
