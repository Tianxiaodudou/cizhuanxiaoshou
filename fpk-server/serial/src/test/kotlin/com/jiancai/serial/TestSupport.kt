/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   25 行  函数 clockAt
 * 第   27 行  函数 exec
 * 第   34 行  函数 queryLong
 */

package com.jiancai.serial

import com.jiancai.db.TxContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/*
 * 测试夹具（不是业务代码）：固定时区/时钟 + 少量造数与断言 SQL。
 * 生产 SQL 只允许出现在 SerialRepository（铁律 10）；这里的 SQL 仅用于测试占号/用尽等场景。
 */

/** 业务时区：与部署环境一致（业务日期以服务端本地日期为准）。 */
internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 固定时钟：入参用 UTC `Z` 写法，业务日期按 [TEST_ZONE] 读取本地日期。 */
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
