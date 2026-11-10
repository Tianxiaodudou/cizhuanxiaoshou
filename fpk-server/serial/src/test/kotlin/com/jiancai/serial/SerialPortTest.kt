package com.jiancai.serial

import com.jiancai.db.DbCore
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.serial.SerialType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验收：前缀与年月日正确、序号口径、事务回滚、唯一约束兜底、序号用尽。
 */
class SerialPortTest {

    /** 2026-11-10 10:00 +08:00。 */
    private val day = clockAt("2026-11-10T02:00:00Z")

    @Test
    fun serial_is_prefix_plus_year_month_day_plus_four_digit_sequence() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            val serial = SerialService(db.txRunner, day)
            SerialType.values().forEach { type ->
                assertEquals(
                    "${type.prefix}202611100001",
                    serial.next(type),
                    "单号必须是「前缀 + 年 + 月日 + 4 位序号」",
                )
            }
        }
    }

    @Test
    fun business_date_follows_the_server_time_zone_across_the_year_boundary() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            // 2026-12-31 23:30 +08:00：仍是 2026 年 12 月 31 日。
            val beforeMidnight = SerialService(db.txRunner, clockAt("2026-12-31T15:30:00Z"))
            assertEquals("PS202612310001", beforeMidnight.next(SerialType.PURCHASE))

            // 2026-12-31 16:30Z = 2027-01-01 00:30 +08:00：跨年、跨月、跨日同时发生。
            val afterMidnight = SerialService(db.txRunner, clockAt("2026-12-31T16:30:00Z"))
            assertEquals("PS202701010001", afterMidnight.next(SerialType.PURCHASE))
        }
    }

    @Test
    fun sequences_increment_per_type_and_reset_on_a_new_day() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            val today = SerialService(db.txRunner, day)
            assertEquals("PS202611100001", today.next(SerialType.PURCHASE))
            assertEquals("PS202611100002", today.next(SerialType.PURCHASE))
            assertEquals("ZS202611100001", today.next(SerialType.DIRECT), "不同前缀各自独立编号")

            val tomorrow = SerialService(db.txRunner, clockAt("2026-11-11T02:00:00Z"))
            assertEquals("PS202611110001", tomorrow.next(SerialType.PURCHASE), "新的一天从 0001 重新开始")
        }
    }

    @Test
    fun sequence_is_persisted_in_the_database_not_in_the_service_instance() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            assertEquals("PS202611100001", SerialService(db.txRunner, day).next(SerialType.PURCHASE))
            // 新实例（模拟重启/另一处装配）必须接着库里的序号继续，而不是重新从 1 开始。
            assertEquals("PS202611100002", SerialService(db.txRunner, day).next(SerialType.PURCHASE))
        }
    }

    @Test
    fun caller_transaction_rollback_does_not_consume_the_number() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            val serial = SerialService(db.txRunner, day)
            assertFailsWith<IllegalStateException> {
                db.txRunner.run { tx ->
                    assertEquals("PS202611100001", serial.next(tx, SerialType.PURCHASE))
                    throw IllegalStateException("业务失败：整个事务回滚")
                }
            }
            assertEquals(
                "PS202611100001",
                serial.next(SerialType.PURCHASE),
                "取号与业务同事务：回滚后序号必须可复用，不能留下空号",
            )
        }
    }

    @Test
    fun unique_constraint_is_the_fallback_when_a_number_is_already_taken() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            // 模拟「账本里已有这个号，但计数器没跟上」：并发进程或历史数据占号。
            db.txRunner.run { tx ->
                tx.exec(
                    "INSERT INTO serial_number (serial_no, prefix, period, seq, created_at) VALUES (?, ?, ?, ?, ?)",
                    "PS202611100001",
                    "PS",
                    "20261110",
                    1L,
                    Instant.parse("2026-11-10T02:00:00Z").toString(),
                )
            }
            val serial = SerialService(db.txRunner, day)
            assertEquals(
                "PS202611100002",
                serial.next(SerialType.PURCHASE),
                "撞唯一约束后必须自动取下一个号，而不是把冲突抛给业务",
            )
            assertEquals("PS202611100003", serial.next(SerialType.PURCHASE))
        }
    }

    @Test
    fun exhausted_sequence_fails_with_a_retryable_serial_error() {
        DbCore.testDatabase(SerialMigrations.all).use { db ->
            db.txRunner.run { tx ->
                tx.exec(
                    "INSERT INTO serial_counter (prefix, period, last_value, updated_at) VALUES (?, ?, ?, ?)",
                    "PS",
                    "20261110",
                    9999L,
                    "2026-11-10T02:00:00Z",
                )
            }
            val serial = SerialService(db.txRunner, day)
            val failure = assertFailsWith<SerialException> { serial.next(SerialType.PURCHASE) }
            assertEquals(ErrorCode.E9001, failure.code)
            assertTrue(failure.message.orEmpty().contains("已用尽"), "异常消息要能定位是序号用尽")

            // 独立取号也必须是事务：失败后计数器回滚到 9999，不会留下 10000 的脏值。
            val lastValue = db.txRunner.run { tx ->
                tx.queryLong(
                    "SELECT last_value FROM serial_counter WHERE prefix = ? AND period = ?",
                    "PS",
                    "20261110",
                )
            }
            assertEquals(9999L, lastValue)
        }
    }
}
