/*
 * @file fpk-server/serial/src/main/kotlin/com/jiancai/serial/SerialRepository.kt
 * @role `serial_counter` / `serial_number` 的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core
 * @calledBy fpk-server/serial/src/main/kotlin/com/jiancai/serial/SerialService.kt
 * @sideEffects 写 serial_counter, serial_number（INSERT / UPDATE / DELETE）
 */

package com.jiancai.serial

import com.jiancai.db.TxContext

/**
 * `serial_counter` / `serial_number` 的唯一 SQL 出口（铁律 10）。
 *
 * 两张表都归 S07 所有，其它模块禁止读写；对外只暴露 [SerialPort]。
 */
internal class SerialRepository {

    /**
     * 原子分配下一个序号：键不存在则从 1 开始，存在则 +1，返回本次序号。
     *
     * UPSERT 是单条语句，写锁由 SQLite 保证；调用方已在 TxRunner 事务内，
     * 因此「读-改-写」不会与其它事务交错。
     */
    fun allocateSequence(tx: TxContext, prefix: String, period: String, now: String): Long {
        tx.connection.prepareStatement(UPSERT_COUNTER).use { statement ->
            statement.setString(1, prefix)
            statement.setString(2, period)
            statement.setString(3, now)
            statement.executeUpdate()
        }
        return tx.connection.prepareStatement(SELECT_COUNTER).use { statement ->
            statement.setString(1, prefix)
            statement.setString(2, period)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "serial_counter 分配后缺失：prefix=$prefix, period=$period" }
                rows.getLong(1)
            }
        }
    }

    /** 落一条单号账本；`serial_no` 主键与 `(prefix, period, seq)` 唯一约束是并发兜底。 */
    fun record(tx: TxContext, serialNo: String, prefix: String, period: String, sequence: Long, now: String) {
        tx.connection.prepareStatement(INSERT_SERIAL).use { statement ->
            statement.setString(1, serialNo)
            statement.setString(2, prefix)
            statement.setString(3, period)
            statement.setLong(4, sequence)
            statement.setString(5, now)
            statement.executeUpdate()
        }
    }

    private companion object {
        val UPSERT_COUNTER = """
            INSERT INTO serial_counter (prefix, period, last_value, updated_at)
            VALUES (?, ?, 1, ?)
            ON CONFLICT (prefix, period) DO UPDATE SET
                last_value = last_value + 1,
                updated_at = excluded.updated_at
        """.trimIndent()

        const val SELECT_COUNTER =
            "SELECT last_value FROM serial_counter WHERE prefix = ? AND period = ?"

        const val INSERT_SERIAL =
            "INSERT INTO serial_number (serial_no, prefix, period, seq, created_at) VALUES (?, ?, ?, ?, ?)"
    }
}
