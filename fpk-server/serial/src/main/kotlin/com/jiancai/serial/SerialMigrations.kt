/*
 * @file fpk-server/serial/src/main/kotlin/com/jiancai/serial/SerialMigrations.kt
 * @role S07 的迁移（版本号段 100–199，见 `db-core/module.md`）。
 * @exports SerialMigrations
 * @depends db-core
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/App.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.serial

import com.jiancai.db.Migration

/**
 * S07 的迁移（版本号段 100–199，见 `db-core/module.md`）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, SerialMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object SerialMigrations {

    /** 本模块迁移版本号（段位 100–199 的首位）。 */
    const val VERSION: Int = 100

    /**
     * 地基表：
     * - `serial_counter`：按「前缀 + 业务日期」保存已分配到的序号，UPSERT 原子递增；
     * - `serial_number`：已发出的单号账本，`serial_no` 主键 + `(prefix, period, seq)` 唯一约束，
     *   作为并发取号的最后一道兜底。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_serial_tables",
            """
                CREATE TABLE serial_counter (
                    prefix     TEXT    NOT NULL,
                    period     TEXT    NOT NULL,
                    last_value INTEGER NOT NULL CHECK (last_value >= 1),
                    updated_at TEXT    NOT NULL,
                    PRIMARY KEY (prefix, period)
                )
            """.trimIndent(),
            """
                CREATE TABLE serial_number (
                    serial_no  TEXT    NOT NULL PRIMARY KEY,
                    prefix     TEXT    NOT NULL,
                    period     TEXT    NOT NULL,
                    seq        INTEGER NOT NULL CHECK (seq >= 1),
                    created_at TEXT    NOT NULL,
                    UNIQUE (prefix, period, seq)
                )
            """.trimIndent(),
        ),
    )
}
