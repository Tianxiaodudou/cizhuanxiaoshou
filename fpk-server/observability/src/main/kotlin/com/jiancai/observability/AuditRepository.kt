/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   22 行  类 AuditRepository
 * 第   25 行  函数 insert
 * 第   45 行  函数 query
 * 第   87 行  函数 countByTarget
 * 第   96 行  对象 ?
 * 第  120 行  类 AuditRecordRow
 */

package com.jiancai.observability

import com.jiancai.db.TxContext
import java.sql.Statement

/**
 * `audit_log` 的唯一 SQL 出口（铁律 10）。
 *
 * 表归 S14 所有；**只有 INSERT 与 SELECT**：审计台账只追加，不回改、不删除。
 */
internal class AuditRepository {

    /** 追加一行，返回自增 id（严格递增，可当时间序用）。 */
    fun insert(tx: TxContext, row: AuditRecordRow): Long {
        tx.connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setString(1, row.action.name)
            statement.setString(2, row.action.targetType)
            statement.setString(3, row.targetNo)
            statement.setLong(4, row.operatorId)
            statement.setString(5, row.operatorLabel)
            statement.setString(6, row.summary)
            statement.setString(7, row.detailsJson)
            statement.setString(8, row.createdAt)
            statement.setString(9, row.traceId)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "audit_log 未返回自增 id" }
                return keys.getLong(1)
            }
        }
    }

    /** 按条件查询（AND 组合，id 倒序）。 */
    fun query(tx: TxContext, filter: AuditQuery): List<AuditRecordRow> {
        val conditions = mutableListOf<String>()
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Unit>()
        filter.action?.let { action ->
            conditions += "action = ?"
            binders += { statement, index -> statement.setString(index, action.name) }
        }
        filter.targetNo?.let { targetNo ->
            conditions += "target_no = ?"
            binders += { statement, index -> statement.setString(index, targetNo) }
        }
        filter.operatorId?.let { operatorId ->
            conditions += "operator_id = ?"
            binders += { statement, index -> statement.setLong(index, operatorId) }
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val sql = SELECT + where + " ORDER BY id DESC LIMIT ?"

        tx.connection.prepareStatement(sql).use { statement ->
            binders.forEachIndexed { offset, binder -> binder(statement, offset + 1) }
            statement.setInt(binders.size + 1, filter.limit)
            statement.executeQuery().use { rows ->
                val result = mutableListOf<AuditRecordRow>()
                while (rows.next()) {
                    result += AuditRecordRow(
                        action = AuditAction.valueOf(rows.getString("action")),
                        targetNo = rows.getString("target_no"),
                        operatorId = rows.getLong("operator_id"),
                        operatorLabel = rows.getString("operator_label"),
                        summary = rows.getString("summary"),
                        detailsJson = rows.getString("details_json"),
                        createdAt = rows.getString("created_at"),
                        traceId = rows.getString("trace_id"),
                        id = rows.getLong("id"),
                    )
                }
                return result
            }
        }
    }

    /** 某业务主键的留痕条数（验收「改价 / 审核有留痕」的直接证据）。 */
    fun countByTarget(tx: TxContext, targetNo: String): Int =
        tx.connection.prepareStatement(COUNT_BY_TARGET).use { statement ->
            statement.setString(1, targetNo)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "audit_log 计数失败：$targetNo" }
                rows.getInt(1)
            }
        }

    private companion object {
        val INSERT = """
            INSERT INTO audit_log (
                action, target_type, target_no, operator_id, operator_label,
                summary, details_json, created_at, trace_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        val SELECT = """
            SELECT id, action, target_no, operator_id, operator_label,
                   summary, details_json, created_at, trace_id
            FROM audit_log
        """.trimIndent()

        const val COUNT_BY_TARGET = "SELECT COUNT(*) FROM audit_log WHERE target_no = ?"
    }
}

/**
 * `audit_log` 的一行（内部形态，不对外返回；对外一律 [AuditRecordDto]）。
 *
 * @property detailsJson 明细键值对象的 JSON 文本
 * @property id 自增序号；插入前为 0
 */
internal data class AuditRecordRow(
    val action: AuditAction,
    val targetNo: String,
    val operatorId: Long,
    val operatorLabel: String?,
    val summary: String?,
    val detailsJson: String,
    val createdAt: String,
    val traceId: String?,
    val id: Long = 0,
)
