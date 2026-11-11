/*
 * @file fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleRepository.kt
 * @role `settlement` 表的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core, shared
 * @calledBy fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleMath.kt, fpk-server/settlement/src/main/kotlin/com/jiancai/settlement/SettleService.kt
 * @sideEffects 写 settlement（INSERT / UPDATE / DELETE）
 */

package com.jiancai.settlement

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus

/**
 * 结算记录持久化形态（内部类型）：只在 S11 内流转，对外一律转成 [SettleDto]（铁律 2）。
 *
 * [remark] 是提交备注，[auditRemark] 是审核备注；对外 DTO 只暴露一个 `remark` 字段，
 * 按「审核备注优先」合并展示，但库内两份事实都保留。
 */
internal data class StoredSettle(
    val settleNo: String,
    val clientRequestId: String,
    val orderNo: String,
    val customerId: Long,
    val customerName: String,
    val direction: SettleDirection,
    val amount: Money,
    val status: SettleStatus,
    val submittedAt: String,
    val submittedBy: Long,
    val auditedAt: String?,
    val auditedBy: Long?,
    val remark: String?,
    val auditRemark: String?,
) {
    /** 组装对外 DTO：不暴露幂等键 / 持久化细节。 */
    fun toDto(): SettleDto = SettleDto(
        settleNo = settleNo,
        orderNo = orderNo,
        customerId = customerId,
        customerName = customerName,
        direction = direction,
        amount = amount,
        status = status,
        submittedAt = submittedAt,
        submittedBy = submittedBy,
        auditedAt = auditedAt,
        auditedBy = auditedBy,
        remark = auditRemark ?: remark,
    )
}

/**
 * `settlement` 表的唯一 SQL 出口（铁律 10）。
 *
 * 这张表归 S11 独占，其它模块禁止读写；本类不触碰 `order_header` / `order_line`，
 * 订单数据一律由 [SettleService] 经 S08 的 `OrderPort` 读取（铁律 3）。
 *
 * 所有方法都要求调用方已在 `TxRunner.run {}` 事务内（Port 负责），
 * 因此「幂等查重 → 余额判定 → 取号 → 写结算」不会与其它事务交错。
 */
internal class SettleRepository {

    // —— 读 ——

    /** 按幂等键取结算记录；不存在返回 null。 */
    fun findByClientRequestId(tx: TxContext, clientRequestId: String): StoredSettle? =
        tx.connection.prepareStatement(SELECT_BY_REQUEST_ID).use { statement ->
            statement.setString(1, clientRequestId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toSettle() else null }
        }

    /** 按结算单号取记录；不存在返回 null。 */
    fun findBySettleNo(tx: TxContext, settleNo: String): StoredSettle? =
        tx.connection.prepareStatement(SELECT_BY_SETTLE_NO).use { statement ->
            statement.setString(1, settleNo)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toSettle() else null }
        }

    /**
     * 某张订单上**审核通过**的结算记录（收款 / 退款都返回，由 Service 按方向换算符号）。
     *
     * 只有 `APPROVED` 计入已收；`SUBMITTED` / `REJECTED` / `VOID` 都不影响余额。
     */
    fun findApprovedByOrder(tx: TxContext, orderNo: String): List<StoredSettle> =
        tx.connection.prepareStatement(SELECT_APPROVED_BY_ORDER).use { statement ->
            statement.setString(1, orderNo)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toSettle()) } }
        }

    /** 某个客户上**审核通过**的结算记录（已收 / 未收读模型用）。 */
    fun findApprovedByCustomer(tx: TxContext, customerId: Long): List<StoredSettle> =
        tx.connection.prepareStatement(SELECT_APPROVED_BY_CUSTOMER).use { statement ->
            statement.setLong(1, customerId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toSettle()) } }
        }

    /** 客户名称兜底：客户没有任何订单时，用最近一笔结算的快照展示。 */
    fun findCustomerName(tx: TxContext, customerId: Long): String? =
        tx.connection.prepareStatement(SELECT_CUSTOMER_NAME).use { statement ->
            statement.setLong(1, customerId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    // —— 写 ——

    /** 插入结算记录（单号由 S07 产出，本类不生成）。 */
    fun insert(tx: TxContext, settle: StoredSettle) {
        tx.connection.prepareStatement(INSERT).use { statement ->
            statement.setString(1, settle.settleNo)
            statement.setString(2, settle.clientRequestId)
            statement.setString(3, settle.orderNo)
            statement.setLong(4, settle.customerId)
            statement.setString(5, settle.customerName)
            statement.setString(6, settle.direction.name)
            statement.setString(7, settle.amount.amount)
            statement.setString(8, settle.status.name)
            statement.setString(9, settle.submittedAt)
            statement.setLong(10, settle.submittedBy)
            statement.setString(11, settle.auditedAt)
            if (settle.auditedBy == null) {
                statement.setNull(12, java.sql.Types.BIGINT)
            } else {
                statement.setLong(12, settle.auditedBy)
            }
            statement.setString(13, settle.remark)
            statement.setString(14, settle.auditRemark)
            statement.executeUpdate()
        }
    }

    /**
     * 条件审核：只有 `SUBMITTED` 能被推进到 `APPROVED` / `REJECTED`。
     *
     * `WHERE status = 'SUBMITTED'` 是并发兜底：两个管理员同时审核，只有一个能成功，
     * 另一个拿到 0 行由 Service 转 `E5003`。
     *
     * @return false 表示已被其它请求审核（或记录不存在）
     */
    fun markAudited(
        tx: TxContext,
        settleNo: String,
        status: SettleStatus,
        auditedAt: String,
        auditedBy: Long,
        auditRemark: String?,
    ): Boolean = tx.connection.prepareStatement(MARK_AUDITED).use { statement ->
        statement.setString(1, status.name)
        statement.setString(2, auditedAt)
        statement.setLong(3, auditedBy)
        statement.setString(4, auditRemark)
        statement.setString(5, settleNo)
        statement.executeUpdate() > 0
    }

    // —— 映射 ——

    private fun java.sql.ResultSet.toSettle(): StoredSettle = StoredSettle(
        settleNo = getString("settle_no"),
        clientRequestId = getString("client_request_id"),
        orderNo = getString("order_no"),
        customerId = getLong("customer_id"),
        customerName = getString("customer_name"),
        direction = SettleDirection.valueOf(getString("direction")),
        amount = Money.ofPlain(getString("amount")),
        status = SettleStatus.valueOf(getString("status")),
        submittedAt = getString("submitted_at"),
        submittedBy = getLong("submitted_by"),
        auditedAt = getString("audited_at"),
        auditedBy = getLongOrNull("audited_by"),
        remark = getString("remark"),
        auditRemark = getString("audit_remark"),
    )

    /** 可空整型列：`getLong` 会把 NULL 读成 0，审核留痕必须保持 NULL 语义。 */
    private fun java.sql.ResultSet.getLongOrNull(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private companion object {
        const val COLUMNS =
            "settle_no, client_request_id, order_no, customer_id, customer_name, direction, amount, " +
                "status, submitted_at, submitted_by, audited_at, audited_by, remark, audit_remark"

        const val SELECT_BY_REQUEST_ID =
            "SELECT $COLUMNS FROM settlement WHERE client_request_id = ?"

        const val SELECT_BY_SETTLE_NO =
            "SELECT $COLUMNS FROM settlement WHERE settle_no = ?"

        const val SELECT_APPROVED_BY_ORDER =
            "SELECT $COLUMNS FROM settlement WHERE order_no = ? AND status = 'APPROVED' " +
                "ORDER BY submitted_at, settle_no"

        const val SELECT_APPROVED_BY_CUSTOMER =
            "SELECT $COLUMNS FROM settlement WHERE customer_id = ? AND status = 'APPROVED' " +
                "ORDER BY submitted_at, settle_no"

        const val SELECT_CUSTOMER_NAME =
            "SELECT customer_name FROM settlement WHERE customer_id = ? " +
                "ORDER BY submitted_at DESC, settle_no DESC LIMIT 1"

        const val INSERT =
            "INSERT INTO settlement " +
                "(settle_no, client_request_id, order_no, customer_id, customer_name, direction, " +
                "amount, status, submitted_at, submitted_by, audited_at, audited_by, remark, audit_remark) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val MARK_AUDITED =
            "UPDATE settlement SET status = ?, audited_at = ?, audited_by = ?, audit_remark = ? " +
                "WHERE settle_no = ? AND status = 'SUBMITTED'"
    }
}
