/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   20 行  对象 ObservabilityMigrations
 */

package com.jiancai.observability

import com.jiancai.db.Migration

/**
 * S14 的迁移（版本号段 700–799；100 S07、200 S01、210 S02、300 S04、310 S05、320 S06、
 * 400/401 S08、500 S10、510 S11、600 S12 已占用，S13 api-gateway 不建表）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, ObservabilityMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object ObservabilityMigrations {

    /** 本模块迁移版本号（段位 700–799 的首位）。 */
    const val VERSION: Int = 700

    /**
     * 审计台账（S14 独占，其它模块禁止读写；只能经 [AuditPort] 追加与查询）。
     *
     * - **只追加**：没有 UPDATE / DELETE 语句，也没有删除类 Port；整改要求靠新条目表达；
     * - `action` / `target_type` 存枚举名，线格式由 `shared` 决定（本模块不跨端，仍统一走 shared 口径）；
     * - `details_json` 是字符串键值对象的 JSON 文本（服务端产出，禁止客户端直传）；
     * - `operator_id` 必须是正数，`target_no` 不得为空白；
     * - 索引覆盖三种真实查询：按目标、按动作 + 时间、按操作者。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_audit_log_table",
            """
                CREATE TABLE audit_log (
                    id             INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    action         TEXT    NOT NULL
                                   CHECK (action IN ('PRICE_CHANGE', 'SETTLE_AUDIT')),
                    target_type    TEXT    NOT NULL
                                   CHECK (length(trim(target_type)) > 0),
                    target_no      TEXT    NOT NULL
                                   CHECK (length(trim(target_no)) > 0),
                    operator_id    INTEGER NOT NULL CHECK (operator_id > 0),
                    operator_label TEXT,
                    summary        TEXT,
                    details_json   TEXT    NOT NULL DEFAULT '{}'
                                   CHECK (length(details_json) > 0),
                    created_at     TEXT    NOT NULL
                                   CHECK (length(trim(created_at)) > 0),
                    trace_id       TEXT
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_audit_target
                    ON audit_log (target_type, target_no, id)
            """.trimIndent(),
            """
                CREATE INDEX idx_audit_action_created
                    ON audit_log (action, created_at, id)
            """.trimIndent(),
            """
                CREATE INDEX idx_audit_operator
                    ON audit_log (operator_id, id)
            """.trimIndent(),
        ),
    )
}
