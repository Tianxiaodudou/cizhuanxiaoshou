/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  类 CategoryMigrationsTest
 * 第   22 行  函数 category_tables_are_created_by_the_category_migration
 * 第   38 行  函数 migration_version_is_in_the_category_segment_and_idempotent
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
