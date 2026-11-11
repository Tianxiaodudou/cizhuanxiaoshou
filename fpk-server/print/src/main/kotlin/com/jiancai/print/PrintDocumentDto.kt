/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   42 行  类 PrintDocumentDto
 * 第   79 行  类 PrintHeaderDto
 * 第  103 行  类 PrintItemDto
 * 第  119 行  类 PrintTotalsDto
 * 第  126 行  类 PrintNoteDto
 */

package com.jiancai.print

import com.jiancai.shared.dto.Money
import com.jiancai.shared.enums.DocType

/**
 * 打印结果（S12 唯一出参 DTO）。
 *
 * 只暴露「渲染后的票据 + 审计信息」，不返回 `print_job` 的持久化结构（铁律 2）。
 * [content] 就是送打印机的 80mm 文本（UTF-8，行尾 `\n`）；[items] 是同一份数据的结构化视图，
 * 供 S13 做回执 / 排查，不要求客户端重新排版（客户端零打印、零计算）。
 *
 * 版式口径（S12 唯一实现）：
 * - 纸宽 [paperWidthMm] = 80mm，可打印区 [printableWidthMm] = 72mm；
 * - 每行 [charactersPerLine] = 48 个半角字符（中文按 2 个半角宽计）；
 * - 明细区 [columns] = 5 列（品名 / 规格 / 数量 / 单价 / 金额），每行物品占 [linesPerItem] = 2 行
 *   （第二行放批号 / 换算 / 重量 / 体积）。
 *
 * @property docNo 订单号或备注单号（服务端产出，客户端只展示）
 * @property docType 单据类型（`shared` 的 [DocType]）
 * @property title 单据标题（取 [DocType.label]）
 * @property printedAt 本次打印时间（`WireFormat.TIMESTAMP`，服务端时钟）
 * @property printedBy 打印操作者 id（S13 认证后传入，仅留痕；不是权限判断）
 * @property printCount 本次打印后的累计次数（含本次）
 * @property maxPrints 允许的最大打印次数（部署配置，默认 [PrintPort.DEFAULT_MAX_PRINTS]）
 * @property header 票据抬头
 * @property items 明细（备注单为空）
 * @property totals 金额合计（备注单为 null）
 * @property note 备注单差异信息（订单为 null）
 * @property content 完整渲染文本
 */
data class PrintDocumentDto(
    val docNo: String,
    val docType: DocType,
    val title: String,
    val paperWidthMm: Int,
    val printableWidthMm: Int,
    val charactersPerLine: Int,
    val columns: Int,
    val linesPerItem: Int,
    val printedAt: String,
    val printedBy: Long?,
    val printCount: Int,
    val maxPrints: Int,
    val header: PrintHeaderDto,
    val items: List<PrintItemDto>,
    val totals: PrintTotalsDto?,
    val note: PrintNoteDto?,
    val content: String,
) {
    /** [content] 按行拆开（含空行，便于打印机逐行输出）。 */
    val lines: List<String> get() = content.lines()

    /** 剩余可打印次数（0 表示再打印会抛 `E7002`）。 */
    val remainingPrints: Int get() = (maxPrints - printCount).coerceAtLeast(0)
}

/**
 * 票据抬头（订单 / 备注单共用）。
 *
 * @property merchantName 部署级商家名称（S01，只读）
 * @property title 单据标题
 * @property docNo 单据号
 * @property date 订单业务日期 / 备注单创建时间
 * @property statusLabel 状态文案（订单取 `OrderStatus.label`，已作废备注单为「已作废」；无状态为 null）
 * @property sourceOrderNo 退货单的原单号 / 备注单的原单号
 * @property remark 订单备注（备注单的差异备注在 [PrintNoteDto.remark]）
 */
data class PrintHeaderDto(
    val merchantName: String,
    val title: String,
    val docNo: String,
    val date: String,
    val statusLabel: String?,
    val customerName: String,
    val customerPhone: String,
    val customerAddress: String?,
    val sourceOrderNo: String?,
    val remark: String?,
)

/**
 * 一条订单明细的打印视图。
 *
 * 金额 / 单价直接取 `OrderDto` 已产出的 `Money`，本模块**不重算**（铁律 5）；
 * [conversion] / [weightKg] / [volumeM3] 是服务端按订单快照里的装箱规格做的展示换算，
 * 只用于打印，不回写订单。
 *
 * @property conversion 包装换算，如 `2箱+5件` / `2箱` / `0.5箱`；不适用时为 null
 * @property weightKg 换算后总重量（kg，十进制字符串，最多 3 位小数）
 * @property volumeM3 换算后总体积（m³，十进制字符串，最多 3 位小数）
 */
data class PrintItemDto(
    val seq: Int,
    val name: String,
    val model: String,
    val spec: String?,
    val batchTag: String,
    val quantity: String,
    val unitLabel: String,
    val unitPrice: String,
    val amount: String,
    val conversion: String?,
    val weightKg: String?,
    val volumeM3: String?,
)

/** 订单金额合计：全部取 `OrderDto` 的服务端派生值（合计 / 应收 / 已收）。 */
data class PrintTotalsDto(
    val totalAmount: Money,
    val receivableAmount: Money,
    val receivedAmount: Money,
)

/** 备注单差异信息（只打印备注单自身，不改原单、不重复打印原单明细）。 */
data class PrintNoteDto(
    val diffTypeLabel: String,
    val diffAmount: Money,
    val remark: String,
    val voided: Boolean,
    val voidReason: String?,
)
