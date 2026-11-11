/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   27 行  类 DbCoreTest
 * 第   30 行  函数 in_memory_database_accepts_reads_and_writes
 * 第   40 行  函数 one_instance_has_exactly_one_connection
 * 第   49 行  函数 memory_instances_do_not_share_state
 * 第   63 行  函数 foreign_keys_are_enforced
 * 第   80 行  函数 closed_database_rejects_further_work_and_close_is_idempotent
 * 第   91 行  函数 file_database_persists_wal_and_creates_parent_directory
 * 第  116 行  函数 test_database_is_migrated_in_memory_target
 */

package com.jiancai.db

import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 连接层验收：唯一连接、`:memory:` 可用、PRAGMA 生效、关闭语义、文件库持久化。
 */
class DbCoreTest {

    @Test
    fun in_memory_database_accepts_reads_and_writes() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            db.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (tag) VALUES ('a')") }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
            assertTrue(db.config.isInMemory)
        }
    }

    @Test
    fun one_instance_has_exactly_one_connection() {
        DbCore.inMemory().use { db ->
            val first = db.txRunner.run { tx -> tx.connection }
            val second = db.txRunner.run { tx -> tx.connection }
            assertSame(first, second, "同一实例必须复用唯一连接，否则 :memory: 库会分裂成两个互不可见的库")
        }
    }

    @Test
    fun memory_instances_do_not_share_state() {
        DbCore.inMemory().use { first ->
            DbCore.inMemory().use { second ->
                first.createProbeTables()
                first.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (tag) VALUES ('a')") }
                assertEquals(1, first.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
                assertFailsWith<SQLException> {
                    second.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") }
                }
            }
        }
    }

    @Test
    fun foreign_keys_are_enforced() {
        DbCore.inMemory().use { db ->
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("PRAGMA foreign_keys") })
            db.txRunner.run { tx ->
                tx.exec("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                tx.exec("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parent(id))")
                tx.exec("INSERT INTO parent (id) VALUES (1)")
            }
            assertFailsWith<SQLException> {
                db.txRunner.run { tx -> tx.exec("INSERT INTO child (id, parent_id) VALUES (2, 99)") }
            }
            db.txRunner.run { tx -> tx.exec("INSERT INTO child (id, parent_id) VALUES (3, 1)") }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM child") })
        }
    }

    @Test
    fun closed_database_rejects_further_work_and_close_is_idempotent() {
        val db = DbCore.inMemory()
        db.createProbeTables()
        db.close()
        db.close()
        assertTrue(db.isClosed)
        assertFailsWith<DbClosedException> { db.txRunner.run { tx -> tx.queryInt("SELECT 1") } }
        assertFailsWith<DbClosedException> { db.migrator.currentVersion() }
    }

    @Test
    fun file_database_persists_wal_and_creates_parent_directory() {
        val root = Files.createTempDirectory("db-core-file")
        try {
            val path = root.resolve("nested").resolve("app.db").toString()
            val migrations = listOf(
                Migration.of(1, "probe", "CREATE TABLE probe_row (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)"),
            )
            DbCore.open(DbConfig.file(path), migrations).use { db ->
                db.migrator.migrate()
                db.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (id, tag) VALUES (1, 'a')") }
                // 文件库默认 WAL，内存库退化为 MEMORY（见 JournalMode.AUTO）。
                assertEquals("wal", db.txRunner.run { tx -> tx.queryText("PRAGMA journal_mode") })
                assertEquals(path, db.config.path)
            }
            DbCore.open(DbConfig.file(path), migrations).use { db ->
                assertEquals(1, db.migrator.currentVersion())
                assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
                assertTrue(db.migrator.migrate().applied.isEmpty(), "重启后迁移必须幂等")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun test_database_is_migrated_in_memory_target() {
        DbCore.testDatabase(
            listOf(Migration.of(1, "probe", "CREATE TABLE probe_row (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)")),
        ).use { db ->
            assertEquals(1, db.migrator.currentVersion())
            db.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (id, tag) VALUES (1, 'a')") }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
        }
    }
}
