package com.jiancai.serial

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.serial.SerialType
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * [SerialPort] 的唯一实现：事务内取号 + 唯一约束兜底。
 *
 * 每次取号（复用调用方事务或自开事务）的流程：
 * 1. 按「前缀 + 业务日期」把 `serial_counter.last_value` 原子 +1，得到本次序号；
 * 2. 拼出单号并写入 `serial_number`（`serial_no` 主键、`(prefix, period, seq)` 唯一）；
 * 3. 第 2 步撞唯一约束（并发进程 / 历史数据占号）时，回到第 1 步取下一个号重试；
 * 4. 重试 [MAX_ATTEMPTS] 次仍冲突，抛 [SerialException]（E9001，客户端可重试）。
 *
 * 业务日期取 [clock] 所在时区的当日日期；生产用系统默认时区，测试注入固定时钟。
 * 序号按「前缀 + 业务日期」各自从 0001 开始，4 位定长，上限 9999。
 */
class SerialService(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : SerialPort {

    private val repository = SerialRepository()

    override fun next(type: SerialType): String = txRunner.run { tx -> next(tx, type) }

    override fun next(tx: TxContext, type: SerialType): String {
        // 一次取号只认一个业务日期，避免重试过程中跨过午夜。
        val period = LocalDate.now(clock).format(PERIOD_FORMAT)
        val prefix = type.prefix

        repeat(MAX_ATTEMPTS) {
            val now = Instant.now(clock).toString()
            val sequence = repository.allocateSequence(tx, prefix, period, now)
            if (sequence > MAX_SEQUENCE) {
                throw SerialException(
                    "单号序号已用尽：前缀=$prefix，日期=$period，上限=$MAX_SEQUENCE",
                )
            }
            val serialNo = compose(prefix, period, sequence)
            try {
                repository.record(tx, serialNo, prefix, period, sequence, now)
                return serialNo
            } catch (conflict: SQLException) {
                // SQLite 默认 ABORT：只回滚失败语句，事务仍可继续，因此可就地取下一个号。
                if (!conflict.isUniqueViolation()) throw conflict
            }
        }
        throw SerialException("单号生成冲突，重试 $MAX_ATTEMPTS 次仍失败：前缀=$prefix，日期=$period")
    }

    /** 前缀 + `yyyyMMdd` + 4 位序号，例如 `PS202611100001`。 */
    private fun compose(prefix: String, period: String, sequence: Long): String =
        prefix + period + sequence.toString().padStart(SEQUENCE_WIDTH, '0')

    /**
     * 唯一约束冲突判定：不同 SQLite JDBC 版本可能上报基础码（19）、
     * 主键扩展码（1555）或唯一索引扩展码（2067），三者都按「可重试的占号」处理。
     */
    private fun SQLException.isUniqueViolation(): Boolean =
        errorCode == SQLITE_CONSTRAINT ||
            errorCode == SQLITE_CONSTRAINT_PRIMARY_KEY ||
            errorCode == SQLITE_CONSTRAINT_UNIQUE ||
            sqlState == SQL_STATE_UNIQUE_VIOLATION ||
            message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true)

    private companion object {
        /** 序号位数（契约固定 4 位：1 → `0001`）。 */
        const val SEQUENCE_WIDTH = 4

        /** 单日单前缀序号上限（4 位十进制最大值）。 */
        const val MAX_SEQUENCE = 9999L

        /** 唯一约束冲突最大重试次数；正常路径一次成功。 */
        const val MAX_ATTEMPTS = 5

        /** 年月日段格式：`yyyyMMdd`（BASIC_ISO_DATE 即该格式）。 */
        val PERIOD_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        // SQLite 约束错误码：基础码与扩展码（见 https://sqlite.org/rescode.html）。
        const val SQLITE_CONSTRAINT = 19
        const val SQLITE_CONSTRAINT_PRIMARY_KEY = 1555
        const val SQLITE_CONSTRAINT_UNIQUE = 2067

        /** 标准 SQLState：唯一键冲突（部分驱动/版本用它替代 vendor code）。 */
        const val SQL_STATE_UNIQUE_VIOLATION = "23505"
    }
}
