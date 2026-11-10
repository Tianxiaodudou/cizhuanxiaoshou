package com.jiancai.db

/*
 * 测试夹具（不是业务代码）：只用假表 probe_* 验证连接 / 事务 / 迁移机制，
 * 不碰任何模块的真实表——表归属各模块，db-core 不认识业务表。
 */

internal fun TxContext.exec(sql: String, vararg args: Any?) {
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.execute()
    }
}

internal fun TxContext.queryInt(sql: String, vararg args: Any?): Int =
    connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
    }

internal fun TxContext.queryText(sql: String): String? =
    connection.prepareStatement(sql).use { statement ->
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

internal fun TxContext.tags(table: String): List<String> =
    connection.prepareStatement("SELECT tag FROM $table ORDER BY id").use { statement ->
        statement.executeQuery().use { rows ->
            val result = ArrayList<String>()
            while (rows.next()) result += rows.getString(1)
            result
        }
    }

/** 建两张假表：`probe_row`（模拟订单模块的表）、`probe_other`（模拟结算模块的表）。 */
internal fun DbCore.createProbeTables() {
    txRunner.run { tx ->
        tx.exec("CREATE TABLE probe_row (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL)")
        tx.exec("CREATE TABLE probe_other (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL)")
    }
}
