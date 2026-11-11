/*
 * @file fpk-server/db-core/src/main/kotlin/com/jiancai/db/SqliteDatabase.kt
 * @role 进程内**唯一** SQLite 连接（模块内实现，不对外暴露）。
 * @exports （无）
 * @depends （无）
 * @calledBy fpk-server/db-core/src/main/kotlin/com/jiancai/db/DbCore.kt, fpk-server/db-core/src/main/kotlin/com/jiancai/db/SqliteTxRunner.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock

/**
 * 进程内**唯一** SQLite 连接（模块内实现，不对外暴露）。
 *
 * 为什么是单连接：
 * - `:memory:` 库的作用域就是连接本身，多连接等于多个互不可见的库；
 * - SQLite 单写者模型下，一个连接 + 一把可重入锁天然串行化写事务，不会出现 SQLITE_BUSY；
 * - 跨模块事务要求「同一事务包裹多次调用」，共享一条连接是最简单正确的做法。
 *
 * 约束（对其它模块）：拿不到本类，也不允许反射/另开连接，只能通过 [TxRunner] / [Migrator] 使用数据库。
 */
internal class SqliteDatabase private constructor(
    val config: DbConfig,
    private val connection: Connection,
) : AutoCloseable {

    /** 保护单连接的全局锁：可重入，保证同一线程的嵌套事务不会被自己阻塞。 */
    private val lock = ReentrantLock()

    @Volatile
    private var closed = false

    /** 当前事务使用的连接；只允许 [SqliteTxRunner] 与 [SchemaMigrationRepository] 读取。 */
    val jdbcConnection: Connection get() = connection

    val isClosed: Boolean get() = closed

    /**
     * 在持有全局锁、且连接未关闭的前提下执行 [block]。
     *
     * 嵌套调用（同线程）直接重入；其它线程排队，从而把写事务串行化。
     */
    fun <T> locked(block: () -> T): T {
        if (closed) throw DbClosedException()
        lock.lock()
        try {
            if (closed) throw DbClosedException()
            return block()
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            connection.close()
        } catch (failure: SQLException) {
            throw DbException("关闭 SQLite 连接失败：${config.path}", failure)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun open(config: DbConfig): SqliteDatabase {
            if (!config.isInMemory) prepareParentDirectory(config.path)
            val connection = try {
                DriverManager.getConnection("jdbc:sqlite:${config.path}")
            } catch (failure: SQLException) {
                throw DbException("打开 SQLite 失败：${config.path}", failure)
            }
            try {
                applyPragmas(connection, config)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                if (failure is DbException) throw failure
                throw DbException("初始化 SQLite 连接失败：${config.path}", failure)
            }
            return SqliteDatabase(config, connection)
        }

        /** FPK 部署时数据目录可能尚不存在（首次启动），这里统一创建，避免业务模块各自 mkdir。 */
        private fun prepareParentDirectory(path: String) {
            val parent = File(path).absoluteFile.parentFile ?: return
            if (parent.isDirectory) return
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw DbException("无法创建数据库目录：$parent")
            }
        }

        private fun applyPragmas(connection: Connection, config: DbConfig) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=${if (config.foreignKeys) "ON" else "OFF"}")
                statement.execute("PRAGMA busy_timeout=${config.busyTimeoutMs}")
                statement.execute("PRAGMA synchronous=NORMAL")
            }
            val journal = when (config.journalMode) {
                JournalMode.AUTO -> if (config.isInMemory) "MEMORY" else "WAL"
                else -> config.journalMode.name
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode=$journal").use { rows ->
                    if (!rows.next()) throw DbException("设置 journal_mode=$journal 失败：${config.path}")
                    // 内存库会退化（拒绝 WAL 时返回 memory），属预期，不在此处报错。
                }
            }
        }
    }
}
