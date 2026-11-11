/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   26 行  类 PrintMigrationsTest
 * 第   29 行  函数 print_job_table_is_created_by_the_print_migration
 * 第   38 行  函数 migration_version_is_in_the_print_segment_and_idempotent
 * 第   54 行  函数 doc_no_index_exists_for_count_queries
 * 第   67 行  函数 database_checks_reject_invalid_print_job_rows
 * 第   82 行  函数 insertJob
 */

package com.jiancai.print

import com.jiancai.db.TxContext
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S12 迁移与库层约束测试。
 *
 * 重点守住：只建 `print_job` 一张表、次数靠台账行数、非法元数据被数据库拒绝。
 */
class PrintMigrationsTest {

    @Test
    fun print_job_table_is_created_by_the_print_migration() {
        printOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("print_job"), tables, "S12 只允许拥有 print_job 一张表（铁律 3）")
        }
    }

    @Test
    fun migration_version_is_in_the_print_segment_and_idempotent() {
        assertTrue(PrintMigrations.VERSION in 600..699, "S12 使用 600–699 段位")

        printOnlyDb().use { db ->
            assertEquals(PrintMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val report = db.migrator.migrate()

            assertTrue(report.applied.isEmpty(), "重复迁移不产生副作用")
            assertTrue(report.skipped.contains(PrintMigrations.VERSION))
            assertEquals(PrintMigrations.VERSION, report.currentVersion)
        }
    }

    @Test
    fun doc_no_index_exists_for_count_queries() {
        printOnlyDb().use { db ->
            val indexCount = db.txRunner.run { tx ->
                tx.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_print_job_doc_no'",
                )
            }

            assertEquals(1L, indexCount, "打印次数按 doc_no 计数，必须有索引")
        }
    }

    @Test
    fun database_checks_reject_invalid_print_job_rows() {
        printOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> { insertJob(tx, docNo = "   ") }
                assertFailsWith<SQLException> { insertJob(tx, docType = " ") }
                assertFailsWith<SQLException> { insertJob(tx, paperWidth = 0) }
                assertFailsWith<SQLException> { insertJob(tx, template = " ") }
                assertFailsWith<SQLException> { insertJob(tx, printedAt = " ") }
                assertFailsWith<SQLException> { insertJob(tx, printedBy = 0L) }

                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM print_job"))
            }
        }
    }

    private fun insertJob(
        tx: TxContext,
        docNo: String = "PS202611110001",
        docType: String = "PURCHASE",
        paperWidth: Int = 80,
        template: String = "1.0.0",
        printedAt: String = NOW_TIMESTAMP,
        printedBy: Long? = PRINTER_ID,
    ) {
        tx.exec(
            "INSERT INTO print_job " +
                "(doc_no, doc_type, paper_width_mm, template_version, printed_at, printed_by) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            docNo,
            docType,
            paperWidth,
            template,
            printedAt,
            printedBy,
        )
    }
}
