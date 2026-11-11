/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   28 行  类 NoteMigrationsTest
 * 第   31 行  函数 diff_note_table_is_created_by_the_note_migration
 * 第   40 行  函数 note_no_is_unique_at_the_database_layer
 * 第   60 行  函数 a_voided_note_must_carry_void_metadata
 * 第   75 行  函数 an_active_note_must_not_carry_void_metadata
 * 第   94 行  函数 customer_id_and_remark_are_checked_by_the_database
 * 第  112 行  函数 migration_version_is_in_the_note_segment_and_idempotent
 * 第  127 行  函数 insertNote
 */

package com.jiancai.note

import com.jiancai.db.TxContext
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S10 迁移与库层约束测试。
 *
 * 重点守住：只建一张 `diff_note`、单号主键唯一、作废标记与留痕一致、金额 / 备注 / 客户校验。
 */
class NoteMigrationsTest {

    @Test
    fun diff_note_table_is_created_by_the_note_migration() {
        noteOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("diff_note"), tables, "S10 只允许拥有 diff_note 一张表（铁律 3）")
        }
    }

    @Test
    fun note_no_is_unique_at_the_database_layer() {
        noteOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertNote(tx, noteNo = "BZ202611110001")

                val failure = assertFailsWith<SQLException> {
                    insertNote(tx, noteNo = "BZ202611110001")
                }

                assertTrue(
                    failure.message.orEmpty().contains("UNIQUE", ignoreCase = true) ||
                        failure.message.orEmpty().contains("PRIMARY KEY", ignoreCase = true),
                    "备注单号必须唯一：${failure.message}",
                )
                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM diff_note"))
            }
        }
    }

    @Test
    fun a_voided_note_must_carry_void_metadata() {
        noteOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertNote(tx, noteNo = "BZ202611110001", voided = 1)
                }
                assertFailsWith<SQLException> {
                    insertNote(tx, noteNo = "BZ202611110002", voided = 2)
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM diff_note"))
            }
        }
    }

    @Test
    fun an_active_note_must_not_carry_void_metadata() {
        noteOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertNote(
                        tx,
                        noteNo = "BZ202611110001",
                        voided = 0,
                        voidedAt = NOW_TIMESTAMP,
                        voidedBy = VOIDER_ID,
                        voidReason = "不应存在",
                    )
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM diff_note"))
            }
        }
    }

    @Test
    fun customer_id_and_remark_are_checked_by_the_database() {
        noteOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertNote(tx, noteNo = "BZ202611110001", customerId = 0L)
                }
                assertFailsWith<SQLException> {
                    insertNote(tx, noteNo = "BZ202611110002", remark = "   ")
                }
                assertFailsWith<SQLException> {
                    insertNote(tx, noteNo = "   ")
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM diff_note"))
            }
        }
    }

    @Test
    fun migration_version_is_in_the_note_segment_and_idempotent() {
        assertTrue(NoteMigrations.VERSION in 500..509, "S10 使用 500–509 子段")

        noteOnlyDb().use { db ->
            assertEquals(NoteMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val report = db.migrator.migrate()

            assertTrue(report.applied.isEmpty(), "重复迁移不产生副作用")
            assertTrue(report.skipped.contains(NoteMigrations.VERSION))
            assertEquals(NoteMigrations.VERSION, report.currentVersion)
        }
    }

    private fun insertNote(
        tx: TxContext,
        noteNo: String,
        customerId: Long = 1L,
        remark: String = "备注",
        voided: Int = 0,
        voidedAt: String? = null,
        voidedBy: Long? = null,
        voidReason: String? = null,
    ) {
        tx.exec(
            "INSERT INTO diff_note " +
                "(note_no, source_order_no, customer_id, diff_type, diff_amount, remark, voided, " +
                "created_at, created_by, voided_at, voided_by, void_reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            noteNo,
            "PS202611110001",
            customerId,
            "PRICE",
            "12.30",
            remark,
            voided,
            NOW_TIMESTAMP,
            OPERATOR_ID,
            voidedAt,
            voidedBy,
            voidReason,
        )
    }
}
