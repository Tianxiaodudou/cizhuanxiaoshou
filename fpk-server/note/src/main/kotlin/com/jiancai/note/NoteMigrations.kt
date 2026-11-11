/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  对象 NoteMigrations
 */

package com.jiancai.note

import com.jiancai.db.Migration

/**
 * S10 的迁移（版本号段 500–599，见 `db-core/module.md`；本模块只占 500）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, NoteMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object NoteMigrations {

    /** 本模块迁移版本号：S10 使用 500（S11 预留 510–519）。 */
    const val VERSION: Int = 500

    /**
     * 差异备注单一张表（S10 独占，其它模块禁止读写）：
     *
     * - `note_no`：S07 产出的备注单号，本表只保存，不拼接；
     * - `source_order_no`：关联的原单号快照。**不建外键**：订单表归 S08 独占，本模块只经
     *   `OrderPort` 校验原单（铁律 3 / 10），跨模块外键会把两张表的生命周期绑在一起；
     * - `customer_id`：创建时的客户 id 快照，来自 `OrderPort` 返回的订单 DTO；
     * - `diff_type` / `diff_amount` / `remark`：差异分类、差异金额（两位小数字符串）、备注；
     * - `voided` + `voided_at` / `voided_by` / `void_reason`：作废标记与留痕，四者由 CHECK 保持一致；
     * - 备注单只能作废，不能删除；本表不提供 DELETE 语句。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_diff_note_table",
            """
                CREATE TABLE diff_note (
                    note_no         TEXT    NOT NULL PRIMARY KEY
                                    CHECK (length(trim(note_no)) > 0),
                    source_order_no TEXT    NOT NULL
                                    CHECK (length(trim(source_order_no)) > 0),
                    customer_id     INTEGER NOT NULL CHECK (customer_id > 0),
                    diff_type       TEXT    NOT NULL,
                    diff_amount     TEXT    NOT NULL CHECK (length(diff_amount) > 0),
                    remark          TEXT    NOT NULL CHECK (length(trim(remark)) > 0),
                    voided          INTEGER NOT NULL DEFAULT 0 CHECK (voided IN (0, 1)),
                    created_at      TEXT    NOT NULL CHECK (length(created_at) > 0),
                    created_by      INTEGER NOT NULL CHECK (created_by > 0),
                    voided_at       TEXT,
                    voided_by       INTEGER CHECK (voided_by IS NULL OR voided_by > 0),
                    void_reason     TEXT,
                    CHECK (
                        (voided = 0
                            AND voided_at IS NULL
                            AND voided_by IS NULL
                            AND void_reason IS NULL)
                        OR
                        (voided = 1
                            AND voided_at IS NOT NULL
                            AND voided_by IS NOT NULL
                            AND void_reason IS NOT NULL
                            AND length(trim(void_reason)) > 0)
                    )
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_diff_note_source_order
                    ON diff_note (source_order_no, created_at)
            """.trimIndent(),
            """
                CREATE INDEX idx_diff_note_created
                    ON diff_note (created_at)
            """.trimIndent(),
        ),
    )
}
