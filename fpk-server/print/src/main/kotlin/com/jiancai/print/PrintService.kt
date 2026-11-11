/*
 * @file fpk-server/print/src/main/kotlin/com/jiancai/print/PrintService.kt
 * @role [PrintPort] 的唯一实现：查单据 → 判打印次数 → 渲染 → 落台账，全部在一个事务里。
 * @exports PrintService
 * @depends config, db-core, order, shared
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.print

import com.jiancai.config.ConfigPort
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.order.OrderException
import com.jiancai.order.OrderPort
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.DocType
import com.jiancai.shared.errors.ErrorCode
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [PrintPort] 的唯一实现：查单据 → 判打印次数 → 渲染 → 落台账，全部在一个事务里。
 *
 * 依赖只有三个 Port：
 * - S08 [OrderPort]：按单号读订单（只读，绝不写订单表）；
 * - S01 [ConfigPort]：读商家名称；
 * - [NoteReadPort]：备注单只读入口（S10 还没有读 Port 时的消费侧最小契约，可空）。
 *
 * 打印次数在**同一事务**内 `INSERT` + `COUNT`：渲染失败 / 调用方回滚时台账一并回滚，
 * 不会出现「次数加了、票没出」。
 *
 * @param txRunner db-core 事务 Port（铁律 4）
 * @param orders S08 订单只读 Port
 * @param config S01 部署配置 Port
 * @param notes 备注单只读 Port；S10 追加读方法前可为 null（备注单打印返回 `E7001`）
 * @param clock 服务端时钟（`printed_at` 按 `WireFormat.TIMESTAMP` 产出）
 * @param maxPrintsPerDocument 同一单据最多打印次数（>= 1，默认 [PrintPort.DEFAULT_MAX_PRINTS]）
 */
class PrintService(
    private val txRunner: TxRunner,
    private val orders: OrderPort,
    private val config: ConfigPort,
    private val notes: NoteReadPort? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxPrintsPerDocument: Int = PrintPort.DEFAULT_MAX_PRINTS,
) : PrintPort {

    private val repository = PrintRepository()
    private val renderer = PrintRenderer()

    init {
        require(maxPrintsPerDocument >= 1) {
            "打印次数上限必须大于 0：$maxPrintsPerDocument"
        }
    }

    override fun render(docNo: String, printedBy: Long?): PrintDocumentDto =
        txRunner.run { tx -> render(tx, docNo, printedBy) }

    override fun render(tx: TxContext, docNo: String, printedBy: Long?): PrintDocumentDto {
        val normalized = normalizeDocNo(docNo)
        if (printedBy != null && printedBy <= 0) {
            throw PrintException(ErrorCode.E422, "打印人 id 必须大于 0：$printedBy")
        }

        // 先确认单据存在：不存在的单号不消耗打印次数。
        val order = findOrder(tx, normalized)
        val note = if (order == null) findNote(tx, normalized) else null
        if (order == null && note == null) {
            throw PrintException(ErrorCode.E7001, "打印数据不存在：$normalized")
        }
        val docType = order?.orderType?.docType ?: DocType.NOTE
        val merchantName = config.getMerchantName()
        val printedAt = OffsetDateTime.now(clock).format(TIMESTAMP_FORMAT)

        val inserted = repository.insertIfUnderLimit(
            tx = tx,
            record = PrintRecord(
                docNo = normalized,
                docType = docType,
                paperWidthMm = PrintLayout.PAPER_WIDTH_MM,
                templateVersion = PrintLayout.TEMPLATE_VERSION,
                printedAt = printedAt,
                printedBy = printedBy,
            ),
            limit = maxPrintsPerDocument,
        )
        if (!inserted) {
            throw PrintException(
                ErrorCode.E7002,
                "打印次数已达上限（$maxPrintsPerDocument 次）：$normalized",
            )
        }
        val printCount = repository.count(tx, normalized)

        return if (order != null) {
            renderer.renderOrder(
                order = order,
                merchantName = merchantName,
                printCount = printCount,
                maxPrints = maxPrintsPerDocument,
                printedBy = printedBy,
                printedAt = printedAt,
            )
        } else {
            val value = requireNotNull(note) { "备注单与订单不能同时为空：$normalized" }
            renderer.renderNote(
                note = value,
                sourceOrder = findSourceOrder(tx, value.sourceOrderNo),
                merchantName = merchantName,
                printCount = printCount,
                maxPrints = maxPrintsPerDocument,
                printedBy = printedBy,
                printedAt = printedAt,
            )
        }
    }

    /**
     * 按单号读订单：S08 没有 get-by-no 的读方法，用 `OrderQuery.orderNo` 精确过滤（只取 1 条）。
     *
     * 不截取 / 判断前缀；查不到返回 null，由调用方继续尝试备注单。
     */
    private fun findOrder(tx: TxContext, docNo: String): OrderDto? {
        val page = try {
            orders.getRecent(tx, OrderQuery(orderNo = docNo, page = 1, pageSize = 1))
        } catch (failure: OrderException) {
            if (failure.code == ErrorCode.E422) {
                throw PrintException(ErrorCode.E422, failure.message ?: "单号格式不合法：$docNo", failure)
            }
            throw failure
        }
        return page.items.firstOrNull()
    }

    /** 备注单的原单只用于展示客户名 / 电话 / 地址；原单缺失不影响备注单本身打印。 */
    private fun findSourceOrder(tx: TxContext, orderNo: String): OrderDto? =
        try {
            findOrder(tx, orderNo)
        } catch (failure: PrintException) {
            null
        }

    private fun findNote(tx: TxContext, noteNo: String): NoteDto? = notes?.get(tx, noteNo)

    private fun normalizeDocNo(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw PrintException(ErrorCode.E422, "单号不能为空")
        if (text.length > MAX_DOC_NO_LENGTH) {
            throw PrintException(ErrorCode.E422, "单号长度不合法：${text.length}")
        }
        if (text.any { it.isISOControl() }) {
            throw PrintException(ErrorCode.E422, "单号不能包含控制字符")
        }
        return text
    }

    private companion object {
        /** 与 S08 的单号长度上限一致（S07 产出 14 位，留出余量）。 */
        const val MAX_DOC_NO_LENGTH: Int = 32

        /** 时间戳按共享契约 [WireFormat.TIMESTAMP] 产出（含时区偏移）。 */
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)
    }
}
