/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   34 行  类 PrintPortTest
 * 第   37 行  函数 order_renders_the_80mm_five_column_two_line_layout_with_server_produced_values
 * 第  124 行  函数 exact_package_quantities_show_whole_packages_without_a_remainder
 * 第  156 行  函数 a_model_without_packing_spec_prints_without_conversion
 * 第  178 行  函数 print_count_is_persisted_across_service_instances_and_marks_reprints
 * 第  205 行  函数 the_configured_print_limit_returns_e7002_without_writing_a_row
 * 第  226 行  函数 unknown_document_is_e7001_and_does_not_consume_a_print
 * 第  236 行  函数 blank_overlong_and_control_character_document_numbers_are_e422
 * 第  248 行  函数 render_reuses_the_callers_transaction_and_rolls_back_the_print_count
 * 第  284 行  函数 printing_never_changes_the_order_or_consumes_its_version
 */

package com.jiancai.print

import com.jiancai.shared.enums.DocType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S12 打印用例测试（验收：80mm 渲染、五列双行、装箱换算、打印次数）。
 *
 * 所有用例独立内存库 + 固定时钟；不依赖执行时刻、不直连打印机。
 */
class PrintPortTest {

    @Test
    fun order_renders_the_80mm_five_column_two_line_layout_with_server_produced_values() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(
                categoryName = "瓷砖",
                model = "800x800",
                unit = PricingUnit.PIECE,
                packingSpec = PackingSpec(
                    baseUnit = PricingUnit.PIECE,
                    packageUnit = PricingUnit.BOX,
                    unitsPerPackage = 10,
                    weightPerPackageKg = "10.5",
                    volumePerPackageM3 = "0.02",
                    specText = "800×800",
                ),
            )
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(
                customerId = customer.id,
                categoryId = category.id,
                quantity = "25",
                unit = PricingUnit.PIECE,
            )

            val doc = fixture.print.render(order.orderNo, PRINTER_ID)

            assertEquals(80, doc.paperWidthMm)
            assertEquals(72, doc.printableWidthMm)
            assertEquals(48, doc.charactersPerLine)
            assertEquals(5, doc.columns)
            assertEquals(2, doc.linesPerItem)
            assertEquals(DocType.PURCHASE, doc.docType)
            assertEquals("采购单", doc.title)
            assertEquals(order.orderNo, doc.docNo)
            assertEquals(NOW_TIMESTAMP, doc.printedAt)
            assertEquals(PRINTER_ID, doc.printedBy)
            assertEquals(1, doc.printCount)
            assertEquals(3, doc.maxPrints)
            assertEquals(2, doc.remainingPrints)

            assertEquals(MERCHANT_NAME, doc.header.merchantName)
            assertEquals(customer.name, doc.header.customerName)
            assertEquals(customer.phone, doc.header.customerPhone)
            assertEquals(customer.address, doc.header.customerAddress)
            assertEquals(TODAY, doc.header.date)
            assertEquals("已下单", doc.header.statusLabel)

            val item = doc.items.single()
            assertEquals("瓷砖", item.name)
            assertEquals("800x800", item.model)
            assertEquals("800×800", item.spec)
            assertEquals("A", item.batchTag)
            assertEquals("25", item.quantity)
            assertEquals("件", item.unitLabel)
            assertEquals("12.30", item.unitPrice)
            assertEquals("307.50", item.amount)
            assertEquals("2箱+5件", item.conversion)
            assertEquals("26.25", item.weightKg)
            assertEquals("0.05", item.volumeM3)

            val totals = requireNotNull(doc.totals)
            assertEquals("307.50", totals.totalAmount.display())
            assertEquals("307.50", totals.receivableAmount.display())
            assertEquals("0.00", totals.receivedAmount.display())

            assertTrue(
                doc.lines.all { PrintText.displayWidth(it) <= PrintLayout.CHARACTERS_PER_LINE },
                "80mm 票据每行不得超过 ${PrintLayout.CHARACTERS_PER_LINE} 个半角格；实际：${doc.lines.maxOf { PrintText.displayWidth(it) }}",
            )
            assertTrue(doc.content.contains(MERCHANT_NAME))
            assertTrue(doc.content.contains("品名"))
            assertTrue(doc.content.contains("规格"))
            assertTrue(doc.content.contains("数量"))
            assertTrue(doc.content.contains("单价"))
            assertTrue(doc.content.contains("金额"))
            assertTrue(doc.content.contains("批号 A"))
            assertTrue(doc.content.contains("2箱+5件"))
            assertTrue(doc.content.contains("26.25kg"))
            assertTrue(doc.content.contains("0.05m³"))
            assertTrue(doc.content.contains("打印次数: 1"))

            assertEquals(1L, fixture.printRows(order.orderNo))
            assertEquals(PRINTER_ID, fixture.printedByOf(order.orderNo))
        }
    }

    @Test
    fun exact_package_quantities_show_whole_packages_without_a_remainder() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(
                categoryName = "瓷砖",
                model = "600x600",
                unit = PricingUnit.PIECE,
                packingSpec = PackingSpec(
                    baseUnit = PricingUnit.PIECE,
                    packageUnit = PricingUnit.BOX,
                    unitsPerPackage = 10,
                    weightPerPackageKg = "10.5",
                    specText = "600×600",
                ),
            )
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(
                customerId = customer.id,
                categoryId = category.id,
                quantity = "20",
                unit = PricingUnit.PIECE,
            )

            val item = fixture.print.render(order.orderNo).items.single()

            assertEquals("2箱", item.conversion)
            assertEquals("21", item.weightKg)
            assertNull(item.volumeM3, "未配置体积时不显示")
        }
    }

    @Test
    fun a_model_without_packing_spec_prints_without_conversion() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category(unit = PricingUnit.TON, packingSpec = null)
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id, quantity = "2")

            val doc = fixture.print.render(order.orderNo)
            val item = doc.items.single()

            assertNull(item.conversion)
            assertNull(item.weightKg)
            assertNull(item.volumeM3)
            // 表头固定有「批号 / 换算 / 重量」，这里断言明细第二行没有换算段。
            val detailLine = doc.lines.single { it.contains("批号 A") }
            assertFalse(detailLine.contains("换算"))
            assertFalse(detailLine.contains("重 "))
            assertFalse(detailLine.contains("体积"))
        }
    }

    @Test
    fun print_count_is_persisted_across_service_instances_and_marks_reprints() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id, quantity = "2")

            val first = fixture.print.render(order.orderNo, PRINTER_ID)
            val second = fixture.print.render(order.orderNo, PRINTER_ID)
            // 换一个 S12 实例：次数应来自库，不是实例内存。
            val third = fixture.printService().render(order.orderNo, PRINTER_ID)

            assertEquals(1, first.printCount)
            assertEquals(2, second.printCount)
            assertEquals(3, third.printCount)
            assertTrue(second.content.contains("补打"))
            assertTrue(second.content.contains("第 2 次"))
            assertEquals(3L, fixture.printRows(order.orderNo))

            assertPrintError(ErrorCode.E7002) {
                fixture.printService().render(order.orderNo, PRINTER_ID)
            }
            assertEquals(3L, fixture.printRows(order.orderNo))
        }
    }

    @Test
    fun the_configured_print_limit_returns_e7002_without_writing_a_row() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id, quantity = "2")

            val limited = fixture.printService(maxPrints = 2)
            limited.render(order.orderNo, PRINTER_ID)
            limited.render(order.orderNo, PRINTER_ID)

            val failure = assertFailsWith<PrintException> {
                limited.render(order.orderNo, PRINTER_ID)
            }
            assertEquals(ErrorCode.E7002, failure.code)
            assertTrue(failure.message.orEmpty().contains("2"))
            assertEquals(2L, fixture.printRows(order.orderNo))
        }
    }

    @Test
    fun unknown_document_is_e7001_and_does_not_consume_a_print() {
        Fixture().use { fixture ->
            assertPrintError(ErrorCode.E7001) {
                fixture.print.render("PS202611119999", PRINTER_ID)
            }
            assertEquals(0L, fixture.printRows("PS202611119999"))
        }
    }

    @Test
    fun blank_overlong_and_control_character_document_numbers_are_e422() {
        Fixture().use { fixture ->
            assertPrintError(ErrorCode.E422) { fixture.print.render("   ") }
            assertPrintError(ErrorCode.E422) { fixture.print.render("PS" + "1".repeat(40)) }
            assertPrintError(ErrorCode.E422) { fixture.print.render("PS202611110001\nX") }
            assertPrintError(ErrorCode.E422) { fixture.print.render("PS202611110001", 0L) }
            assertPrintError(ErrorCode.E422) { fixture.print.render("PS202611110001", -1L) }
            assertEquals(0L, fixture.printRows("PS202611110001"))
        }
    }

    @Test
    fun render_reuses_the_callers_transaction_and_rolls_back_the_print_count() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id, quantity = "2")

            val counting = CountingTxRunner(fixture.txRunner)
            val service = PrintService(
                txRunner = counting,
                orders = fixture.orders,
                config = fixture.config,
                notes = fixture.noteReader,
                clock = fixture.clock,
            )

            val doc = service.render(order.orderNo)
            assertEquals(1, counting.runs, "便捷重载应恰好开一个事务")
            assertEquals(1, doc.printCount)

            counting.reset()
            fixture.txRunner.run { tx -> service.render(tx, order.orderNo) }
            assertEquals(0, counting.runs, "带 tx 的重载不得自己开事务")
            assertEquals(2L, fixture.printRows(order.orderNo))

            assertFailsWith<IllegalStateException> {
                fixture.txRunner.run { tx ->
                    fixture.print.render(tx, order.orderNo)
                    error("模拟调用方回滚")
                }
            }
            assertEquals(2L, fixture.printRows(order.orderNo), "回滚不得留下打印次数")
        }
    }

    @Test
    fun printing_never_changes_the_order_or_consumes_its_version() {
        Fixture().use { fixture ->
            val customer = fixture.customer()
            val category = fixture.category()
            fixture.price(customer.id, category.id, "12.30")
            val order = fixture.newOrder(customer.id, category.id, quantity = "2")

            val before = fixture.order(order.orderNo)
            fixture.print.render(order.orderNo, PRINTER_ID)
            val after = fixture.order(order.orderNo)

            assertEquals(before, after, "打印是只读渲染，不得改订单任何字段 / 版本")
            assertEquals(1L, after.version)
        }
    }
}
