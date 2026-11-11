/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   29 行  类 CustomerRepository
 * 第   32 行  函数 findByPhone
 * 第   36 行  函数 findById
 * 第   47 行  函数 insert
 * 第   70 行  函数 queryOne
 * 第   82 行  函数 toStoredCustomer
 * 第   91 行  对象 ?
 * 第  111 行  类 StoredCustomer
 * 第  119 行  函数 toDto
 */

package com.jiancai.customer

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CustomerDto
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * `customer` 表的唯一 SQL 出口（铁律 10）。
 *
 * 表由 [CustomerMigrations] 创建；其它模块禁止直接读写本表，需要客户数据只能调 [CustomerPort]。
 * 电话是客户的唯一 id：`phone` 列自带唯一约束，查询按原值精确匹配（服务端不存第二套规范化键）。
 * 出参先转成内部的 [StoredCustomer]，再由 Port 层转 [CustomerDto]（铁律 2）。
 */
internal class CustomerRepository {

    /** 按电话（唯一 id）查找；未找到返回 null。 */
    fun findByPhone(tx: TxContext, phone: String): StoredCustomer? =
        queryOne(tx, SELECT_BY_PHONE) { statement -> statement.setString(1, phone) }

    /** 按主键查找；未找到返回 null（由 Port 层决定 `E404`）。 */
    fun findById(tx: TxContext, id: Long): StoredCustomer? =
        queryOne(tx, SELECT_BY_ID) { statement -> statement.setLong(1, id) }

    /**
     * 插入客户并返回主键。
     *
     * 与 S02 auth 相同，不用 JDBC `getGeneratedKeys`（不同 SQLite 驱动版本行为不一致），
     * 插入后在同一事务内按唯一键回查 id，结果确定。
     *
     * @throws java.sql.SQLException 电话违反唯一约束时（Port 层回查已有客户并幂等返回）
     */
    fun insert(
        tx: TxContext,
        name: String,
        phone: String,
        address: String?,
        createdAt: String,
    ): Long {
        tx.connection.prepareStatement(INSERT_CUSTOMER).use { statement ->
            statement.setString(1, name)
            statement.setString(2, phone)
            statement.setString(3, address)
            statement.setString(4, createdAt)
            statement.executeUpdate()
        }
        return tx.connection.prepareStatement(SELECT_ID_BY_PHONE).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "customer 插入后未查到客户：$phone" }
                rows.getLong(1)
            }
        }
    }

    private fun queryOne(
        tx: TxContext,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): StoredCustomer? =
        tx.connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredCustomer() else null
            }
        }

    private fun ResultSet.toStoredCustomer(): StoredCustomer = StoredCustomer(
        id = getLong("id"),
        name = getString("name"),
        phone = getString("phone"),
        address = getString("address"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private companion object {
        const val COLUMNS = "id, name, phone, address, created_at, updated_at"

        // 含模板的 SQL 用普通 val（const 初始值只放纯字面量）。
        val SELECT_BY_PHONE = "SELECT $COLUMNS FROM customer WHERE phone = ?"

        val SELECT_BY_ID = "SELECT $COLUMNS FROM customer WHERE id = ?"

        const val SELECT_ID_BY_PHONE = "SELECT id FROM customer WHERE phone = ?"

        const val INSERT_CUSTOMER =
            "INSERT INTO customer (name, phone, address, created_at) VALUES (?, ?, ?, ?)"
    }
}

/**
 * 客户的持久化形态（内部类型）：只在本模块内流转，**不**作为 Port 出参。
 *
 * 对外一律转成 [CustomerDto]（铁律 2）。
 */
internal data class StoredCustomer(
    val id: Long,
    val name: String,
    val phone: String,
    val address: String?,
    val createdAt: String,
    val updatedAt: String?,
) {
    fun toDto(): CustomerDto = CustomerDto(
        id = id,
        name = name,
        phone = phone,
        address = address,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
