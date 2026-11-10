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

/** 固定时钟对应的创建时间（UTC ISO-8601）。 */
internal const val CREATED_AT: String = "2026-11-11T02:00:00Z"

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟：断言 `created_at` 由服务端产出且可复现。 */
internal fun clockAt(instant: String = CREATED_AT): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

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
