package com.jiancai.db

import java.io.Closeable

/**
 * db-core 门面：进程内唯一 SQLite 连接的持有者，对外只出两个 Port。
 *
 * 组合根（服务启动处）只做一次：
 * ```
 * val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), migrations)
 * db.migrator.migrate()                       // 启动即迁移（幂等，可重复启动）
 * db.txRunner.run { tx -> ... }               // 业务模块从这里拿事务
 * ```
 *
 * 禁止：任何其它模块自己 `DriverManager.getConnection("jdbc:sqlite:...")`（会得到第二个库，
 * 内存库更是直接分裂成两个互不可见的库），需要数据库就调用上述两个 Port。
 */
class DbCore internal constructor(
    private val database: SqliteDatabase,
    /** 事务 Port：跨模块写操作的唯一包裹方式。 */
    val txRunner: TxRunner,
    /** 迁移 Port：按版本号幂等应用 [Migration]。 */
    val migrator: Migrator,
) : Closeable {

    /** 当前连接配置（只读用途，例如日志里打印库路径）。 */
    val config: DbConfig get() = database.config

    val isClosed: Boolean get() = database.isClosed

    /** 关闭唯一连接；可重复调用（第二次及以后无副作用）。关闭后所有 Port 调用抛 [DbClosedException]。 */
    override fun close() = database.close()

    companion object {
        /**
         * 打开唯一连接（文件库会自动创建父目录）。
         *
         * @param migrations 全部模块的迁移（顺序无关，按 [Migration.version] 升序应用）。
         */
        fun open(config: DbConfig = DbConfig(), migrations: List<Migration> = emptyList()): DbCore {
            val database = SqliteDatabase.open(config)
            return try {
                val txRunner = SqliteTxRunner(database)
                // 同一个 txRunner 实例：迁移在业务事务内被调用时才能正确降级为保存点。
                DbCore(database, txRunner, SqliteMigrator(txRunner, migrations))
            } catch (failure: Throwable) {
                runCatching { database.close() }
                throw failure
            }
        }

        /**
         * `:memory:` 库（每个实例一个独立的内存库）。
         *
         * 适合单测：不落盘、不污染部署库；需要建表请传 [migrations] 并调用 [Migrator.migrate]，
         * 或直接用 [testDatabase]。
         */
        fun inMemory(migrations: List<Migration> = emptyList()): DbCore =
            open(DbConfig.inMemory(), migrations)

        /** 测试专用：内存库 + 立即迁移到最新版（失败时自动关闭连接，不泄漏）。 */
        fun testDatabase(migrations: List<Migration> = emptyList()): DbCore {
            val db = open(DbConfig.inMemory(), migrations)
            return try {
                db.migrator.migrate()
                db
            } catch (failure: Throwable) {
                runCatching { db.close() }
                throw failure
            }
        }
    }
}
