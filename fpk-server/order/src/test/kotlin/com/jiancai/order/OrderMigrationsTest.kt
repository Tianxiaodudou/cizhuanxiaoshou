/*
 * @file fpk-server/order/src/test/kotlin/com/jiancai/order/OrderMigrationsTest.kt
 * @role S08 迁移与库层约束测试。
 * @exports OrderMigrationsTest
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.order

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S08 迁移与库层约束测试。
 *
 * 重点守住：幂等键唯一、明细外键级联、日期形状；以及「S08 只拥有两张表」。
 */
class OrderMigrationsTest {

    @Test
    fun order_tables_are_created_by_the_order_migration() {
        orderOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("order_header", "order_line"), tables)
        }
    }

    @Test
    fun client_request_id_is_unique_at_the_database_layer() {
        orderOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertHeader(tx, orderNo = "PS202611110001", requestId = "req-1")

                val failure = assertFailsWith<SQLException> {
                    insertHeader(tx, orderNo = "PS202611110002", requestId = "req-1")
                }

                assertTrue(
                    failure.message.orEmpty().contains("UNIQUE", ignoreCase = true),
                    "同一 client_request_id 只能有一张订单：${failure.message}",
                )
                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM order_header"))
            }
        }
    }

    @Test
    fun order_lines_cascade_when_the_header_is_deleted() {
        orderOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertHeader(tx, orderNo = "PS202611110001", requestId = "req-1")
                insertLine(tx, orderNo = "PS202611110001", seq = 1)

                tx.exec("DELETE FROM order_header WHERE order_no = ?", "PS202611110001")

                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM order_line"))
            }
        }
    }

    @Test
    fun order_line_requires_an_existing_header() {
        orderOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertLine(tx, orderNo = "PS202611119999", seq = 1)
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM order_line"))
            }
        }
    }

    @Test
    fun order_date_must_match_the_wire_date_shape() {
        orderOnlyDb().use { db ->
            db.txRunner.run { tx ->
                assertFailsWith<SQLException> {
                    insertHeader(tx, orderNo = "PS202611110001", requestId = "req-bad", orderDate = "2026-1-1")
                }
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM order_header"))
            }
        }
    }

    @Test
    fun source_order_no_is_nullable_for_legacy_rows_and_persisted_when_present() {
        orderOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertHeader(tx, orderNo = "PS202611110001", requestId = "req-legacy")

                assertNull(
                    tx.queryString("SELECT source_order_no FROM order_header WHERE order_no = ?", "PS202611110001"),
                    "1.0.0 历史退货单没有原单号，迁移后保持 NULL",
                )

                tx.exec(
                    "UPDATE order_header SET source_order_no = ? WHERE order_no = ?",
                    "PS202611110000",
                    "PS202611110001",
                )
                assertEquals(
                    "PS202611110000",
                    tx.queryString("SELECT source_order_no FROM order_header WHERE order_no = ?", "PS202611110001"),
                )
            }
        }
    }

    @Test
    fun migration_version_is_in_the_order_segment_and_idempotent() {
        assertTrue(OrderMigrations.VERSION in 400..409, "S08 使用 400–409 子段")

        orderOnlyDb().use { db ->
            assertEquals(OrderMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val report = db.migrator.migrate()

            assertTrue(report.applied.isEmpty(), "重复迁移不产生副作用")
            assertTrue(report.skipped.contains(OrderMigrations.VERSION))
            assertEquals(OrderMigrations.VERSION, report.currentVersion)
        }
    }

    private fun insertHeader(
        tx: com.jiancai.db.TxContext,
        orderNo: String,
        requestId: String,
        orderDate: String = TODAY,
    ) {
        tx.exec(
            "INSERT INTO order_header " +
                "(order_no, order_type, status, customer_id, customer_name, customer_phone, " +
                "customer_address, order_date, total_amount, receivable_amount, received_amount, " +
                "remark, version, client_request_id, created_at, created_by, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            orderNo,
            "PURCHASE",
            "CREATED",
            1L,
            "张三",
            "13800000000",
            "建材市场 1 号",
            orderDate,
            "24.60",
            "24.60",
            "0.00",
            null,
            1L,
            requestId,
            NOW_TIMESTAMP,
            OPERATOR_ID,
            null,
        )
    }

    private fun insertLine(tx: com.jiancai.db.TxContext, orderNo: String, seq: Int) {
        tx.exec(
            "INSERT INTO order_line " +
                "(order_no, seq, category_id, category_name, model, batch_tag, unit, quantity, " +
                "unit_price, amount, packing_spec) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            orderNo,
            seq,
            2L,
            "水泥",
            "P.O 42.5",
            "A",
            "TON",
            "2",
            "12.30",
            "24.60",
            null,
        )
    }
}
