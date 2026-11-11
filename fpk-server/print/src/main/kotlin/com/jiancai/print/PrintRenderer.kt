/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   27 行  类 PrintRenderer
 * 第   29 行  函数 renderOrder
 * 第   68 行  函数 renderNote
 * 第  109 行  函数 document
 * 第  139 行  函数 compose
 * 第  196 行  函数 label
 * 第  209 行  函数 toItem
 * 第  227 行  对象 ?
 */

package com.jiancai.print

import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.enums.DocType

/**
 * 票据渲染器：把 S08 / S10 的 DTO 变成 80mm 文本与结构化打印视图。
 *
 * 纯函数（无数据库、无时钟、无 IO）：打印次数的读取 / 写入在 [PrintService]，
 * 因此本类可以单独测版式，不会顺手改库。
 */
internal class PrintRenderer {

    fun renderOrder(
        order: OrderDto,
        merchantName: String,
        printCount: Int,
        maxPrints: Int,
        printedBy: Long?,
        printedAt: String,
    ): PrintDocumentDto {
        val header = PrintHeaderDto(
            merchantName = merchantName,
            title = order.orderType.docType.label,
            docNo = order.orderNo,
            date = order.orderDate,
            statusLabel = order.status.label,
            customerName = order.customerName,
            customerPhone = order.customerPhone,
            customerAddress = order.customerAddress,
            sourceOrderNo = order.sourceOrderNo,
            remark = order.remark,
        )
        val items = order.lines.map(::toItem)
        val totals = PrintTotalsDto(
            totalAmount = order.totalAmount,
            receivableAmount = order.receivableAmount,
            receivedAmount = order.receivedAmount,
        )
        return document(
            docType = order.orderType.docType,
            header = header,
            items = items,
            totals = totals,
            note = null,
            printCount = printCount,
            maxPrints = maxPrints,
            printedBy = printedBy,
            printedAt = printedAt,
        )
    }

    fun renderNote(
        note: NoteDto,
        sourceOrder: OrderDto?,
        merchantName: String,
        printCount: Int,
        maxPrints: Int,
        printedBy: Long?,
        printedAt: String,
    ): PrintDocumentDto {
        val header = PrintHeaderDto(
            merchantName = merchantName,
            title = DocType.NOTE.label,
            docNo = note.noteNo,
            date = note.createdAt,
            statusLabel = if (note.voided) VOID_LABEL else null,
            customerName = sourceOrder?.customerName ?: "客户 #${note.customerId}",
            customerPhone = sourceOrder?.customerPhone.orEmpty(),
            customerAddress = sourceOrder?.customerAddress,
            sourceOrderNo = note.sourceOrderNo,
            remark = null,
        )
        val noteBlock = PrintNoteDto(
            diffTypeLabel = note.diffType.label,
            diffAmount = note.diffAmount,
            remark = note.remark,
            voided = note.voided,
            voidReason = note.voidReason,
        )
        return document(
            docType = DocType.NOTE,
            header = header,
            items = emptyList(),
            totals = null,
            note = noteBlock,
            printCount = printCount,
            maxPrints = maxPrints,
            printedBy = printedBy,
            printedAt = printedAt,
        )
    }

    private fun document(
        docType: DocType,
        header: PrintHeaderDto,
        items: List<PrintItemDto>,
        totals: PrintTotalsDto?,
        note: PrintNoteDto?,
        printCount: Int,
        maxPrints: Int,
        printedBy: Long?,
        printedAt: String,
    ): PrintDocumentDto = PrintDocumentDto(
        docNo = header.docNo,
        docType = docType,
        title = header.title,
        paperWidthMm = PrintLayout.PAPER_WIDTH_MM,
        printableWidthMm = PrintLayout.PRINTABLE_WIDTH_MM,
        charactersPerLine = PrintLayout.CHARACTERS_PER_LINE,
        columns = PrintLayout.COLUMNS,
        linesPerItem = PrintLayout.LINES_PER_ITEM,
        printedAt = printedAt,
        printedBy = printedBy,
        printCount = printCount,
        maxPrints = maxPrints,
        header = header,
        items = items,
        totals = totals,
        note = note,
        content = compose(header, items, totals, note, printCount, printedBy, printedAt),
    )

    private fun compose(
        header: PrintHeaderDto,
        items: List<PrintItemDto>,
        totals: PrintTotalsDto?,
        note: PrintNoteDto?,
        printCount: Int,
        printedBy: Long?,
        printedAt: String,
    ): String {
        val lines = mutableListOf<String>()
        lines += PrintText.fit(header.merchantName, PrintLayout.CHARACTERS_PER_LINE, PrintText.Align.CENTER)
        lines += PrintText.fit(header.title, PrintLayout.CHARACTERS_PER_LINE, PrintText.Align.CENTER)
        lines += label("单号", header.docNo)
        lines += label("日期", header.date)
        lines += label("客户", header.customerName)
        lines += label("电话", header.customerPhone)
        header.customerAddress?.let { lines += label("地址", it) }
        header.statusLabel?.let { lines += label("状态", it) }
        header.sourceOrderNo?.let { lines += label("原单", it) }
        header.remark?.let { lines += label("备注", it) }

        if (items.isNotEmpty()) {
            lines += PrintLayout.divider()
            lines += PrintLayout.headerRow()
            lines += PrintLayout.detailHeaderRow()
            lines += PrintLayout.divider()
            items.forEach { item ->
                lines += PrintLayout.itemRow(item)
                lines += PrintLayout.itemDetailRow(item)
            }
            lines += PrintLayout.divider()
            totals?.let {
                lines += PrintLayout.moneyLine("合计", it.totalAmount.display())
                lines += PrintLayout.moneyLine("应收", it.receivableAmount.display())
                lines += PrintLayout.moneyLine("已收", it.receivedAmount.display())
            }
        }

        note?.let {
            lines += PrintLayout.divider()
            lines += label("差异类型", it.diffTypeLabel)
            lines += label("差异金额", it.diffAmount.display())
            lines += label("备注", it.remark)
            if (it.voided) {
                it.voidReason?.let { reason -> lines += label("作废原因", reason) }
            }
        }

        lines += PrintLayout.divider()
        lines += label("打印次数", printCount.toString())
        if (printCount > 1) lines += label("补打", "第 $printCount 次")
        lines += label("打印时间", printedAt)
        printedBy?.let { lines += label("打印人", "#$it") }
        return lines.joinToString("\n")
    }

    /** `标签: 值`，长值按显示宽度折行，续行缩进到与首行值对齐；空值不输出。 */
    private fun label(name: String, value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val labelPrefix = "$name: "
        val available = PrintLayout.CHARACTERS_PER_LINE - PrintText.displayWidth(labelPrefix)
        if (available <= 0) {
            return listOf(PrintText.take(labelPrefix + value, PrintLayout.CHARACTERS_PER_LINE))
        }
        return PrintText.wrap(value, available).mapIndexed { index, part ->
            if (index == 0) labelPrefix + part
            else " ".repeat(PrintText.displayWidth(labelPrefix)) + part
        }
    }

    private fun toItem(line: LineDto): PrintItemDto {
        val conversion = PrintConversion.of(line)
        return PrintItemDto(
            seq = line.seq,
            name = line.categoryName,
            model = line.model,
            spec = line.packingSpec?.specText,
            batchTag = line.batchTag,
            quantity = line.quantity,
            unitLabel = line.unit.label,
            unitPrice = line.unitPrice.display(),
            amount = line.amount.display(),
            conversion = conversion.packageText,
            weightKg = conversion.weightKg,
            volumeM3 = conversion.volumeM3,
        )
    }

    private companion object {
        /** 备注单作废标记（备注单没有共享状态枚举，只有 boolean）。 */
        const val VOID_LABEL = "已作废"
    }
}
