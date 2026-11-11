/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   23 行  类 PrintRepository
 * 第   33 行  函数 insertIfUnderLimit
 * 第   52 行  函数 count
 * 第   61 行  对象 ?
 * 第   73 行  类 PrintRecord
 */

package com.jiancai.print

import com.jiancai.db.TxContext
import com.jiancai.shared.enums.DocType
import java.sql.Types

/**
 * `print_job` 的唯一 SQL 出口（铁律 10）。
 *
 * 表归 S12 所有；其它模块禁止读写。打印次数不单独维护计数器，直接 `COUNT(*)`：
 * 台账只增不删，次数就是行数，重启 / 多实例天然一致。
 */
internal class PrintRepository {

    /**
     * 在「当前次数 < [limit]」时原子追加一行。
     *
     * `INSERT ... SELECT ... WHERE (SELECT COUNT(*)) < ?` 把「判上限 + 落台账」压成一条语句，
     * 避免先查后插的窗口；调用方已在 TxRunner 事务内，SQLite 不会出现 SQLITE_BUSY。
     *
     * @return true = 已落一行；false = 已达上限，未写入
     */
    fun insertIfUnderLimit(tx: TxContext, record: PrintRecord, limit: Int): Boolean {
        tx.connection.prepareStatement(INSERT_IF_UNDER_LIMIT).use { statement ->
            statement.setString(1, record.docNo)
            statement.setString(2, record.docType.name)
            statement.setInt(3, record.paperWidthMm)
            statement.setString(4, record.templateVersion)
            statement.setString(5, record.printedAt)
            if (record.printedBy == null) {
                statement.setNull(6, Types.INTEGER)
            } else {
                statement.setLong(6, record.printedBy)
            }
            statement.setString(7, record.docNo)
            statement.setInt(8, limit)
            return statement.executeUpdate() == 1
        }
    }

    /** 某单据已打印次数。 */
    fun count(tx: TxContext, docNo: String): Int =
        tx.connection.prepareStatement(COUNT_BY_DOC_NO).use { statement ->
            statement.setString(1, docNo)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "print_job 计数失败：$docNo" }
                rows.getInt(1)
            }
        }

    private companion object {
        val INSERT_IF_UNDER_LIMIT = """
            INSERT INTO print_job (doc_no, doc_type, paper_width_mm, template_version, printed_at, printed_by)
            SELECT ?, ?, ?, ?, ?, ?
            WHERE (SELECT COUNT(*) FROM print_job WHERE doc_no = ?) < ?
        """.trimIndent()

        const val COUNT_BY_DOC_NO = "SELECT COUNT(*) FROM print_job WHERE doc_no = ?"
    }
}

/** 一次打印的审计元数据（内部形态，不对外返回）。 */
internal data class PrintRecord(
    val docNo: String,
    val docType: DocType,
    val paperWidthMm: Int,
    val templateVersion: String,
    val printedAt: String,
    val printedBy: Long?,
)
