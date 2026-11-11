/*
 * @file fpk-server/print/src/main/kotlin/com/jiancai/print/PrintLayout.kt
 * @role 80mm 票据版式（S12 唯一真源，不放到 shared：打印排版属本模块）。
 * @exports （无）
 * @depends （无）
 * @calledBy fpk-server/print/src/main/kotlin/com/jiancai/print/PrintRenderer.kt, fpk-server/print/src/main/kotlin/com/jiancai/print/PrintService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.print

/**
 * 80mm 票据版式（S12 唯一真源，不放到 shared：打印排版属本模块）。
 *
 * 五列 / 双行：
 * - **五列**：品名（含型号）/ 规格 / 数量 / 单价 / 金额，合计 48 个半角格；
 * - **双行**：每条明细固定占两行——第一行五列，第二行批号 / 换算 / 重量 / 体积（无内容时留空）；
 * - 表头同样两行：第一行五列列名，第二行「批号 / 换算 / 重量」。
 *
 * 48 格 = 80mm 热敏纸（可打印区约 72mm）在 12×24 点阵字体下的常见容量；
 * 数字列右对齐，文本列左对齐，超宽按整字截断。
 */
internal object PrintLayout {

    /** 纸宽（mm），验收口径固定 80mm。 */
    const val PAPER_WIDTH_MM: Int = 80

    /** 80mm 纸的可打印宽度（mm），左右各留 4mm。 */
    const val PRINTABLE_WIDTH_MM: Int = 72

    /** 每行半角字符数（中文算 2 格）。 */
    const val CHARACTERS_PER_LINE: Int = 48

    /** 明细列数。 */
    const val COLUMNS: Int = 5

    /** 每条明细占用的行数。 */
    const val LINES_PER_ITEM: Int = 2

    /** 版式版本（写入 `print_job.template_version`，便于将来换版式后追溯）。 */
    const val TEMPLATE_VERSION: String = "1.0.0"

    /** 五列宽度：15 + 8 + 8 + 8 + 9 = 48。 */
    private val COLUMN_WIDTHS = intArrayOf(15, 8, 8, 8, 9)

    /** 五列列名（顺序与 [COLUMN_WIDTHS] 一致）。 */
    private val COLUMN_TITLES = arrayOf("品名", "规格", "数量", "单价", "金额")

    /** 分隔线：整行 48 个 `-`。 */
    fun divider(): String = "-".repeat(CHARACTERS_PER_LINE)

    /** 五列列名行（第一行表头）。 */
    fun headerRow(): String =
        COLUMN_TITLES.mapIndexed { index, title ->
            PrintText.fit(title, COLUMN_WIDTHS[index], PrintText.Align.CENTER)
        }.joinToString("").trimEnd()

    /** 第二行表头：说明明细第二行会放什么。 */
    fun detailHeaderRow(): String = PrintText.take("批号 / 换算 / 重量", CHARACTERS_PER_LINE)

    /** 明细第一行：五列。 */
    fun itemRow(item: PrintItemDto): String =
        listOf(
            PrintText.fit(
                "${item.seq}.${item.name} ${item.model}".trim(),
                COLUMN_WIDTHS[0],
                PrintText.Align.LEFT,
            ),
            PrintText.fit(item.spec.orEmpty(), COLUMN_WIDTHS[1], PrintText.Align.LEFT),
            PrintText.fit(
                "${item.quantity}${item.unitLabel}",
                COLUMN_WIDTHS[2],
                PrintText.Align.RIGHT,
            ),
            PrintText.fit(item.unitPrice, COLUMN_WIDTHS[3], PrintText.Align.RIGHT),
            PrintText.fit(item.amount, COLUMN_WIDTHS[4], PrintText.Align.RIGHT),
        ).joinToString("").trimEnd()

    /** 明细第二行：批号 / 换算 / 重量 / 体积；都没有时返回空行（保持「双行」占位）。 */
    fun itemDetailRow(item: PrintItemDto): String {
        val parts = buildList {
            if (item.batchTag.isNotBlank()) add("批号 ${item.batchTag}")
            item.conversion?.let { add("换算 $it") }
            item.weightKg?.let { add("重 ${it}kg") }
            item.volumeM3?.let { add("体积 ${it}m³") }
        }
        if (parts.isEmpty()) return ""
        return PrintText.take("  " + parts.joinToString("  "), CHARACTERS_PER_LINE)
    }

    /** 金额行：`标签 + 右对齐金额`，用于合计 / 应收 / 已收。 */
    fun moneyLine(label: String, amount: String): String =
        PrintText.fit("$label:", 10, PrintText.Align.LEFT) +
            PrintText.fit(amount, 12, PrintText.Align.RIGHT)
}
