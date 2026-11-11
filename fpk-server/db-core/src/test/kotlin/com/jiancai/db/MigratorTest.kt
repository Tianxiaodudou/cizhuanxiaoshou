/*
 * @file fpk-server/db-core/src/test/kotlin/com/jiancai/db/MigratorTest.kt
 * @role 迁移验收：按序应用、幂等、失败原子性、checksum 漂移拦截、内存库可用。
 * @exports MigratorTest
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 迁移验收：按序应用、幂等、失败原子性、checksum 漂移拦截、内存库可用。
 */
class MigratorTest {

    private fun probeMigrations() = listOf(
        Migration.of(1, "create_probe_row", "CREATE TABLE probe_row (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)"),
        Migration.of(2, "create_probe_other", "CREATE TABLE probe_other (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)"),
    )

    @Test
    fun migrate_applies_in_version_order() {
        // 故意乱序传入：应用顺序只由版本号决定，与模块注册顺序无关。
        DbCore.inMemory(probeMigrations().reversed()).use { db ->
            val report = db.migrator.migrate()
            assertEquals(listOf(1, 2), report.applied)
            assertTrue(report.skipped.isEmpty())
            assertTrue(report.changed)
            assertEquals(2, report.currentVersion)
            assertEquals(2, db.migrator.currentVersion())
        }
    }

    @Test
    fun migrate_is_idempotent() {
        DbCore.inMemory(probeMigrations()).use { db ->
            db.migrator.migrate()
            val second = db.migrator.migrate()
            assertEquals(emptyList(), second.applied)
            assertEquals(listOf(1, 2), second.skipped)
            assertEquals(2, second.currentVersion)
            assertFalse(second.changed)
            // 账本表只有 2 行，重复执行不会重复记账。
            assertEquals(2, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM schema_migration") })
        }
    }

    @Test
    fun pending_versions_track_unapplied_migrations() {
        DbCore.inMemory(probeMigrations()).use { db ->
            assertEquals(listOf(1, 2), db.migrator.pendingVersions())
            db.migrator.migrate()
            assertEquals(emptyList(), db.migrator.pendingVersions())
            assertTrue(db.migrator.drift().isEmpty())
        }
    }

    @Test
    fun failed_migration_rolls_back_only_itself() {
        val migrations = listOf(
            Migration.of(1, "create_probe_row", "CREATE TABLE probe_row (id INTEGER PRIMARY KEY)"),
            Migration.of(
                2,
                "broken",
                "CREATE TABLE probe_broken (id INTEGER PRIMARY KEY)",
                "INSERT INTO missing_table (id) VALUES (1)",
            ),
        )
        DbCore.inMemory(migrations).use { db ->
            val failure = assertFailsWith<MigrationFailedException> { db.migrator.migrate() }
            assertEquals(2, failure.version)
            // 第 1 条保留，第 2 条整体回滚（建表语句也回滚，不留半张表）。
            assertEquals(1, db.migrator.currentVersion())
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM schema_migration") })
            assertEquals(0, db.txRunner.run { tx ->
                tx.queryInt("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'probe_broken'")
            })
            assertEquals(1, db.txRunner.run { tx ->
                tx.queryInt("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'probe_row'")
            })
            // 未修复前重跑：第 1 条只核对不重跑，第 2 条仍失败，账本不重复记账（幂等）。
            assertEquals(listOf(2), db.migrator.pendingVersions())
            assertFailsWith<MigrationFailedException> { db.migrator.migrate() }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM schema_migration") })
        }
    }

    @Test
    fun checksum_drift_is_rejected_and_reported() {
        val root = Files.createTempDirectory("db-core-drift")
        try {
            val path = root.resolve("app.db").toString()
            val v1 = listOf(Migration.of(1, "create_probe_row", "CREATE TABLE probe_row (id INTEGER PRIMARY KEY)"))
            DbCore.open(DbConfig.file(path), v1).use { db -> db.migrator.migrate() }

            // 历史迁移被改动（这里加了一列）：必须拒绝继续，而不是静默重跑。
            val tampered = listOf(
                Migration.of(1, "create_probe_row", "CREATE TABLE probe_row (id INTEGER PRIMARY KEY, extra TEXT)"),
            )
            DbCore.open(DbConfig.file(path), tampered).use { db ->
                val failure = assertFailsWith<MigrationDriftException> { db.migrator.migrate() }
                assertEquals(1, failure.version)
                assertEquals(1, db.migrator.drift().size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicate_migration_versions_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            DbCore.inMemory(
                listOf(
                    Migration.of(1, "a", "CREATE TABLE a (id INTEGER PRIMARY KEY)"),
                    Migration.of(1, "b", "CREATE TABLE b (id INTEGER PRIMARY KEY)"),
                ),
            )
        }
    }

    @Test
    fun migration_definition_rejects_invalid_input() {
        assertFailsWith<IllegalArgumentException> { Migration(0, "zero", listOf("SELECT 1")) }
        assertFailsWith<IllegalArgumentException> { Migration(1, " ", listOf("SELECT 1")) }
        assertFailsWith<IllegalArgumentException> { Migration(1, "empty", emptyList()) }
    }

    @Test
    fun memory_database_is_a_valid_migration_target() {
        DbCore.testDatabase(probeMigrations()).use { db ->
            assertEquals(2, db.migrator.currentVersion())
            db.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (id, tag) VALUES (1, 'a')") }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
            // 同一实例上重复迁移保持幂等。
            assertTrue(db.migrator.migrate().applied.isEmpty())
        }
    }
}
