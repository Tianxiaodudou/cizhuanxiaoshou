/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   39 行  函数 clockAt
 * 第   42 行  函数 testDb
 * 第   45 行  类 CountingTxRunner
 * 第   49 行  函数 run
 * 第   55 行  函数 exec
 * 第   62 行  函数 queryLong
 * 第   68 行  函数 queryString
 */

package com.jiancai.customer

import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：内存库 + 固定时钟 + 事务计数。
 *
 * 生产 SQL 只允许出现在 CustomerRepository（铁律 10）；这里的 SQL 仅用于
 * 验证库层约束与「本模块只拥有 customer 一张表」。
 */

/** 固定时钟的瞬间（UTC，测试输入）。 */
internal const val NOW_INSTANT: String = "2026-11-11T02:00:00Z"

/** 固定时钟按服务端时区（Asia/Shanghai）产出的线格式 `created_at`。 */
internal const val CREATED_AT: String = "2026-11-11T10:00:00+08:00"

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟：断言 `created_at` 由服务端产出且可复现。 */
internal fun clockAt(instant: String = NOW_INSTANT): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

/** 每个测试一个独立内存库，并已迁移到 S04 的最新版本。 */
internal fun testDb(): DbCore = DbCore.testDatabase(CustomerMigrations.all)

/** 事务计数包装：验证便捷重载各开一个事务、带 tx 重载不再自开事务。 */
internal class CountingTxRunner(private val delegate: TxRunner) : TxRunner {
    var runs: Int = 0
        private set

    override fun <T> run(block: (TxContext) -> T): T {
        runs += 1
        return delegate.run(block)
    }
}

internal fun TxContext.exec(sql: String, vararg args: Any?) {
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeUpdate()
    }
}

internal fun TxContext.queryLong(sql: String, vararg args: Any?): Long =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

internal fun TxContext.queryString(sql: String, vararg args: Any?): String? =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
