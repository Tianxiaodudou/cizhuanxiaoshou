/*
 * @file fpk-server/category/src/test/kotlin/com/jiancai/category/CategoryMigrationsTest.kt
 * @role 迁移验收：号段归属、建表、重复迁移幂等。
 * @exports CategoryMigrationsTest
 * @depends db-core
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.category

import com.jiancai.db.DbCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 迁移验收：号段归属、建表、重复迁移幂等。
 */
class CategoryMigrationsTest {

    @Test
    fun category_tables_are_created_by_the_category_migration() {
        DbCore.testDatabase(CategoryMigrations.all).use { db ->
            assertEquals(CategoryMigrations.VERSION, db.migrator.currentVersion())
            assertTrue(db.migrator.pendingVersions().isEmpty())

            val tables = db.txRunner.run { tx ->
                tx.queryLong(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name IN ('category', 'category_batch_tag')",
                )
            }
            assertEquals(2L, tables)
        }
    }

    @Test
    fun migration_version_is_in_the_category_segment_and_idempotent() {
        assertTrue(CategoryMigrations.VERSION in 300..399, "S05 迁移号段为 300–399")

        DbCore.testDatabase(CategoryMigrations.all).use { db ->
            val report = db.migrator.migrate()
            assertTrue(report.applied.isEmpty(), "重复 migrate 不应重复执行")
            assertEquals(listOf(CategoryMigrations.VERSION), report.skipped)
        }
    }
}
