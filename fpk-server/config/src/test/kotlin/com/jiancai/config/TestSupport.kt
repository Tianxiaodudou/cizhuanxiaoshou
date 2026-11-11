/*
 * @file fpk-server/config/src/test/kotlin/com/jiancai/config/TestSupport.kt
 * @role 固定时钟：验证写入 / 不写入 `updated_at` 的时机。
 * @exports （无）
 * @depends db-core
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.config

import com.jiancai.db.TxContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：只做断言用的查询 / 造数。
 * 生产 SQL 只允许出现在 ConfigRepository（铁律 10）；这里的 SQL 仅用于验证库层约束。
 */

/** 服务端业务时区：与部署环境一致。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟：验证写入 / 不写入 `updated_at` 的时机。 */
internal fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), TEST_ZONE)

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
