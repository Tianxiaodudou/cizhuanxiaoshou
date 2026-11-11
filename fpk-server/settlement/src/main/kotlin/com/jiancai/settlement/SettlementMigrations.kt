/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  对象 SettlementMigrations
 */

package com.jiancai.settlement

import com.jiancai.db.Migration

/**
 * S11 的迁移（版本号段 500–599 内：S10 用 500，S11 取 510）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, SettlementMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object SettlementMigrations {

    /** 首个迁移版本（S11 使用 510–519 子段，避开 S10 的 500）。 */
    const val BASE_VERSION: Int = 510

    /** 当前最新迁移版本。 */
    const val VERSION: Int = 510

    /**
     * 结算记录表（S11 独占，其它模块禁止读写）：
     *
     * - `settle_no`：S07 产出的结算单号（本表不拼号）；
     * - `client_request_id`：提交幂等键，`UNIQUE`，与 S08 的建单幂等口径一致；
     * - `order_no` / `customer_id` / `customer_name`：下单时快照，**不建跨模块外键**
     *   （订单表归 S08，结算只保存单号，应收运行时经 OrderPort 重新读取）；
     * - `direction` / `amount`：收款或退款，金额始终为正数、方向由 `direction` 表达；
     * - `status`：`SUBMITTED / APPROVED / REJECTED / VOID`，只有 `APPROVED` 计入已收；
     * - `remark` 是提交备注，`audit_remark` 是审核备注，两者分开保存，审核不覆盖提交事实；
     * - 审核留痕与状态强一致：`SUBMITTED` 不得带审核人 / 审核时间，其它状态必须带齐。
     *
     * 金额列不用浮点类型，仍是 `Money` 的十进制字符串口径；单据状态 / 方向用枚举名持久化，
     * 线值仍由 shared 的 `@SerialName` 决定（铁律 9）。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            BASE_VERSION,
            "create_settlement_table",
            """
                CREATE TABLE settlement (
                    settle_no         TEXT    NOT NULL PRIMARY KEY
                                      CHECK (length(trim(settle_no)) > 0),
                    client_request_id TEXT    NOT NULL UNIQUE
                                      CHECK (length(trim(client_request_id)) > 0),
                    order_no          TEXT    NOT NULL
                                      CHECK (length(trim(order_no)) > 0),
                    customer_id       INTEGER NOT NULL CHECK (customer_id > 0),
                    customer_name     TEXT    NOT NULL CHECK (length(trim(customer_name)) > 0),
                    direction         TEXT    NOT NULL
                                      CHECK (direction IN ('RECEIPT', 'REFUND')),
                    amount            TEXT    NOT NULL CHECK (length(amount) > 0),
                    status            TEXT    NOT NULL
                                      CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'VOID')),
                    submitted_at      TEXT    NOT NULL,
                    submitted_by      INTEGER NOT NULL CHECK (submitted_by > 0),
                    audited_at        TEXT,
                    audited_by        INTEGER CHECK (audited_by IS NULL OR audited_by > 0),
                    remark            TEXT,
                    audit_remark      TEXT,
                    CHECK (
                        (status = 'SUBMITTED' AND audited_at IS NULL AND audited_by IS NULL)
                        OR
                        (status IN ('APPROVED', 'REJECTED', 'VOID')
                            AND audited_at IS NOT NULL AND audited_by IS NOT NULL)
                    )
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_settlement_order_status
                    ON settlement (order_no, status)
            """.trimIndent(),
            """
                CREATE INDEX idx_settlement_customer_status
                    ON settlement (customer_id, status, submitted_at)
            """.trimIndent(),
            """
                CREATE INDEX idx_settlement_status_submitted
                    ON settlement (status, submitted_at)
            """.trimIndent(),
        ),
    )
}
