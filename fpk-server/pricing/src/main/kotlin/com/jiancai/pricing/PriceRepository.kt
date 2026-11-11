/*
 * @file fpk-server/pricing/src/main/kotlin/com/jiancai/pricing/PriceRepository.kt
 * @role `price` / `price_change_log` 的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core, shared
 * @calledBy fpk-server/pricing/src/main/kotlin/com/jiancai/pricing/PriceService.kt
 * @sideEffects 写 price, price_change_log（INSERT / UPDATE / DELETE）
 */

package com.jiancai.pricing

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.PriceDto

/**
 * 价格持久化形态（内部类型）：只在本模块内流转，对外一律转成 [PriceDto]（铁律 2）。
 */
internal data class StoredPrice(
    val id: Long,
    val customerId: Long,
    val categoryId: Long,
    val batchTag: String,
    val unitPrice: Money,
    val effectiveDate: String,
    val updatedAt: String,
    val updatedBy: Long,
) {
    /**
     * @param today 服务端当天日期（`yyyy-MM-dd`）；定价日不是今天即视为跨日锁定。
     */
    fun toDto(today: String): PriceDto = PriceDto(
        id = id,
        customerId = customerId,
        categoryId = categoryId,
        batchTag = batchTag,
        unitPrice = unitPrice,
        effectiveDate = effectiveDate,
        locked = effectiveDate != today,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
    )
}

/**
 * 改价留痕的写入形态（内部类型）：由 Service 在价格更新成功后构造，Repository 只负责追加。
 */
internal data class PriceChangeRecord(
    val priceId: Long,
    val customerId: Long,
    val categoryId: Long,
    val batchTag: String,
    val oldPrice: Money,
    val newPrice: Money,
    val adminId: Long,
    val reason: String,
    val changedAt: String,
)

/**
 * `price` / `price_change_log` 的唯一 SQL 出口（铁律 10）。
 *
 * 表归 S06 独占，其它模块禁止读写；对外只暴露 [PricePort]。
 * 所有方法都要求调用方已在 `TxRunner.run {}` 事务内（Port 负责），
 * 因此「查价 → 改价 → 留痕 → 回读」不会与其它事务交错。
 *
 * 硬边界：本类**没有**删除 / 修改留痕的方法；库层还有触发器兜底
 * （见 [PricingMigrations]），改价历史只能追加。
 */
internal class PriceRepository {

    /** 按维度取价格；不存在返回 null。批号列是 `COLLATE NOCASE`，大小写不敏感。 */
    fun find(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): StoredPrice? =
        tx.connection.prepareStatement(SELECT_BY_DIMENSION).use { statement ->
            statement.setLong(1, customerId)
            statement.setLong(2, categoryId)
            statement.setString(3, batchTag)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredPrice() else null
            }
        }

    /** 按主键取价格；不存在返回 null。 */
    fun findById(tx: TxContext, id: Long): StoredPrice? =
        tx.connection.prepareStatement(SELECT_BY_ID).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredPrice() else null
            }
        }

    /**
     * 插入首次价格并返回主键。
     *
     * 与 S05 同口径：不用 JDBC `getGeneratedKeys`（不同 SQLite 驱动版本行为不一致），
     * 插入后在同一事务内按唯一键回查 id。
     */
    fun insert(
        tx: TxContext,
        customerId: Long,
        categoryId: Long,
        batchTag: String,
        unitPrice: Money,
        effectiveDate: String,
        updatedAt: String,
        updatedBy: Long,
    ): Long {
        tx.connection.prepareStatement(INSERT_PRICE).use { statement ->
            statement.setLong(1, customerId)
            statement.setLong(2, categoryId)
            statement.setString(3, batchTag)
            statement.setString(4, unitPrice.amount)
            statement.setString(5, effectiveDate)
            statement.setString(6, updatedAt)
            statement.setLong(7, updatedBy)
            statement.executeUpdate()
        }
        return tx.connection.prepareStatement(SELECT_ID_BY_DIMENSION).use { statement ->
            statement.setLong(1, customerId)
            statement.setLong(2, categoryId)
            statement.setString(3, batchTag)
            statement.executeQuery().use { rows ->
                check(rows.next()) {
                    "price 插入后未查到价格：customer=$customerId / category=$categoryId / batch=$batchTag"
                }
                rows.getLong(1)
            }
        }
    }

    /** 更新当日价格（`effective_date` 不变）；返回是否有行被更新。 */
    fun updatePrice(tx: TxContext, id: Long, unitPrice: Money, updatedAt: String, updatedBy: Long): Boolean =
        tx.connection.prepareStatement(UPDATE_PRICE).use { statement ->
            statement.setString(1, unitPrice.amount)
            statement.setString(2, updatedAt)
            statement.setLong(3, updatedBy)
            statement.setLong(4, id)
            statement.executeUpdate() > 0
        }

    /** 追加一条改价留痕；没有任何更新 / 删除入口。 */
    fun insertChangeLog(tx: TxContext, record: PriceChangeRecord) {
        tx.connection.prepareStatement(INSERT_CHANGE_LOG).use { statement ->
            statement.setLong(1, record.priceId)
            statement.setLong(2, record.customerId)
            statement.setLong(3, record.categoryId)
            statement.setString(4, record.batchTag)
            statement.setString(5, record.oldPrice.amount)
            statement.setString(6, record.newPrice.amount)
            statement.setLong(7, record.adminId)
            statement.setString(8, record.reason)
            statement.setString(9, record.changedAt)
            statement.executeUpdate()
        }
    }

    /** 按维度读取留痕，最新在前；批号大小写不敏感。 */
    fun findChangeLogs(
        tx: TxContext,
        customerId: Long,
        categoryId: Long,
        batchTag: String,
    ): List<PriceChangeLog> =
        tx.connection.prepareStatement(SELECT_CHANGE_LOGS).use { statement ->
            statement.setLong(1, customerId)
            statement.setLong(2, categoryId)
            statement.setString(3, batchTag)
            statement.executeQuery().use { rows ->
                val result = ArrayList<PriceChangeLog>()
                while (rows.next()) {
                    result += PriceChangeLog(
                        id = rows.getLong("id"),
                        customerId = rows.getLong("customer_id"),
                        categoryId = rows.getLong("category_id"),
                        batchTag = rows.getString("batch_tag"),
                        oldPrice = Money.ofPlain(rows.getString("old_price")),
                        newPrice = Money.ofPlain(rows.getString("new_price")),
                        adminId = rows.getLong("admin_id"),
                        reason = rows.getString("reason"),
                        changedAt = rows.getString("changed_at"),
                    )
                }
                result
            }
        }

    private fun java.sql.ResultSet.toStoredPrice(): StoredPrice = StoredPrice(
        id = getLong("id"),
        customerId = getLong("customer_id"),
        categoryId = getLong("category_id"),
        batchTag = getString("batch_tag"),
        unitPrice = Money.ofPlain(getString("unit_price")),
        effectiveDate = getString("effective_date"),
        updatedAt = getString("updated_at"),
        updatedBy = getLong("updated_by"),
    )

    private companion object {
        const val COLUMNS =
            "id, customer_id, category_id, batch_tag, unit_price, effective_date, updated_at, updated_by"

        const val SELECT_BY_DIMENSION =
            "SELECT $COLUMNS FROM price WHERE customer_id = ? AND category_id = ? AND batch_tag = ?"

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM price WHERE id = ?"

        const val SELECT_ID_BY_DIMENSION =
            "SELECT id FROM price WHERE customer_id = ? AND category_id = ? AND batch_tag = ?"

        const val INSERT_PRICE =
            "INSERT INTO price " +
                "(customer_id, category_id, batch_tag, unit_price, effective_date, updated_at, updated_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"

        const val UPDATE_PRICE =
            "UPDATE price SET unit_price = ?, updated_at = ?, updated_by = ? WHERE id = ?"

        const val INSERT_CHANGE_LOG =
            "INSERT INTO price_change_log " +
                "(price_id, customer_id, category_id, batch_tag, old_price, new_price, admin_id, reason, changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val SELECT_CHANGE_LOGS =
            "SELECT id, customer_id, category_id, batch_tag, old_price, new_price, admin_id, reason, changed_at " +
                "FROM price_change_log WHERE customer_id = ? AND category_id = ? AND batch_tag = ? " +
                "ORDER BY changed_at DESC, id DESC"
    }
}
