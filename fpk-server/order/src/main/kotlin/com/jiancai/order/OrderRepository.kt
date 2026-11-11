/*
 * @file fpk-server/order/src/main/kotlin/com/jiancai/order/OrderRepository.kt
 * @role `order_header` / `order_line` 的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core, shared
 * @calledBy fpk-server/order/src/main/kotlin/com/jiancai/order/OrderPriceRecalculator.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderService.kt
 * @sideEffects 写 order_header, order_line（INSERT / UPDATE / DELETE）
 */

package com.jiancai.order

import com.jiancai.db.TxContext
import com.jiancai.shared.ContractJson
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 订单抬头持久化形态（内部类型）：只在 S08 内流转，对外一律转成 [OrderDto]（铁律 2）。
 */
internal data class StoredOrderHeader(
    val orderNo: String,
    val orderType: OrderType,
    val status: OrderStatus,
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val customerAddress: String?,
    val orderDate: String,
    val totalAmount: Money,
    val receivableAmount: Money,
    val receivedAmount: Money,
    val remark: String?,
    val version: Long,
    val clientRequestId: String,
    val createdAt: String,
    val createdBy: Long,
    val updatedAt: String?,
    /** 1.1.0：退货单指向的原单号；非退货单 / 历史退货单为 null。 */
    val sourceOrderNo: String?,
) {
    /** 组装对外 DTO：明细由调用方一次批量读出，避免 N+1。 */
    fun toDto(lines: List<StoredOrderLine>): OrderDto = OrderDto(
        orderNo = orderNo,
        orderType = orderType,
        status = status,
        customerId = customerId,
        customerName = customerName,
        customerPhone = customerPhone,
        customerAddress = customerAddress,
        orderDate = orderDate,
        lines = lines.map { it.toDto() },
        totalAmount = totalAmount,
        receivableAmount = receivableAmount,
        receivedAmount = receivedAmount,
        remark = remark,
        version = version,
        createdAt = createdAt,
        createdBy = createdBy,
        updatedAt = updatedAt,
        sourceOrderNo = sourceOrderNo,
    )
}

/** 订单明细持久化形态（内部类型）：客户快照 / 装箱规格都在这里，不回查 S04 / S05。 */
internal data class StoredOrderLine(
    val seq: Int,
    val categoryId: Long,
    val categoryName: String,
    val model: String,
    val batchTag: String,
    val unit: PricingUnit,
    val quantity: String,
    val unitPrice: Money,
    val amount: Money,
    val packingSpec: PackingSpec?,
) {
    fun toDto(): LineDto = LineDto(
        seq = seq,
        categoryId = categoryId,
        categoryName = categoryName,
        model = model,
        batchTag = batchTag,
        unit = unit,
        quantity = quantity,
        unitPrice = unitPrice,
        amount = amount,
        packingSpec = packingSpec,
    )
}

/** 历史查询过滤条件（内部类型）：全部为可选，由 Service 校验后传入。 */
internal data class OrderFilter(
    val customerId: Long? = null,
    val orderType: OrderType? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    /** 1.1.0：按单号精确过滤（S09 / S10 / S11 免去翻页探测存在性）。 */
    val orderNo: String? = null,
)

/**
 * `order_header` / `order_line` 的唯一 SQL 出口（铁律 10）。
 *
 * 两张表都归 S08 独占，其它模块禁止读写；对外只暴露 [OrderPort]。
 * 所有方法都要求调用方已在 `TxRunner.run {}` 事务内（Port 负责），
 * 因此「幂等查重 → 取号 → 写抬头 → 写明细」不会与其它事务交错。
 */
internal class OrderRepository {

    // —— 读 ——

    /** 按幂等键取订单抬头；不存在返回 null。 */
    fun findByClientRequestId(tx: TxContext, clientRequestId: String): StoredOrderHeader? =
        tx.connection.prepareStatement(SELECT_HEADER_BY_REQUEST_ID).use { statement ->
            statement.setString(1, clientRequestId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toHeader() else null }
        }

    /** 按单号取订单抬头；不存在返回 null。 */
    fun findByOrderNo(tx: TxContext, orderNo: String): StoredOrderHeader? =
        tx.connection.prepareStatement(SELECT_HEADER_BY_ORDER_NO).use { statement ->
            statement.setString(1, orderNo)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toHeader() else null }
        }

    /** 某张订单的全部明细（按 seq 升序）。 */
    fun findLines(tx: TxContext, orderNo: String): List<StoredOrderLine> =
        tx.connection.prepareStatement(SELECT_LINES_BY_ORDER_NO).use { statement ->
            statement.setString(1, orderNo)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toLine()) }
            }
        }

    /**
     * 批量读多张订单的明细，返回 `orderNo -> lines`（空列表也保证有 key）。
     *
     * 列表页一次查完，避免每张订单一条 SQL；`IN` 占位符过多时按 [MAX_ORDER_NOS_PER_QUERY]
     * 分批，避免触发 SQLite 的变量数上限（当日单列表没有分页上限）。
     */
    fun findLinesByOrderNos(tx: TxContext, orderNos: List<String>): Map<String, List<StoredOrderLine>> {
        if (orderNos.isEmpty()) return emptyMap()
        val result = orderNos.associateWith { mutableListOf<StoredOrderLine>() }.toMutableMap()
        orderNos.chunked(MAX_ORDER_NOS_PER_QUERY).forEach { chunk ->
            val placeholders = chunk.joinToString(", ") { "?" }
            val sql = SELECT_LINES_BY_ORDER_NOS.replace(":orderNos", placeholders)
            tx.connection.prepareStatement(sql).use { statement ->
                chunk.forEachIndexed { index, orderNo -> statement.setString(index + 1, orderNo) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val orderNo = rows.getString("order_no")
                        result.getValue(orderNo) += rows.toLine()
                    }
                }
            }
        }
        return result
    }

    /** 服务端某业务日的全部订单，按创建时间倒序。 */
    fun findByOrderDate(tx: TxContext, orderDate: String): List<StoredOrderHeader> =
        tx.connection.prepareStatement(SELECT_HEADERS_BY_DATE).use { statement ->
            statement.setString(1, orderDate)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toHeader()) } }
        }

    /** 符合过滤条件的订单总数（分页用）。 */
    fun count(tx: TxContext, filter: OrderFilter): Long {
        val query = buildFilter(filter)
        val sql = "SELECT COUNT(*) FROM order_header" + query.where
        return tx.connection.prepareStatement(sql).use { statement ->
            bind(statement, query.args)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    /** 符合过滤条件的订单分页，按创建时间倒序（同一时间按单号倒序，保证稳定）。 */
    fun findPage(tx: TxContext, filter: OrderFilter, limit: Int, offset: Long): List<StoredOrderHeader> {
        val query = buildFilter(filter)
        val sql = "SELECT $HEADER_COLUMNS FROM order_header" + query.where +
            " ORDER BY created_at DESC, order_no DESC LIMIT ? OFFSET ?"
        return tx.connection.prepareStatement(sql).use { statement ->
            bind(statement, query.args)
            statement.setInt(query.args.size + 1, limit)
            statement.setLong(query.args.size + 2, offset)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toHeader()) } }
        }
    }

    /**
     * 价格维度命中的、仍可重算的当日订单单号（`PriceChanged` 重算用）。
     *
     * 只返回服务端当天、状态为已下单 / 部分已收的订单；已结清 / 已作废 / 历史订单不参与重算。
     * 批号列是 `COLLATE NOCASE`，大小写不敏感。
     */
    fun findSameDayOrderNosByPrice(
        tx: TxContext,
        customerId: Long,
        categoryId: Long,
        batchTag: String,
        orderDate: String,
        editableStatuses: List<OrderStatus>,
    ): List<String> {
        val placeholders = editableStatuses.joinToString(", ") { "?" }
        val sql = SELECT_SAME_DAY_ORDER_NOS_BY_PRICE.replace(":statuses", placeholders)
        return tx.connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, customerId)
            statement.setLong(2, categoryId)
            statement.setString(3, batchTag)
            statement.setString(4, orderDate)
            editableStatuses.forEachIndexed { index, status ->
                statement.setString(5 + index, status.name)
            }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    // —— 写 ——

    /** 插入订单抬头（单号为 S07 产出，本类不生成）。 */
    fun insertHeader(tx: TxContext, header: StoredOrderHeader) {
        tx.connection.prepareStatement(INSERT_HEADER).use { statement ->
            statement.setString(1, header.orderNo)
            statement.setString(2, header.orderType.name)
            statement.setString(3, header.status.name)
            statement.setLong(4, header.customerId)
            statement.setString(5, header.customerName)
            statement.setString(6, header.customerPhone)
            statement.setString(7, header.customerAddress)
            statement.setString(8, header.orderDate)
            statement.setString(9, header.totalAmount.amount)
            statement.setString(10, header.receivableAmount.amount)
            statement.setString(11, header.receivedAmount.amount)
            statement.setString(12, header.remark)
            statement.setLong(13, header.version)
            statement.setString(14, header.clientRequestId)
            statement.setString(15, header.createdAt)
            statement.setLong(16, header.createdBy)
            statement.setString(17, header.updatedAt)
            statement.setString(18, header.sourceOrderNo)
            statement.executeUpdate()
        }
    }

    /** 插入一条订单明细（装箱规格按共享契约的 JSON 结构保存快照）。 */
    fun insertLine(tx: TxContext, orderNo: String, line: StoredOrderLine) {
        tx.connection.prepareStatement(INSERT_LINE).use { statement ->
            statement.setString(1, orderNo)
            statement.setInt(2, line.seq)
            statement.setLong(3, line.categoryId)
            statement.setString(4, line.categoryName)
            statement.setString(5, line.model)
            statement.setString(6, line.batchTag)
            statement.setString(7, line.unit.name)
            statement.setString(8, line.quantity)
            statement.setString(9, line.unitPrice.amount)
            statement.setString(10, line.amount.amount)
            statement.setString(11, line.packingSpec?.let { ContractJson.json.encodeToString(PackingSpec.serializer(), it) })
            statement.executeUpdate()
        }
    }

    /**
     * 条件更新订单抬头（乐观锁）：`WHERE order_no = ? AND version = ?`。
     *
     * @return false 表示版本已被其它请求推进（或订单不存在），由 Service 抛 `E2002`
     */
    fun updateHeader(
        tx: TxContext,
        orderNo: String,
        expectedVersion: Long,
        newVersion: Long,
        status: OrderStatus,
        totalAmount: Money,
        receivableAmount: Money,
        remark: String?,
        updatedAt: String,
    ): Boolean = tx.connection.prepareStatement(UPDATE_HEADER).use { statement ->
        statement.setString(1, status.name)
        statement.setString(2, totalAmount.amount)
        statement.setString(3, receivableAmount.amount)
        statement.setString(4, remark)
        statement.setLong(5, newVersion)
        statement.setString(6, updatedAt)
        statement.setString(7, orderNo)
        statement.setLong(8, expectedVersion)
        statement.executeUpdate() > 0
    }

    /** 改单时整单替换明细：先删后插，两条语句在同一事务内。 */
    fun deleteLines(tx: TxContext, orderNo: String) {
        tx.connection.prepareStatement(DELETE_LINES).use { statement ->
            statement.setString(1, orderNo)
            statement.executeUpdate()
        }
    }

    /** `PriceChanged` 重算：只更新单条明细的单价与金额（数量、快照不变）。 */
    fun updateLinePricing(tx: TxContext, orderNo: String, seq: Int, unitPrice: Money, amount: Money) {
        tx.connection.prepareStatement(UPDATE_LINE_PRICING).use { statement ->
            statement.setString(1, unitPrice.amount)
            statement.setString(2, amount.amount)
            statement.setString(3, orderNo)
            statement.setInt(4, seq)
            statement.executeUpdate()
        }
    }

    // —— 映射 ——

    private fun java.sql.ResultSet.toHeader(): StoredOrderHeader = StoredOrderHeader(
        orderNo = getString("order_no"),
        orderType = OrderType.valueOf(getString("order_type")),
        status = OrderStatus.valueOf(getString("status")),
        customerId = getLong("customer_id"),
        customerName = getString("customer_name"),
        customerPhone = getString("customer_phone"),
        customerAddress = getString("customer_address"),
        orderDate = getString("order_date"),
        totalAmount = Money.ofPlain(getString("total_amount")),
        receivableAmount = Money.ofPlain(getString("receivable_amount")),
        receivedAmount = Money.ofPlain(getString("received_amount")),
        remark = getString("remark"),
        version = getLong("version"),
        clientRequestId = getString("client_request_id"),
        createdAt = getString("created_at"),
        createdBy = getLong("created_by"),
        updatedAt = getString("updated_at"),
        sourceOrderNo = getString("source_order_no"),
    )

    private fun java.sql.ResultSet.toLine(): StoredOrderLine = StoredOrderLine(
        seq = getInt("seq"),
        categoryId = getLong("category_id"),
        categoryName = getString("category_name"),
        model = getString("model"),
        batchTag = getString("batch_tag"),
        unit = PricingUnit.valueOf(getString("unit")),
        quantity = getString("quantity"),
        unitPrice = Money.ofPlain(getString("unit_price")),
        amount = Money.ofPlain(getString("amount")),
        packingSpec = getString("packing_spec")
            ?.let { ContractJson.json.decodeFromString<PackingSpec>(it) },
    )

    // —— 动态条件（值全部走占位符，不拼接客户端字符串）——

    private fun buildFilter(filter: OrderFilter): FilterQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        filter.customerId?.let {
            clauses += "customer_id = ?"
            args += it
        }
        filter.orderType?.let {
            clauses += "order_type = ?"
            args += it.name
        }
        filter.orderNo?.let {
            clauses += "order_no = ?"
            args += it
        }
        filter.fromDate?.let {
            clauses += "order_date >= ?"
            args += it
        }
        filter.toDate?.let {
            clauses += "order_date <= ?"
            args += it
        }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        return FilterQuery(where, args)
    }

    private fun bind(statement: java.sql.PreparedStatement, args: List<Any>) {
        args.forEachIndexed { index, argument ->
            when (argument) {
                is Long -> statement.setLong(index + 1, argument)
                else -> statement.setString(index + 1, argument.toString())
            }
        }
    }

    private data class FilterQuery(val where: String, val args: List<Any>)

    private companion object {
        const val HEADER_COLUMNS =
            "order_no, order_type, status, customer_id, customer_name, customer_phone, " +
                "customer_address, order_date, total_amount, receivable_amount, received_amount, " +
                "remark, version, client_request_id, created_at, created_by, updated_at, source_order_no"

        const val SELECT_HEADER_BY_ORDER_NO =
            "SELECT $HEADER_COLUMNS FROM order_header WHERE order_no = ?"

        const val SELECT_HEADER_BY_REQUEST_ID =
            "SELECT $HEADER_COLUMNS FROM order_header WHERE client_request_id = ?"

        const val SELECT_HEADERS_BY_DATE =
            "SELECT $HEADER_COLUMNS FROM order_header WHERE order_date = ? " +
                "ORDER BY created_at DESC, order_no DESC"

        const val LINE_COLUMNS =
            "order_no, seq, category_id, category_name, model, batch_tag, unit, quantity, " +
                "unit_price, amount, packing_spec"

        const val SELECT_LINES_BY_ORDER_NO =
            "SELECT $LINE_COLUMNS FROM order_line WHERE order_no = ? ORDER BY seq"

        const val SELECT_LINES_BY_ORDER_NOS =
            "SELECT $LINE_COLUMNS FROM order_line WHERE order_no IN (:orderNos) ORDER BY order_no, seq"

        const val INSERT_HEADER =
            "INSERT INTO order_header " +
                "(order_no, order_type, status, customer_id, customer_name, customer_phone, " +
                "customer_address, order_date, total_amount, receivable_amount, received_amount, " +
                "remark, version, client_request_id, created_at, created_by, updated_at, source_order_no) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val INSERT_LINE =
            "INSERT INTO order_line " +
                "(order_no, seq, category_id, category_name, model, batch_tag, unit, quantity, " +
                "unit_price, amount, packing_spec) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val UPDATE_HEADER =
            "UPDATE order_header SET status = ?, total_amount = ?, receivable_amount = ?, remark = ?, " +
                "version = ?, updated_at = ? WHERE order_no = ? AND version = ?"

        const val DELETE_LINES = "DELETE FROM order_line WHERE order_no = ?"

        const val UPDATE_LINE_PRICING =
            "UPDATE order_line SET unit_price = ?, amount = ? WHERE order_no = ? AND seq = ?"

        const val SELECT_SAME_DAY_ORDER_NOS_BY_PRICE =
            "SELECT DISTINCT h.order_no FROM order_header h " +
                "JOIN order_line l ON l.order_no = h.order_no " +
                "WHERE h.customer_id = ? AND l.category_id = ? AND l.batch_tag = ? " +
                "AND h.order_date = ? AND h.status IN (:statuses) " +
                "ORDER BY h.order_no"

        /** 单条 `IN` 的最大占位符数（远低于 SQLite 的变量上限，留足余量）。 */
        const val MAX_ORDER_NOS_PER_QUERY = 400
    }
}
