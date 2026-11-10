package com.jiancai.customer

import com.jiancai.db.Migration

/**
 * S04 的迁移（db-core 分配给业务主链 300–399，见 `db-core/module.md`）。
 *
 * 段位内再分子段，避免 S05 / S06 抢号：
 * | 子段 | 归属 |
 * | --- | --- |
 * | 300–309 | S04 customer |
 * | 310–319 | S05 category |
 * | 320–329 | S06 pricing |
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, CustomerMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object CustomerMigrations {

    /** 本模块首个迁移版本（子段 300–309 的首位）。 */
    const val VERSION: Int = 300

    /**
     * 客户表（S04 独占）：
     * - `phone` 保存用户输入的展示值（仅 trim），`phone_key` 保存规范化键
     *   （去掉空白与连字符），唯一约束保证「一个电话只对应一个客户」；
     * - 名称 / 电话 / 地址在库层再挡一次空白：即使绕过 Port 也写不进空客户；
     * - `address` 可空（客户可以只留电话），但非空时必须非空白；
     * - `created_at` 为服务端 UTC ISO-8601（`Instant.toString()`）；
     *   `updated_at` 可空——v1.0.0 没有修改客户的 Port，建档时为 null 表示「从未修改」。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_customer",
            """
                CREATE TABLE customer (
                    id         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    name       TEXT    NOT NULL CHECK (length(trim(name)) > 0),
                    phone      TEXT    NOT NULL CHECK (length(trim(phone)) > 0),
                    phone_key  TEXT    NOT NULL UNIQUE CHECK (length(trim(phone_key)) > 0),
                    address    TEXT    CHECK (address IS NULL OR length(trim(address)) > 0),
                    created_at TEXT    NOT NULL,
                    updated_at TEXT
                )
            """.trimIndent(),
        ),
    )
}
