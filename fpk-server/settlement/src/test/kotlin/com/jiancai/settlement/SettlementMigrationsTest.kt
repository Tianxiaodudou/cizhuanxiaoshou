/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   31 行  类 SettlementMigrationsTest
 * 第   34 行  函数 the_settlement_table_is_created_by_the_settlement_migration
 * 第   43 行  函数 settle_no_is_unique_at_the_database_layer
 * 第   63 行  函数 the_client_request_id_is_unique_at_the_database_layer
 * 第   82 行  函数 direction_and_status_are_checked_by_the_database
 * 第   97 行  函数 a_submitted_settlement_must_not_carry_audit_metadata
 * 第  116 行  函数 an_audited_settlement_must_carry_audit_metadata
 * 第  142 行  函数 customer_amount_and_name_are_checked_by_the_database
 * 第  163 行  函数 migration_version_is_in_the_settlement_segment_and_idempotent
 * 第  178 行  函数 insertSettle
 */

package com.jiancai.settlement

import com.jiancai.db.TxContext
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S11 迁移与库层约束测试。
 *
 * 重点守住：只建一张 `settlement`、单号 / 幂等键唯一、方向与状态受 CHECK 约束、
 * 审核留痕与状态强一致（`SUBMITTED` 不带审核字段，终态必须带齐）。
 */
class SettlementMigrationsTest {

    @Test
    fun the_settlement_table_is_created_by_the_settlement_migration() {
        settlementOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("settlement"), tables, "S11 只允许拥有 settlement 一张表（铁律 3）")
        }
    }

    @Test
    fun settle_no_is_unique_at_the_database_layer() {
        settlementOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertSettle(tx, settleNo = "JS202611110001", requestId = "req-1")

                val failure = assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110001", requestId = "req-2")
                }

                assertTrue(
                    failure.message.orEmpty().contains("UNIQUE", ignoreCase = true) ||
                        failure.message.orEmpty().contains("PRIMARY KEY", ignoreCase = true),
                    "结算单号必须唯一：${failure.message}",
                )
                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM settlement"))
            }
        }
    }

    @Test
    fun the_client_request_id_is_unique_at_the_database_layer() {
        settlementOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertSettle(tx, settleNo = "JS202611110001", requestId = "same-req")

                val failure = assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110002", requestId = "same-req")
                }

                assertTrue(
                    failure.message.orEmpty().contains("UNIQUE", ignoreCase = true),
                    "幂等键必须唯一：${failure.message}",
                )
                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM settlement"))
            }
        }
    }

    @Test
    fun direction_and_status_are_checked_by_the_database() {
        settlementOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110001", requestId = "req-1", direction = "TRANSFER")
                }
                assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110002", requestId = "req-2", status = "PAID")
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM settlement"))
            }
        }
    }

    @Test
    fun a_submitted_settlement_must_not_carry_audit_metadata() {
        settlementOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertSettle(
                        tx,
                        settleNo = "JS202611110001",
                        requestId = "req-1",
                        status = "SUBMITTED",
                        auditedAt = NOW_TIMESTAMP,
                        auditedBy = AUDITOR_ID,
                    )
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM settlement"))
            }
        }
    }

    @Test
    fun an_audited_settlement_must_carry_audit_metadata() {
        settlementOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertSettle(
                        tx,
                        settleNo = "JS202611110001",
                        requestId = "req-1",
                        status = "APPROVED",
                    )
                }
                assertFailsWith<SQLException> {
                    insertSettle(
                        tx,
                        settleNo = "JS202611110002",
                        requestId = "req-2",
                        status = "REJECTED",
                        auditedAt = NOW_TIMESTAMP,
                    )
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM settlement"))
            }
        }
    }

    @Test
    fun customer_amount_and_name_are_checked_by_the_database() {
        settlementOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110001", requestId = "req-1", customerId = 0L)
                }
                assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110002", requestId = "req-2", amount = "")
                }
                assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "JS202611110003", requestId = "req-3", customerName = "   ")
                }
                assertFailsWith<SQLException> {
                    insertSettle(tx, settleNo = "   ", requestId = "req-4")
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM settlement"))
            }
        }
    }

    @Test
    fun migration_version_is_in_the_settlement_segment_and_idempotent() {
        assertTrue(SettlementMigrations.VERSION in 510..519, "S11 使用 510–519 子段，避开 S10 的 500")

        settlementOnlyDb().use { db ->
            assertEquals(SettlementMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val report = db.migrator.migrate()

            assertTrue(report.applied.isEmpty(), "重复迁移不产生副作用")
            assertTrue(report.skipped.contains(SettlementMigrations.VERSION))
            assertEquals(SettlementMigrations.VERSION, report.currentVersion)
        }
    }

    private fun insertSettle(
        tx: TxContext,
        settleNo: String,
        requestId: String,
        orderNo: String = "PS202611110001",
        customerId: Long = 1L,
        customerName: String = "张三",
        direction: String = "RECEIPT",
        amount: String = "10.00",
        status: String = "SUBMITTED",
        submittedAt: String = NOW_TIMESTAMP,
        submittedBy: Long = OPERATOR_ID,
        auditedAt: String? = null,
        auditedBy: Long? = null,
        remark: String? = null,
        auditRemark: String? = null,
    ) {
        tx.exec(
            "INSERT INTO settlement " +
                "(settle_no, client_request_id, order_no, customer_id, customer_name, direction, " +
                "amount, status, submitted_at, submitted_by, audited_at, audited_by, remark, audit_remark) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            settleNo,
            requestId,
            orderNo,
            customerId,
            customerName,
            direction,
            amount,
            status,
            submittedAt,
            submittedBy,
            auditedAt,
            auditedBy,
            remark,
            auditRemark,
        )
    }
}
