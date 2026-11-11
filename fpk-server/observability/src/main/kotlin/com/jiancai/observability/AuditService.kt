/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   51 行  类 AuditService
 * 第   59 行  函数 record
 * 第   61 行  函数 record
 * 第   79 行  函数 priceChanged
 * 第   82 行  函数 priceChanged
 * 第  104 行  函数 settleAudited
 * 第  111 行  函数 settleAudited
 * 第  145 行  函数 query
 * 第  148 行  函数 query
 * 第  155 行  函数 entries
 * 第  158 行  函数 entries
 * 第  175 行  函数 normalize
 * 第  197 行  函数 encodeDetails
 * 第  200 行  函数 decodeDetails
 * 第  207 行  函数 toDto
 * 第  220 行  函数 invalid
 * 第  222 行  对象 ?
 */

package com.jiancai.observability

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.ContractJson
import com.jiancai.shared.dto.AuditEntryDto
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [AuditPort] 的唯一实现：校验 → 组装留痕 → 追加一行 → 返回 DTO。
 *
 * 职责边界（硬边界）：
 * - **不做业务判定**：不判断能不能改价 / 能不能审核，也不修改任何业务数据；
 * - 只有 [AuditRepository] 一处 SQL（铁律 10），表 `audit_log` 归本模块独占；
 * - 所有写入都在 `TxRunner` 事务内（铁律 4）；异常直接上抛，由 S13 统一映射错误码。
 *
 * @param txRunner db-core 事务 Port
 * @param clock 服务端时钟（`created_at` 按 `WireFormat.TIMESTAMP` 产出）
 */
class AuditService(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : AuditPort {

    /** 台账唯一 SQL 出口（`internal`：只在本模块内可见，不出现在任何公开签名上）。 */
    private val repository: AuditRepository = AuditRepository()

    override fun record(event: AuditEvent): AuditRecordDto = txRunner.run { tx -> record(tx, event) }

    override fun record(tx: TxContext, event: AuditEvent): AuditRecordDto {
        val normalized = normalize(event)
        val createdAt = OffsetDateTime.now(clock)
            .format(DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP))
        val row = AuditRecordRow(
            action = normalized.action,
            targetNo = normalized.targetNo,
            operatorId = normalized.operatorId,
            operatorLabel = normalized.operatorLabel?.trim()?.ifEmpty { null },
            summary = normalized.summary?.trim()?.ifEmpty { null },
            detailsJson = encodeDetails(normalized.details),
            createdAt = createdAt,
            traceId = normalized.traceId?.trim()?.ifEmpty { null },
        )
        val id = repository.insert(tx, row)
        return row.toDto(id)
    }

    override fun priceChanged(log: PriceChangeLog, traceId: String?): AuditRecordDto =
        txRunner.run { tx -> priceChanged(tx, log, traceId) }

    override fun priceChanged(tx: TxContext, log: PriceChangeLog, traceId: String?): AuditRecordDto {
        if (log.adminId <= 0) throw invalid("改价留痕缺少管理员 id")
        val event = AuditEvent(
            action = AuditAction.PRICE_CHANGE,
            targetNo = AuditPort.priceKey(log.customerId, log.categoryId, log.batchTag),
            operatorId = log.adminId,
            summary = "改价 ${log.oldPrice.display()} → ${log.newPrice.display()}",
            details = buildMap {
                put("customer_id", log.customerId.toString())
                put("category_id", log.categoryId.toString())
                put("batch_tag", log.batchTag)
                put("old_price", log.oldPrice.display())
                put("new_price", log.newPrice.display())
                put("reason", log.reason)
                put("changed_at", log.changedAt)
                put("log_id", log.id.toString())
            },
            traceId = traceId,
        )
        return record(tx, event)
    }

    override fun settleAudited(
        settle: SettleDto,
        approved: Boolean,
        remark: String?,
        traceId: String?,
    ): AuditRecordDto = txRunner.run { tx -> settleAudited(tx, settle, approved, remark, traceId) }

    override fun settleAudited(
        tx: TxContext,
        settle: SettleDto,
        approved: Boolean,
        remark: String?,
        traceId: String?,
    ): AuditRecordDto {
        val operatorId = settle.auditedBy ?: throw invalid("结算单 ${settle.settleNo} 尚未审核，不能留痕")
        val expected = if (approved) SettleStatus.APPROVED else SettleStatus.REJECTED
        if (settle.status != expected) {
            throw invalid("审核结论与结算单状态不一致：$approved vs ${settle.status}")
        }
        val event = AuditEvent(
            action = AuditAction.SETTLE_AUDIT,
            targetNo = settle.settleNo,
            operatorId = operatorId,
            summary = if (approved) "结算审核通过" else "结算审核驳回",
            details = buildMap {
                put("order_no", settle.orderNo)
                put("customer_id", settle.customerId.toString())
                put("customer_name", settle.customerName)
                put("direction", settle.direction.name)
                put("amount", settle.amount.display())
                put("status", settle.status.name)
                put("submitted_by", settle.submittedBy.toString())
                put("submitted_at", settle.submittedAt)
                settle.auditedAt?.let { put("audited_at", it) }
                remark?.trim()?.takeIf { it.isNotEmpty() }?.let { put("audit_remark", it) }
            },
            traceId = traceId,
        )
        return record(tx, event)
    }

    override fun query(filter: AuditQuery): List<AuditRecordDto> =
        txRunner.run { tx -> query(tx, filter) }

    override fun query(tx: TxContext, filter: AuditQuery): List<AuditRecordDto> {
        if (filter.limit !in 1..AuditQuery.MAX_LIMIT) {
            throw invalid("查询条数必须在 1..${AuditQuery.MAX_LIMIT} 之间")
        }
        return repository.query(tx, filter).map { it.toDto(it.id) }
    }

    override fun entries(filter: AuditQuery): List<AuditEntryDto> =
        txRunner.run { tx -> entries(tx, filter) }

    override fun entries(tx: TxContext, filter: AuditQuery): List<AuditEntryDto> =
        query(tx, filter).map { record ->
            AuditEntryDto(
                id = record.id,
                action = record.action.name,
                targetType = record.targetType,
                targetNo = record.targetNo,
                operatorId = record.operatorId,
                operatorLabel = record.operatorLabel,
                summary = record.summary,
                details = record.details,
                createdAt = record.createdAt,
                traceId = record.traceId,
            )
        }

    /** 入参规范化 + 校验；不修改业务数据，只决定「这条留痕能不能写」。 */
    private fun normalize(event: AuditEvent): AuditEvent {
        val targetNo = event.targetNo.trim()
        if (targetNo.isEmpty()) throw invalid("留痕目标不能为空")
        if (event.operatorId <= 0) throw invalid("操作者 id 必须为正数")
        val summary = event.summary?.trim()
        if (summary != null && summary.length > AuditEvent.MAX_SUMMARY_LENGTH) {
            throw invalid("摘要超过 ${AuditEvent.MAX_SUMMARY_LENGTH} 字符")
        }
        if (event.details.size > AuditEvent.MAX_DETAIL_ENTRIES) {
            throw invalid("明细超过 ${AuditEvent.MAX_DETAIL_ENTRIES} 项")
        }
        event.details.forEach { (key, value) ->
            if (key.isBlank() || key.length > AuditEvent.MAX_DETAIL_LENGTH) {
                throw invalid("明细键非法：$key")
            }
            if (value.length > AuditEvent.MAX_DETAIL_LENGTH) {
                throw invalid("明细值超过 ${AuditEvent.MAX_DETAIL_LENGTH} 字符：$key")
            }
        }
        return event.copy(targetNo = targetNo, summary = summary)
    }

    private fun encodeDetails(details: Map<String, String>): String =
        ContractJson.json.encodeToString(DETAILS_SERIALIZER, LinkedHashMap(details))

    private fun decodeDetails(json: String): Map<String, String> =
        try {
            ContractJson.json.decodeFromString(DETAILS_SERIALIZER, json)
        } catch (failure: RuntimeException) {
            throw AuditException(ErrorCode.E500, "审计明细损坏", failure)
        }

    private fun AuditRecordRow.toDto(id: Long): AuditRecordDto = AuditRecordDto(
        id = id,
        action = action,
        targetType = action.targetType,
        targetNo = targetNo,
        operatorId = operatorId,
        operatorLabel = operatorLabel,
        summary = summary,
        details = decodeDetails(detailsJson),
        createdAt = createdAt,
        traceId = traceId,
    )

    private fun invalid(message: String): AuditException = AuditException(ErrorCode.E422, message)

    private companion object {
        val DETAILS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
