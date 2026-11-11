/*
 * @file fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnRules.kt
 * @role 退单规则（内部）：**纯函数**，不碰数据库、不调 Port、不读时钟。
 * @exports （无）
 * @depends shared
 * @calledBy fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnCheckService.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.returncheck

import com.jiancai.shared.dto.BatchTagRules
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.Quantity
import com.jiancai.shared.dto.ReturnCheckLineDto
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.math.BigDecimal

/**
 * 批次维度键：型号 + 批号比较键（shared 的 `BatchTagRules.comparisonKey`）+ 单位。
 *
 * 单位必须参与匹配：同一型号按吨和按袋是两个不同的量纲，退货不能混算。
 */
internal data class ReturnBatchKey(
    val categoryId: Long,
    val batchTagKey: String,
    val unit: PricingUnit,
)

/**
 * 退单规则（内部）：**纯函数**，不碰数据库、不调 Port、不读时钟。
 *
 * 这里只有「怎么算」：已退数量汇总、可退批次派生、逐行校验。
 * 「去哪里读」全部留在 [ReturnCheckService]，两者分开才能让规则独立升级与单测覆盖
 * （验收「规则可独立升级」）。
 *
 * 跨端口径一律调 shared，不在本模块复制：
 * - 数量：`Quantity.ofPlain`（十进制、> 0、最多 `WireFormat.QUANTITY_MAX_SCALE` 位小数、去尾零）；
 * - 批号：`BatchTagRules.normalize` / `comparisonKey`（trim + ASCII 大小写不敏感）；
 * - 出参：[ReturnableBatchDto] / [ReturnCheckResultDto] / [ReturnCheckLineDto] 全部来自 `shared`。
 *
 * 数量运算用 [BigDecimal]，输出经 [canonical] 归一为服务端口径字符串，客户端只展示（铁律 5）。
 */
internal class ReturnRules(private val policy: ReturnCheckPolicy) {

    /** 原单是否允许退货：类型在策略白名单内，且不是已作废单。 */
    fun canReturn(original: OrderDto): Boolean =
        original.orderType in policy.returnableOrderTypes && original.status != OrderStatus.VOID

    /** 类型是否属于「可退单据」白名单（不看状态，供错误码分流用）。 */
    fun canReturnType(original: OrderDto): Boolean = original.orderType in policy.returnableOrderTypes

    /**
     * 解析并校验数量：统一走 shared 的 [Quantity.ofPlain]。
     *
     * 契约层的 `IllegalArgumentException` 翻译成本模块的 `E422`（shared 不吞错，也不认识业务错误码）。
     */
    fun quantity(raw: String): Quantity = try {
        Quantity.ofPlain(raw)
    } catch (failure: IllegalArgumentException) {
        throw ReturnCheckException(ErrorCode.E422, failure.message ?: "数量格式不正确：$raw", failure)
    }

    /**
     * 汇总「已退数量」：按 [ReturnBatchKey] 累加属于该原单的有效退货单明细数量。
     *
     * - 只统计策略内的退货单类型；
     * - 已作废的退货单默认不占额度（`countVoidReturnOrders = false`）；
     * - 归属见 [isLinkedTo]：退货单带 `source_order_no` 时精确匹配原单号，为空时按策略兼容归集。
     */
    fun returnedQuantities(
        returnOrders: List<OrderDto>,
        original: OrderDto,
    ): Map<ReturnBatchKey, BigDecimal> {
        val totals = LinkedHashMap<ReturnBatchKey, BigDecimal>()
        returnOrders.forEach { order ->
            if (order.orderType !in policy.returnOrderTypes) return@forEach
            if (!policy.countVoidReturnOrders && order.status == OrderStatus.VOID) return@forEach
            if (!isLinkedTo(order, original)) return@forEach
            order.lines.forEach { line ->
                val key = keyOf(line)
                totals[key] = (totals[key] ?: BigDecimal.ZERO) + amount(line.quantity)
            }
        }
        return totals
    }

    /**
     * 退货单是否属于这张原单（可退数量只能被自己的退货扣减）。
     *
     * - 退货单 `sourceOrderNo` 有值：必须等于原单号（1.1.0 契约新增的精确归属）；
     * - 为空：S08 尚未回填历史数据时，默认按「客户 + 批次」兼容归集；
     *   `requireSourceOrderLink = true` 时一律不认（严格模式，等 S08 全量回填后启用）。
     */
    fun isLinkedTo(returnOrder: OrderDto, original: OrderDto): Boolean {
        val link = returnOrder.sourceOrderNo?.trim().orEmpty()
        if (link.isEmpty()) return !policy.requireSourceOrderLink
        return link == original.orderNo.trim()
    }

    /** 原单上出现过的全部批次键（用于区分「批次不在原单上」`E3005` 与「已退满」`E3002`）。 */
    fun originalBatchKeys(original: OrderDto): Set<ReturnBatchKey> =
        original.lines.map { keyOf(it) }.toSet()

    /**
     * 派生可退批次：按批次维度聚合原单明细，减去已退数量；可退量 ≤ 0 的批次不返回。
     *
     * 结果保持原单明细首次出现的顺序（即 `seq` 顺序），批号写法取原单快照（服务端产出）。
     */
    fun availableBatches(
        original: OrderDto,
        returned: Map<ReturnBatchKey, BigDecimal>,
    ): List<ReturnableBatchDto> {
        val ordered = LinkedHashMap<ReturnBatchKey, BigDecimal>()
        val snapshot = LinkedHashMap<ReturnBatchKey, LineDto>()
        original.lines.forEach { line ->
            val key = keyOf(line)
            ordered[key] = (ordered[key] ?: BigDecimal.ZERO) + amount(line.quantity)
            snapshot.putIfAbsent(key, line)
        }

        val batches = ArrayList<ReturnableBatchDto>(ordered.size)
        ordered.forEach { (key, orderedQuantity) ->
            val returnedQuantity = returned[key] ?: BigDecimal.ZERO
            val returnable = orderedQuantity - returnedQuantity
            if (returnable.signum() <= 0) return@forEach
            val line = snapshot.getValue(key)
            batches += ReturnableBatchDto(
                orderNo = original.orderNo,
                categoryId = key.categoryId,
                categoryName = line.categoryName,
                model = line.model,
                batchTag = BatchTagRules.normalize(line.batchTag),
                unit = key.unit,
                orderedQuantity = canonical(orderedQuantity),
                returnedQuantity = canonical(returnedQuantity),
                returnableQuantity = canonical(returnable),
            )
        }
        return batches
    }

    /**
     * 逐行校验退货申请，通过则返回每一行的服务端口径回显。
     *
     * 判定顺序（先挡单、再挡行、最后挡量，错误码因此稳定可预期）：
     * 1. 原单已作废 → `E3004`；单据类型不可退（退货单不能再退）→ `E3002`；
     * 2. 明细为空 / 型号 id 非法 / 数量格式非法 → `E422`；行数超限 → `E413`；
     * 3. 批次不在原单上 → `E3005`；批次在原单上但已退满 → `E3002`；
     * 4. 同批次多行累加后超过可退量 → `E3003`（先合并再比较，不能靠拆行绕过）。
     */
    fun validate(
        original: OrderDto,
        lines: List<OrderLineCmd>,
        batches: List<ReturnableBatchDto>,
    ): ReturnCheckResultDto {
        if (original.status == OrderStatus.VOID) {
            throw ReturnCheckException(ErrorCode.E3004, "原订单已作废，不可退货：${original.orderNo}")
        }
        if (!canReturnType(original)) {
            throw ReturnCheckException(ErrorCode.E3002, "该单据类型不可退货：${original.orderType.label}")
        }
        if (lines.isEmpty()) throw ReturnCheckException(ErrorCode.E422, "退货明细不能为空")
        if (lines.size > policy.maxLines) {
            throw ReturnCheckException(ErrorCode.E413, "退货明细不能超过 ${policy.maxLines} 行：${lines.size}")
        }

        val originalKeys = originalBatchKeys(original)
        val available = batches.associateBy { keyOf(it) }
        val requested = LinkedHashMap<ReturnBatchKey, BigDecimal>()
        val result = ArrayList<ReturnCheckLineDto>(lines.size)

        lines.forEach { line ->
            if (line.categoryId <= 0) {
                throw ReturnCheckException(ErrorCode.E422, "型号 id 必须大于 0：${line.categoryId}")
            }
            val quantity = amount(line.quantity)
            val key = ReturnBatchKey(line.categoryId, BatchTagRules.comparisonKey(line.batchTag), line.unit)
            if (key !in originalKeys) {
                throw ReturnCheckException(
                    ErrorCode.E3005,
                    "退货批次与原单不符：${BatchTagRules.normalize(line.batchTag).ifBlank { "（无批号）" }}" +
                        "（型号 ${line.categoryId}）",
                )
            }
            val batch = available[key]
                ?: throw ReturnCheckException(
                    ErrorCode.E3002,
                    "该批次不可退（已退满）：${BatchTagRules.normalize(line.batchTag)}（型号 ${line.categoryId}）",
                )
            val returnable = BigDecimal(batch.returnableQuantity)
            val total = (requested[key] ?: BigDecimal.ZERO) + quantity
            if (total > returnable) {
                throw ReturnCheckException(
                    ErrorCode.E3003,
                    "可退数量不足：批次 ${batch.batchTag} 可退 ${batch.returnableQuantity}，" +
                        "申请 ${canonical(total)}",
                )
            }
            requested[key] = total
            result += ReturnCheckLineDto(
                categoryId = batch.categoryId,
                categoryName = batch.categoryName,
                model = batch.model,
                batchTag = batch.batchTag,
                unit = batch.unit,
                requestedQuantity = canonical(quantity),
                returnableQuantity = batch.returnableQuantity,
                remainingQuantity = canonical(returnable - total),
            )
        }

        return ReturnCheckResultDto(orderNo = original.orderNo, lines = result)
    }

    /**
     * 服务端口径的数量字符串。
     *
     * 非零量统一经 shared 的 [Quantity] 归一（去尾零、小数位校验）；数量可以减到 0，
     * 而 `Quantity.ofPlain` 要求 > 0，所以 0 单独按契约的十进制写法输出 `"0"`。
     */
    fun canonical(value: BigDecimal): String {
        val normalized = value.stripTrailingZeros()
        return if (normalized.signum() == 0) ZERO_QUANTITY else Quantity.ofPlain(normalized.toPlainString()).display()
    }

    private fun amount(raw: String): BigDecimal = BigDecimal(quantity(raw).display())

    private fun keyOf(line: LineDto): ReturnBatchKey = ReturnBatchKey(
        line.categoryId,
        BatchTagRules.comparisonKey(line.batchTag),
        line.unit,
    )

    private fun keyOf(batch: ReturnableBatchDto): ReturnBatchKey = ReturnBatchKey(
        batch.categoryId,
        BatchTagRules.comparisonKey(batch.batchTag),
        batch.unit,
    )

    private companion object {
        /** 0 数量的线格式（`Quantity` 只接受 > 0，剩余量可以归零）。 */
        const val ZERO_QUANTITY: String = "0"
    }
}
