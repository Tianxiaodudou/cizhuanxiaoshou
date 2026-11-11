/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   79 行  类 PriceService
 * 第   97 行  函数 resolve
 * 第  100 行  函数 resolve
 * 第  113 行  函数 changePrice
 * 第  116 行  函数 changePrice
 * 第  157 行  函数 applyChange
 * 第  209 行  函数 history
 * 第  212 行  函数 history
 * 第  228 行  函数 validateDimensions
 * 第  264 行  函数 normalizeBatchTag
 * 第  292 行  函数 normalizeHistoryTag
 * 第  306 行  函数 normalizeReason
 * 第  319 行  函数 requirePositivePrice
 * 第  328 行  函数 publish
 * 第  332 行  函数 today
 * 第  334 行  函数 now
 * 第  336 行  函数 displayTag
 * 第  342 行  函数 isUniqueViolation
 * 第  349 行  类 Dimensions
 * 第  355 行  对象 ?
 */

package com.jiancai.pricing

import com.jiancai.category.CategoryException
import com.jiancai.category.CategoryPort
import com.jiancai.customer.CustomerException
import com.jiancai.customer.CustomerPort
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.errors.ErrorCode
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [PricePort] 的唯一实现：恒定价格的用例层。
 *
 * 职责边界：
 * - **不判权限**：构造参数里没有角色 / token；`changePrice` 的 `adminId` 只用于留痕与事件，
 *   管理员校验只发生在 S13 api-gateway（铁律 7）；
 * - **不跨模块读写表**：客户 / 型号只经 [CustomerPort] / [CategoryPort] 获取（铁律 3 / 10）；
 * - **服务端产出**：`effective_date` / `updated_at` / `locked` / `changed_at` 全部由服务端时钟产出，
 *   客户端只展示（铁律 5）；
 * - **价格只在本模块解析**：S08 order 必须调 [resolve]，不得在订单模块自行算价。
 *
 * 锁口径（验收「跨日锁生效」）：
 * - `effective_date` 是首次定价的**服务端当地日期**，同一自然日内改价不更新它；
 * - `locked = effective_date != 今天`；跨日后 [changePrice] 抛 `E1001`，不会写价格、不会写留痕；
 * - 新维度（新客户 / 型号 / 批号）在任何一天都可以首次定价，不受其它行锁定影响。
 *
 * 留痕口径（验收「改价有留痕」）：
 * - 只有「已有价格且新旧价不同」才追加 `price_change_log`；首次定价没有旧价，不留痕；
 * - 留痕与价格更新在同一事务内：任一步失败都整体回滚；
 * - 留痕表只有追加入口，库层触发器拒绝 `UPDATE` / `DELETE`（硬边界「禁止删除改价留痕」）。
 *
 * 事件口径（验收「PriceChanged 触发重算」）：
 * - 价格真的变化后，在同一事务内同步派发 [PriceChanged] 给 [listeners]（S08 据此重算当日订单）；
 * - 监听器抛异常会让本次改价整体回滚，监听实现必须幂等、不得做不可回滚的外部副作用。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：写操作经它包裹）
 * @param customers S04 客户 Port（只用于确认客户存在 / 返回其 DTO）
 * @param categories S05 型号 Port（确认型号存在、读取批号类型配置）
 * @param clock 服务端时钟；测试注入固定时钟以断言日期 / 时间戳与跨日锁
 * @param listeners 价格变更监听器（组合根注入 S08 的重算实现；测试注入记录器）
 */
class PriceService(
    private val txRunner: TxRunner,
    private val customers: CustomerPort,
    private val categories: CategoryPort,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val listeners: List<PriceChangeListener> = emptyList(),
) : PricePort {

    private val repository = PriceRepository()

    /** 业务日期按共享契约 [WireFormat.DATE] 产出（服务端时区）。 */
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.DATE)

    /** 时间戳按共享契约 [WireFormat.TIMESTAMP] 产出（含时区偏移）。 */
    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)

    // —— 读 ——

    override fun resolve(customerId: Long, categoryId: Long, batchTag: String): PriceDto =
        txRunner.run { tx -> resolve(tx, customerId, categoryId, batchTag) }

    override fun resolve(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): PriceDto {
        val dimensions = validateDimensions(tx, customerId, categoryId, batchTag)
        val stored = repository.find(tx, dimensions.customerId, dimensions.categoryId, dimensions.batchTag)
            ?: throw PricingException(
                ErrorCode.E1002,
                "未配置价格：客户 ${dimensions.customerId} / 型号 ${dimensions.categoryId} / " +
                    "批号 ${displayTag(dimensions.batchTag)}",
            )
        return stored.toDto(today())
    }

    // —— 写 ——

    override fun changePrice(cmd: ChangePriceCmd, adminId: Long): PriceDto =
        txRunner.run { tx -> changePrice(tx, cmd, adminId) }

    override fun changePrice(tx: TxContext, cmd: ChangePriceCmd, adminId: Long): PriceDto {
        if (adminId <= 0) {
            throw PricingException(ErrorCode.E422, "改价管理员 id 不合法：$adminId")
        }
        val reason = normalizeReason(cmd.reason)
        requirePositivePrice(cmd.newPrice)

        val dimensions = validateDimensions(tx, cmd.customerId, cmd.categoryId, cmd.batchTag)
        val today = today()
        val changedAt = now()

        val existing = repository.find(tx, dimensions.customerId, dimensions.categoryId, dimensions.batchTag)
        if (existing == null) {
            val id = try {
                repository.insert(
                    tx = tx,
                    customerId = dimensions.customerId,
                    categoryId = dimensions.categoryId,
                    batchTag = dimensions.batchTag,
                    unitPrice = cmd.newPrice,
                    effectiveDate = today,
                    updatedAt = changedAt,
                    updatedBy = adminId,
                )
            } catch (conflict: SQLException) {
                // 并发下另一请求刚插入同维度：以库中已有行为准，回到「改价」分支。
                if (!conflict.isUniqueViolation()) throw conflict
                val raced = repository.find(tx, dimensions.customerId, dimensions.categoryId, dimensions.batchTag)
                    ?: throw conflict
                return applyChange(tx, raced, cmd.newPrice, adminId, reason, today, changedAt)
            }
            // 首次定价：不是「改价」，不写留痕、不派发 PriceChanged。
            return repository.findById(tx, id)!!.toDto(today)
        }

        return applyChange(tx, existing, cmd.newPrice, adminId, reason, today, changedAt)
    }

    /**
     * 对已存在的价格执行当日改价：锁定检查 → 同价短路 → 更新 → 留痕 → 回读 → 派发事件。
     */
    private fun applyChange(
        tx: TxContext,
        existing: StoredPrice,
        newPrice: Money,
        adminId: Long,
        reason: String,
        today: String,
        changedAt: String,
    ): PriceDto {
        if (existing.effectiveDate != today) {
            throw PricingException(
                ErrorCode.E1001,
                "价格已跨日锁定（定价日 ${existing.effectiveDate}，今天 $today），不可修改",
            )
        }
        // 同价重复提交是幂等的：不写库、不留痕、不触发重算。
        if (existing.unitPrice == newPrice) return existing.toDto(today)

        if (!repository.updatePrice(tx, existing.id, newPrice, changedAt, adminId)) {
            throw PricingException(ErrorCode.E404, "价格不存在：id=${existing.id}")
        }
        repository.insertChangeLog(
            tx,
            PriceChangeRecord(
                priceId = existing.id,
                customerId = existing.customerId,
                categoryId = existing.categoryId,
                batchTag = existing.batchTag,
                oldPrice = existing.unitPrice,
                newPrice = newPrice,
                adminId = adminId,
                reason = reason,
                changedAt = changedAt,
            ),
        )
        val updated = repository.findById(tx, existing.id)!!.toDto(today)
        publish(
            PriceChanged(
                priceId = updated.id,
                customerId = updated.customerId,
                categoryId = updated.categoryId,
                batchTag = updated.batchTag,
                oldPrice = existing.unitPrice,
                newPrice = updated.unitPrice,
                adminId = adminId,
                reason = reason,
                changedAt = changedAt,
            ),
        )
        return updated
    }

    override fun history(customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog> =
        txRunner.run { tx -> history(tx, customerId, categoryId, batchTag) }

    override fun history(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog> {
        if (customerId <= 0) throw PricingException(ErrorCode.E422, "客户 id 必须大于 0：$customerId")
        if (categoryId <= 0) throw PricingException(ErrorCode.E422, "型号 id 必须大于 0：$categoryId")
        val tag = normalizeHistoryTag(batchTag)
        // 不校验主数据是否仍存在：留痕必须比客户 / 型号更长寿。
        return repository.findChangeLogs(tx, customerId, categoryId, tag)
    }

    // —— 维度校验：客户 / 型号存在 + 批号规范化 ——

    /**
     * 校验并规范化定价维度。
     *
     * 客户 / 型号的存在性由各自 Port 判断；本模块只把它们的错误码原样保留，
     * 不跨表查询（铁律 3 / 10）。
     */
    private fun validateDimensions(
        tx: TxContext,
        customerId: Long,
        categoryId: Long,
        batchTag: String,
    ): Dimensions {
        if (customerId <= 0) throw PricingException(ErrorCode.E422, "客户 id 必须大于 0：$customerId")
        if (categoryId <= 0) throw PricingException(ErrorCode.E422, "型号 id 必须大于 0：$categoryId")

        try {
            customers.get(tx, customerId)
        } catch (failure: CustomerException) {
            throw PricingException(failure.code, failure.message ?: "客户不存在：id=$customerId", failure)
        }

        val category = try {
            categories.get(tx, categoryId)
        } catch (failure: CategoryException) {
            throw PricingException(failure.code, failure.message ?: "型号不存在：id=$categoryId", failure)
        }

        return Dimensions(
            customerId = customerId,
            categoryId = categoryId,
            batchTag = normalizeBatchTag(category, batchTag),
        )
    }

    /**
     * 批号规范化：裁剪、大小写不敏感匹配型号库里的写法。
     *
     * 口径与 S05 的 `category_batch_tag.tag COLLATE NOCASE` 一致：
     * - 型号配置了批号类型：必须命中其中之一（大小写不敏感），返回型号库里的规范写法；
     *   `batchTagRequired = true` 时不允许空；
     * - 型号未配置批号类型：只允许空串；传了值说明客户端的型号 / 批号组合非法。
     */
    private fun normalizeBatchTag(category: CategoryDto, raw: String): String {
        val tag = raw.trim()
        if (tag.length > MAX_TAG_LENGTH) {
            throw PricingException(ErrorCode.E422, "批号类型不能超过 $MAX_TAG_LENGTH 个字符")
        }
        if (tag.any { it.isISOControl() }) {
            throw PricingException(ErrorCode.E422, "批号类型不能包含控制字符")
        }

        if (category.batchTags.isEmpty()) {
            if (tag.isNotEmpty() || category.batchTagRequired) {
                throw PricingException(ErrorCode.E422, "型号未配置批号类型，批号只能为空")
            }
            return ""
        }

        if (tag.isEmpty()) {
            if (category.batchTagRequired) {
                throw PricingException(ErrorCode.E422, "该型号要求选择批号类型")
            }
            return ""
        }

        return category.batchTags.firstOrNull { it.equals(tag, ignoreCase = true) }
            ?: throw PricingException(ErrorCode.E422, "批号类型不属于该型号：$tag")
    }

    /** 留痕查询的批号只做格式校验，不依赖型号当前配置（主数据可能已删）。 */
    private fun normalizeHistoryTag(raw: String): String {
        val tag = raw.trim()
        if (tag.length > MAX_TAG_LENGTH) {
            throw PricingException(ErrorCode.E422, "批号类型不能超过 $MAX_TAG_LENGTH 个字符")
        }
        if (tag.any { it.isISOControl() }) {
            throw PricingException(ErrorCode.E422, "批号类型不能包含控制字符")
        }
        return tag
    }

    // —— 入参校验 ——

    /** 改价原因必填（`E1003`）、裁剪、限长、拒绝控制字符。 */
    private fun normalizeReason(raw: String): String {
        val reason = raw.trim()
        if (reason.isEmpty()) throw PricingException(ErrorCode.E1003, "改价原因必填")
        if (reason.length > MAX_REASON_LENGTH) {
            throw PricingException(ErrorCode.E422, "改价原因不能超过 $MAX_REASON_LENGTH 个字符")
        }
        if (reason.any { it.isISOControl() }) {
            throw PricingException(ErrorCode.E422, "改价原因不能包含控制字符")
        }
        return reason
    }

    /** 价格必须大于 0（金额格式已由共享 [Money] 保证，这里只做业务下限校验）。 */
    private fun requirePositivePrice(price: Money) {
        if (BigDecimal(price.amount).signum() <= 0) {
            throw PricingException(ErrorCode.E422, "价格必须大于 0：${price.amount}")
        }
    }

    // —— 事件 / 时钟 / 错误翻译 ——

    /** 同步派发给所有监听器；任一失败都让当前事务回滚（见 [PriceChanged]）。 */
    private fun publish(event: PriceChanged) {
        listeners.forEach { listener -> listener.onPriceChanged(event) }
    }

    private fun today(): String = LocalDate.now(clock).format(dateFormat)

    private fun now(): String = OffsetDateTime.now(clock).format(timestampFormat)

    private fun displayTag(tag: String): String = if (tag.isEmpty()) "（无）" else tag

    /**
     * 唯一约束冲突判定：不同 SQLite JDBC 版本可能上报基础码（19）、
     * 主键扩展码（1555）或唯一索引扩展码（2067），三者都按「重复」处理。
     */
    private fun SQLException.isUniqueViolation(): Boolean =
        errorCode == SQLITE_CONSTRAINT_PRIMARY_KEY ||
            errorCode == SQLITE_CONSTRAINT_UNIQUE ||
            sqlState == SQL_STATE_UNIQUE_VIOLATION ||
            message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true)

    /** 内部维度元组：批号已按型号库规范化。 */
    private data class Dimensions(
        val customerId: Long,
        val categoryId: Long,
        val batchTag: String,
    )

    private companion object {
        /** 与 S05 `category_batch_tag.tag` 的最大长度一致。 */
        const val MAX_TAG_LENGTH = 32

        /** 改价原因最大长度。 */
        const val MAX_REASON_LENGTH = 200

        // SQLite 约束错误码：基础码与扩展码（见 https://sqlite.org/rescode.html）。
        const val SQLITE_CONSTRAINT_PRIMARY_KEY = 1555
        const val SQLITE_CONSTRAINT_UNIQUE = 2067

        /** 标准 SQLState：唯一键冲突（部分驱动 / 版本用它替代 vendor code）。 */
        const val SQL_STATE_UNIQUE_VIOLATION = "23505"
    }
}
