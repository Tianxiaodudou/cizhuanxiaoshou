/*
 * @file fpk-server/print/src/main/kotlin/com/jiancai/print/PrintMigrations.kt
 * @role S12 的迁移（版本号段 600–699，见 `db-core/module.md`）。
 * @exports PrintMigrations
 * @depends db-core
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.print

import com.jiancai.db.Migration

/**
 * S12 的迁移（版本号段 600–699，见 `db-core/module.md`）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, PrintMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object PrintMigrations {

    /** 本模块迁移版本号（段位 600–699 的首位）。 */
    const val VERSION: Int = 600

    /**
     * 打印台账：一次 `render` 落一行，`COUNT(*)` 就是该单据的打印次数。
     *
     * 只存审计需要的元数据，不存渲染正文（正文可能含客户地址 / 电话，不落库）；
     * 同一单据的多次打印按 `id` 排序即可还原「首打 / 补打」。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_print_job_table",
            """
                CREATE TABLE print_job (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    doc_no           TEXT    NOT NULL CHECK (length(trim(doc_no)) > 0),
                    doc_type         TEXT    NOT NULL CHECK (length(trim(doc_type)) > 0),
                    paper_width_mm   INTEGER NOT NULL CHECK (paper_width_mm > 0),
                    template_version TEXT    NOT NULL CHECK (length(trim(template_version)) > 0),
                    printed_at       TEXT    NOT NULL CHECK (length(trim(printed_at)) > 0),
                    printed_by       INTEGER CHECK (printed_by IS NULL OR printed_by > 0)
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_print_job_doc_no ON print_job (doc_no)
            """.trimIndent(),
        ),
    )
}
