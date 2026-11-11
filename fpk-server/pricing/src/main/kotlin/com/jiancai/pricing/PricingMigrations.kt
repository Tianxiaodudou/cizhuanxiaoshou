/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  对象 PricingMigrations
 */

package com.jiancai.pricing

import com.jiancai.db.Migration

/**
 * S06 的迁移（版本号段 300–399，见 `db-core/module.md`；本模块取 320）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, PricingMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object PricingMigrations {

    /** 本模块迁移版本号（300–399 段内：S04 用 300、S05 用 310、S06 用 320）。 */
    const val VERSION: Int = 320

    /**
     * 定价两张表（S06 独占，其它模块禁止读写）：
     *
     * - `price`：恒定价格。维度是 `(customer_id, category_id, batch_tag)`，`batch_tag` 大小写不敏感；
     *   `effective_date` 是「定价日」，不是最后修改时间——当日修改不改它，因此跨过午夜就锁定；
     * - `price_change_log`：改价留痕，**只增不改不删**。库层用两个触发器拒绝 `UPDATE` / `DELETE`，
     *   即使未来有人在 repository 里误写删除语句也会被数据库拦住（硬边界「禁止删除改价留痕」）。
     *
     * 价格为十进制字符串（`Money.amount` 口径），不使用浮点列，避免精度丢失。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_pricing_tables",
            """
                CREATE TABLE price (
                    id             INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    customer_id    INTEGER NOT NULL CHECK (customer_id > 0),
                    category_id    INTEGER NOT NULL CHECK (category_id > 0),
                    batch_tag      TEXT    NOT NULL DEFAULT '' COLLATE NOCASE,
                    unit_price     TEXT    NOT NULL CHECK (length(trim(unit_price)) > 0),
                    effective_date TEXT    NOT NULL
                                   CHECK (effective_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
                    updated_at     TEXT    NOT NULL,
                    updated_by     INTEGER NOT NULL CHECK (updated_by > 0),
                    UNIQUE (customer_id, category_id, batch_tag)
                )
            """.trimIndent(),
            """
                CREATE TABLE price_change_log (
                    id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    price_id    INTEGER NOT NULL REFERENCES price (id),
                    customer_id INTEGER NOT NULL,
                    category_id INTEGER NOT NULL,
                    batch_tag   TEXT    NOT NULL COLLATE NOCASE,
                    old_price   TEXT    NOT NULL,
                    new_price   TEXT    NOT NULL,
                    admin_id    INTEGER NOT NULL CHECK (admin_id > 0),
                    reason      TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
                    changed_at  TEXT    NOT NULL
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_price_change_log_dimension
                    ON price_change_log (customer_id, category_id, batch_tag, changed_at)
            """.trimIndent(),
            """
                CREATE TRIGGER price_change_log_is_append_only_on_update
                BEFORE UPDATE ON price_change_log
                BEGIN
                    SELECT RAISE(ABORT, 'price_change_log is append-only');
                END
            """.trimIndent(),
            """
                CREATE TRIGGER price_change_log_is_append_only_on_delete
                BEFORE DELETE ON price_change_log
                BEGIN
                    SELECT RAISE(ABORT, 'price_change_log is append-only');
                END
            """.trimIndent(),
        ),
    )
}
