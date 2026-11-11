/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   27 行  类 PricingMigrationsTest
 * 第   30 行  函数 pricing_tables_are_created_by_the_pricing_migration
 * 第   39 行  函数 price_dimension_is_unique_and_rejects_a_second_row
 * 第   58 行  函数 price_change_log_rejects_update_and_delete_at_the_database_layer
 * 第   84 行  函数 migration_version_is_in_the_pricing_segment_and_idempotent
 * 第   99 行  函数 insertPrice
 * 第  120 行  函数 insertLog
 */

package com.jiancai.pricing

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S06 迁移与库层硬边界测试。
 *
 * 重点守住「禁止删除改价留痕」：即使绕过 Service 直接写 SQL，库层触发器也必须拒绝
 * `UPDATE` / `DELETE`；这是最后一道防线，不依赖调用方自觉。
 */
class PricingMigrationsTest {

    @Test
    fun pricing_tables_are_created_by_the_pricing_migration() {
        pricingOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("price", "price_change_log"), tables)
        }
    }

    @Test
    fun price_dimension_is_unique_and_rejects_a_second_row() {
        pricingOnlyDb().use { db ->
            db.txRunner.run { tx ->
                insertPrice(tx, customerId = 1, categoryId = 2, batchTag = "A", price = "12.30")

                val failure = assertFailsWith<SQLException> {
                    insertPrice(tx, customerId = 1, categoryId = 2, batchTag = "a", price = "13.00")
                }

                assertTrue(
                    failure.message.orEmpty().contains("UNIQUE", ignoreCase = true),
                    "同一客户 + 型号 + 批号只能有一行价格：${failure.message}",
                )
                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM price"))
            }
        }
    }

    @Test
    fun price_change_log_rejects_update_and_delete_at_the_database_layer() {
        pricingOnlyDb().use { db ->
            db.txRunner.run { tx ->
                val priceId = insertPrice(
                    tx = tx,
                    customerId = 1,
                    categoryId = 2,
                    batchTag = "A",
                    price = "12.30",
                )
                insertLog(tx, priceId)

                assertFailsWith<SQLException> {
                    tx.exec("UPDATE price_change_log SET reason = '被篡改' WHERE id = 1")
                }
                assertFailsWith<SQLException> {
                    tx.exec("DELETE FROM price_change_log WHERE id = 1")
                }

                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM price_change_log"))
                assertEquals("调整价格", tx.queryString("SELECT reason FROM price_change_log WHERE id = 1"))
            }
        }
    }

    @Test
    fun migration_version_is_in_the_pricing_segment_and_idempotent() {
        assertTrue(PricingMigrations.VERSION in 320..329, "S06 使用 320–329 子段")

        pricingOnlyDb().use { db ->
            assertEquals(PricingMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val report = db.migrator.migrate()

            assertTrue(report.applied.isEmpty(), "重复迁移不产生副作用")
            assertTrue(report.skipped.contains(PricingMigrations.VERSION))
            assertEquals(PricingMigrations.VERSION, report.currentVersion)
        }
    }

    private fun insertPrice(
        tx: com.jiancai.db.TxContext,
        customerId: Long,
        categoryId: Long,
        batchTag: String,
        price: String,
    ): Long {
        tx.exec(
            "INSERT INTO price (customer_id, category_id, batch_tag, unit_price, effective_date, updated_at, updated_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            customerId,
            categoryId,
            batchTag,
            price,
            TODAY,
            NOW_TIMESTAMP,
            7L,
        )
        return tx.queryLong("SELECT id FROM price WHERE customer_id = ? AND category_id = ? AND batch_tag = ?", customerId, categoryId, batchTag)
    }

    private fun insertLog(tx: com.jiancai.db.TxContext, priceId: Long) {
        tx.exec(
            "INSERT INTO price_change_log " +
                "(price_id, customer_id, category_id, batch_tag, old_price, new_price, admin_id, reason, changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            priceId,
            1L,
            2L,
            "A",
            "12.30",
            "13.00",
            7L,
            "调整价格",
            NOW_TIMESTAMP,
        )
    }
}
