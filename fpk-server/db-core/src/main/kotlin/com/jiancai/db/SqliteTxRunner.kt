/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   22 行  类 SqliteTxRunner
 * 第   27 行  函数 run
 * 第   56 行  函数 begin
 * 第   64 行  函数 finish
 * 第   72 行  函数 abort
 * 第   83 行  函数 execute
 * 第   88 行  函数 savepointName
 */

package com.jiancai.db

import java.sql.Connection

/**
 * 事务 Port 的唯一实现：单连接 + 可重入锁 + SAVEPOINT 嵌套。
 *
 * 不对外暴露（`internal`），外部只拿 [TxRunner] 接口，避免绕过事务边界。
 */
internal class SqliteTxRunner(private val database: SqliteDatabase) : TxRunner {

    /** 每线程事务深度：同一线程的嵌套调用共享同一事务，不同线程互不可见（各自排队）。 */
    private val depth = ThreadLocal.withInitial { 0 }

    override fun <T> run(block: (TxContext) -> T): T = database.locked {
        val connection = database.jdbcConnection
        val level = depth.get() + 1
        depth.set(level)
        try {
            begin(connection, level)
            val result = try {
                block(TxContext(connection, level))
            } catch (failure: Throwable) {
                abort(connection, level, failure)
                throw failure
            }
            try {
                finish(connection, level)
            } catch (failure: Throwable) {
                // 提交/释放失败：尽力回滚，绝不留下「半提交」状态。
                abort(connection, level, failure)
                throw failure
            }
            result
        } finally {
            depth.set(level - 1)
            if (level == 1) {
                // 恢复自动提交；若这里失败，连接已不可用，保留原始异常不再覆盖。
                runCatching { connection.autoCommit = true }
            }
        }
    }

    private fun begin(connection: Connection, level: Int) {
        if (level == 1) {
            connection.autoCommit = false
        } else {
            execute(connection, "SAVEPOINT ${savepointName(level)}")
        }
    }

    private fun finish(connection: Connection, level: Int) {
        if (level == 1) {
            connection.commit()
        } else {
            execute(connection, "RELEASE SAVEPOINT ${savepointName(level)}")
        }
    }

    private fun abort(connection: Connection, level: Int, failure: Throwable) {
        runCatching {
            if (level == 1) {
                connection.rollback()
            } else {
                execute(connection, "ROLLBACK TO SAVEPOINT ${savepointName(level)}")
                execute(connection, "RELEASE SAVEPOINT ${savepointName(level)}")
            }
        }.onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
    }

    private fun execute(connection: Connection, sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    /** SAVEPOINT 名只含层级数字，不可能与业务标识冲突。 */
    private fun savepointName(level: Int): String = "dbcore_sp_$level"
}
