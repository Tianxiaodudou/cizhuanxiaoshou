/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   84 行  类 SettleService
 * 第   99 行  函数 submit
 * 第  102 行  函数 submit
 * 第  160 行  函数 audit
 * 第  163 行  函数 audit
 * 第  198 行  函数 getBalance
 * 第  201 行  函数 getBalance
 * 第  227 行  函数 findOrder
 * 第  233 行  函数 loadOrders
 * 第  249 行  函数 requireOperator
 * 第  265 行  函数 ensureWithinOutstanding
 * 第  301 行  函数 nextSettleNo
 * 第  309 行  函数 normalizeRequestId
 * 第  322 行  函数 normalizeIdentifier
 * 第  335 行  函数 normalizeOptionalText
 * 第  348 行  函数 normalizeAmount
 * 第  360 行  函数 now
 * 第  363 行  函数 matches
 * 第  376 行  函数 isUniqueViolation
 * 第  383 行  对象 ?
 */

package com.jiancai.settlement

import com.jiancai.account.AccountAdminException
import com.jiancai.account.AccountAdminPort
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.order.OrderPort
import com.jiancai.serial.SerialException
import com.jiancai.serial.SerialPort
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.DocType
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.serial.SerialType
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [SettlePort] 的唯一实现：结算用例层。
 *
 * 职责边界：
 * - **不判权限**：构造参数与参数里都没有角色 / token；`submittedBy` / `auditedBy` 只用于留痕，
 *   以及经 S03 校验「操作者账号真实存在」；登录与权限只在 S13 api-gateway（铁律 7）；
 * - **应收只来自 S08**：提交与审核都调用 `OrderPort.getReceivable` 重新读取；订单存在性与
 *   客户快照经 `OrderPort.getRecent(orderNo)` 读取，绝不触碰订单表（铁律 3 / 10）；
 * - **已收永远不动**：本模块只写自己的 `settlement` 表，从不回写订单的 `received_amount` /
 *   状态；已收是「审核通过的结算记录」的派生值；
 * - **单号只来自 S07**：调用 `SerialPort.next(tx, SerialType.of(DocType.SETTLEMENT))`，
 *   本类没有任何前缀字面量，也不拼接 / 截取单号（铁律 8）；
 * - **服务端产出**：单号、时间戳、金额、状态、客户快照全部由服务端产出，客户端只展示。
 *
 * 金额判定（验收「审核流通过」「已收金额不被改动」）：
 * ```
 * 已收 = Σ APPROVED 收款 − Σ APPROVED 退款
 * 待结 = 应收(S08 重新读取) − 已收
 * 收款：待结 > 0 且 金额 ≤ 待结，否则 E5002
 * 退款：待结 < 0 且 金额 ≤ −待结，否则 E5002
 * ```
 * 提交时先判一次（当场判定），审核通过时用当时的应收 / 已收再判一次，避免多笔待审叠加超收。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：全部写操作经它包裹）
 * @param orders S08 订单 Port（只读：存在性 / 客户快照 / 应收）
 * @param accounts S03 账号管理 Port（只读：校验操作者账号存在；不判断角色）
 * @param serials S07 单号 Port（结算单号的唯一来源）
 * @param clock 服务端时钟；测试注入固定时钟以断言时间戳
 */
class SettleService(
    private val txRunner: TxRunner,
    private val orders: OrderPort,
    private val accounts: AccountAdminPort,
    private val serials: SerialPort,
    private val clock: Clock = Clock.systemDefaultZone(),
) : SettlePort {

    private val repository = SettleRepository()

    /** 时间戳按共享契约 [WireFormat.TIMESTAMP] 产出（含时区偏移）。 */
    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)

    // —— 提交（幂等 + 当场判定）——

    override fun submit(cmd: SubmitSettleCmd, submittedBy: Long): SettleDto =
        txRunner.run { tx -> submit(tx, cmd, submittedBy) }

    override fun submit(tx: TxContext, cmd: SubmitSettleCmd, submittedBy: Long): SettleDto {
        val requestId = normalizeRequestId(cmd.clientRequestId)
        val orderNo = normalizeIdentifier(cmd.orderNo, "订单号")
        val amount = normalizeAmount(cmd.amount)
        val remark = normalizeOptionalText(cmd.remark, "备注")

        // 幂等优先：同请求 id 同载荷直接返回首单，不再取号 / 再判定。
        repository.findByClientRequestId(tx, requestId)?.let { existing ->
            if (existing.matches(orderNo, cmd.direction, amount)) return existing.toDto()
            throw SettleException(ErrorCode.E5004, "同一请求 id 已用于另一笔结算，请更换请求 id")
        }

        requireOperator(tx, submittedBy, "提交人")
        val order = findOrder(tx, orderNo)
            ?: throw SettleException(ErrorCode.E2001, "订单不存在：$orderNo")

        // 应收从 S08 重新读取，绝不使用客户端上传值或本地缓存。
        val receivable = orders.getReceivable(tx, orderNo)
        val received = SettleMath.received(repository.findApprovedByOrder(tx, orderNo))
        ensureWithinOutstanding(cmd.direction, amount, receivable, received)

        val stored = StoredSettle(
            settleNo = nextSettleNo(tx),
            clientRequestId = requestId,
            orderNo = orderNo,
            customerId = order.customerId,
            customerName = order.customerName,
            direction = cmd.direction,
            amount = amount,
            status = SettleStatus.SUBMITTED,
            submittedAt = now(),
            submittedBy = submittedBy,
            auditedAt = null,
            auditedBy = null,
            remark = remark,
            auditRemark = null,
        )

        try {
            repository.insert(tx, stored)
        } catch (failure: SQLException) {
            // 并发兜底：另一请求先落库同一幂等键时，返回已有记录而不是 500。
            if (failure.isUniqueViolation()) {
                repository.findByClientRequestId(tx, requestId)?.let { existing ->
                    if (existing.matches(orderNo, cmd.direction, amount)) return existing.toDto()
                    throw SettleException(ErrorCode.E5004, "同一请求 id 已用于另一笔结算", failure)
                }
                // 幂等键没有命中，只可能是结算单号冲突：保留 S07 的可重试口径。
                throw SettleException(ErrorCode.E9001, "结算单号生成冲突，请重试", failure)
            }
            throw failure
        }

        return stored.toDto()
    }

    // —— 审核（当时应收再判一次）——

    override fun audit(cmd: AuditSettleCmd, auditedBy: Long): SettleDto =
        txRunner.run { tx -> audit(tx, cmd, auditedBy) }

    override fun audit(tx: TxContext, cmd: AuditSettleCmd, auditedBy: Long): SettleDto {
        val settleNo = normalizeIdentifier(cmd.settleNo, "结算单号")
        val auditRemark = normalizeOptionalText(cmd.remark, "审核备注")

        requireOperator(tx, auditedBy, "审核人")
        val existing = repository.findBySettleNo(tx, settleNo)
            ?: throw SettleException(ErrorCode.E5001, "结算单不存在：$settleNo")
        if (existing.status != SettleStatus.SUBMITTED) {
            throw SettleException(ErrorCode.E5003, "结算单已审核，不可重复审核：$settleNo")
        }

        val target = if (cmd.approved) SettleStatus.APPROVED else SettleStatus.REJECTED
        if (target == SettleStatus.APPROVED) {
            // 审核通过才会改变「已收」，因此必须用当时的应收 / 已收再判一次余额。
            val receivable = orders.getReceivable(tx, existing.orderNo)
            val received = SettleMath.received(repository.findApprovedByOrder(tx, existing.orderNo))
            ensureWithinOutstanding(existing.direction, existing.amount, receivable, received)
        }

        val auditedAt = now()
        // 条件更新兜底并发：两个管理员同时审核，只有一个能把 SUBMITTED 推进到终态。
        if (!repository.markAudited(tx, settleNo, target, auditedAt, auditedBy, auditRemark)) {
            throw SettleException(ErrorCode.E5003, "结算单已被其它请求审核：$settleNo")
        }

        return existing.copy(
            status = target,
            auditedAt = auditedAt,
            auditedBy = auditedBy,
            auditRemark = auditRemark,
        ).toDto()
    }

    // —— 已收 / 未收读模型 ——

    override fun getBalance(customerId: Long): ReceivableDto =
        txRunner.run { tx -> getBalance(tx, customerId) }

    override fun getBalance(tx: TxContext, customerId: Long): ReceivableDto {
        if (customerId <= 0) throw SettleException(ErrorCode.E422, "客户 id 不合法：$customerId")

        val ordersOfCustomer = loadOrders(tx, customerId)
        val receivable = SettleMath.sum(
            ordersOfCustomer.map { order ->
                if (order.status == OrderStatus.VOID) SettleMath.ZERO else order.receivableAmount
            },
        )
        val received = SettleMath.received(repository.findApprovedByCustomer(tx, customerId))
        val customerName = ordersOfCustomer.firstOrNull()?.customerName
            ?: repository.findCustomerName(tx, customerId)
            ?: ""

        return ReceivableDto(
            customerId = customerId,
            customerName = customerName,
            receivableAmount = receivable,
            receivedAmount = received,
            unreceivedAmount = SettleMath.subtract(receivable, received),
        )
    }

    // —— S08 / S03 访问（只经 Port）——

    /** 按单号精确读取订单 DTO（客户快照）；不存在返回 null。 */
    private fun findOrder(tx: TxContext, orderNo: String): OrderDto? =
        orders.getRecent(tx, OrderQuery(orderNo = orderNo, page = 1, pageSize = 1))
            .items
            .firstOrNull()

    /** 某客户的全部订单（按页读 S08，不扫描订单表）。 */
    private fun loadOrders(tx: TxContext, customerId: Long): List<OrderDto> {
        val all = mutableListOf<OrderDto>()
        var page = 1
        while (true) {
            val result = orders.getRecent(
                tx,
                OrderQuery(customerId = customerId, page = page, pageSize = MAX_ORDER_PAGE_SIZE),
            )
            all += result.items
            val fetched = page.toLong() * MAX_ORDER_PAGE_SIZE
            if (result.items.isEmpty() || fetched >= result.total) return all
            page += 1
        }
    }

    /** 操作者存在性经 S03 校验；角色 / 权限不在这里判断（S13 负责）。 */
    private fun requireOperator(tx: TxContext, operatorId: Long, label: String) {
        if (operatorId <= 0) throw SettleException(ErrorCode.E422, "$label id 不合法：$operatorId")
        try {
            accounts.get(tx, operatorId)
        } catch (failure: AccountAdminException) {
            throw SettleException(failure.code, failure.message ?: "${label}不存在：$operatorId", failure)
        }
    }

    // —— 金额判定 ——

    /**
     * 收款 / 退款都不得越过「待结」的符号与绝对值：
     * 收款只在待结为正时发生、且不能超过待结；退款只在待结为负时发生、且不能超过应退额。
     * 两者都不会把余额推过 0，避免超收或超退。
     */
    private fun ensureWithinOutstanding(
        direction: SettleDirection,
        amount: Money,
        receivable: Money,
        received: Money,
    ) {
        val outstanding = SettleMath.subtract(receivable, received)
        when (direction) {
            SettleDirection.RECEIPT -> {
                if (SettleMath.compare(outstanding, SettleMath.ZERO) <= 0 ||
                    SettleMath.compare(amount, outstanding) > 0
                ) {
                    throw SettleException(
                        ErrorCode.E5002,
                        "收款金额超过未收金额：应收 ${receivable.amount}，已收 ${received.amount}，" +
                            "本次 ${amount.amount}",
                    )
                }
            }

            SettleDirection.REFUND -> {
                if (SettleMath.compare(outstanding, SettleMath.ZERO) >= 0 ||
                    SettleMath.compare(amount, SettleMath.negate(outstanding)) > 0
                ) {
                    throw SettleException(
                        ErrorCode.E5002,
                        "退款金额超过应退金额：待结 ${outstanding.amount}，本次 ${amount.amount}",
                    )
                }
            }
        }
    }

    // —— 取号 / 入参规范化 / 时钟 ——

    /** 结算单号只由 S07 产出；取号失败原样保留 S07 的错误码。 */
    private fun nextSettleNo(tx: TxContext): String =
        try {
            serials.next(tx, SerialType.of(DocType.SETTLEMENT))
        } catch (failure: SerialException) {
            throw SettleException(failure.code, failure.message ?: "结算单号生成失败", failure)
        }

    /** 幂等键：裁剪后非空、≤ 128 字符、无控制字符。 */
    private fun normalizeRequestId(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw SettleException(ErrorCode.E422, "请求 id 不能为空")
        if (text.length > MAX_REQUEST_ID_LENGTH) {
            throw SettleException(ErrorCode.E422, "请求 id 不能超过 $MAX_REQUEST_ID_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw SettleException(ErrorCode.E422, "请求 id 不能包含控制字符")
        }
        return text
    }

    /** 单号类入参只做形状校验（长度 / 控制字符），前缀与格式由 S07 保证。 */
    private fun normalizeIdentifier(raw: String, label: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw SettleException(ErrorCode.E422, "$label 不能为空")
        if (text.length > MAX_IDENTIFIER_LENGTH) {
            throw SettleException(ErrorCode.E422, "$label 不能超过 $MAX_IDENTIFIER_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw SettleException(ErrorCode.E422, "$label 不能包含控制字符")
        }
        return text
    }

    /** 可选文本：空白视为未填写；超长 / 控制字符拒绝。 */
    private fun normalizeOptionalText(raw: String?, label: String): String? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text.length > MAX_TEXT_LENGTH) {
            throw SettleException(ErrorCode.E422, "$label 不能超过 $MAX_TEXT_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw SettleException(ErrorCode.E422, "$label 不能包含控制字符")
        }
        return text
    }

    /** 金额必须能归一化为两位小数且 > 0；方向由 `direction` 表达，金额本身始终为正。 */
    private fun normalizeAmount(raw: Money): Money {
        val amount = try {
            Money.ofPlain(raw.amount)
        } catch (failure: IllegalArgumentException) {
            throw SettleException(ErrorCode.E422, "金额格式不正确：${raw.amount}", failure)
        }
        if (BigDecimal(amount.amount).signum() <= 0) {
            throw SettleException(ErrorCode.E422, "结算金额必须大于 0：${amount.amount}")
        }
        return amount
    }

    private fun now(): String = OffsetDateTime.now(clock).format(timestampFormat)

    /** 幂等命中判定：订单 / 方向 / 金额都一致才算同一次提交。 */
    private fun StoredSettle.matches(
        orderNo: String,
        direction: SettleDirection,
        amount: Money,
    ): Boolean =
        this.orderNo == orderNo &&
            this.direction == direction &&
            SettleMath.compare(this.amount, amount) == 0

    /**
     * 唯一约束冲突判定：不同 SQLite JDBC 版本可能上报基础码（19）、
     * 主键扩展码（1555）或唯一索引扩展码（2067），三者都按「重复」处理。
     */
    private fun SQLException.isUniqueViolation(): Boolean =
        errorCode == SQLITE_CONSTRAINT ||
            errorCode == SQLITE_CONSTRAINT_PRIMARY_KEY ||
            errorCode == SQLITE_CONSTRAINT_UNIQUE ||
            sqlState == SQL_STATE_UNIQUE_VIOLATION ||
            message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true)

    private companion object {
        /** 单号类字段最大长度（与 S08 保持同一口径）。 */
        const val MAX_IDENTIFIER_LENGTH: Int = 32

        /** 幂等键最大长度（与 S08 的 `client_request_id` 口径一致）。 */
        const val MAX_REQUEST_ID_LENGTH: Int = 128

        /** 备注 / 审核备注最大长度。 */
        const val MAX_TEXT_LENGTH: Int = 500

        /** 历史订单翻页上限：与 S08 `OrderPort.getRecent` 的单页上限一致。 */
        const val MAX_ORDER_PAGE_SIZE: Int = 200

        // SQLite 约束错误码：基础码与扩展码（见 https://sqlite.org/rescode.html）。
        const val SQLITE_CONSTRAINT = 19
        const val SQLITE_CONSTRAINT_PRIMARY_KEY = 1555
        const val SQLITE_CONSTRAINT_UNIQUE = 2067

        /** 标准 SQLState：唯一键冲突（部分驱动 / 版本用它替代 vendor code）。 */
        const val SQL_STATE_UNIQUE_VIOLATION = "23505"
    }
}
