/*
 * @file fpk-server/order/src/main/kotlin/com/jiancai/order/OrderMigrations.kt
 * @role S08 的迁移（版本号段 400–499，见 `db-core/module.md`；本模块取 400）。
 * @exports OrderMigrations
 * @depends db-core
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.order

import com.jiancai.db.Migration

/**
 * S08 的迁移（版本号段 400–499，见 `db-core/module.md`；本模块取 400）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, OrderMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object OrderMigrations {

    /** 首个迁移版本（300–499 段内：S08 用 400、S09 预留 410–419）。 */
    const val BASE_VERSION: Int = 400

    /** 当前最新迁移版本（1.1.0 追加 401：退货单关联原单号）。 */
    const val VERSION: Int = 401

    /**
     * 订单两张表（S08 独占，其它模块禁止读写）：
     *
     * - `order_header`：订单抬头。`order_no` 是 S07 产出的单号（本表不拼号）；
     *   `client_request_id` 唯一，是建单幂等键；`version` 为乐观锁版本，从 1 开始；
     *   `total_amount / receivable_amount / received_amount` 都是 `Money` 的十进制字符串口径；
     * - `order_line`：订单明细，主键 `(order_no, seq)`，外键级联删除，快照保存品类名 / 型号 /
     *   装箱规格，主数据后续变化不影响历史订单展示。
     *
     * 金额列不用浮点类型，日期列用 `WireFormat.DATE` 的 `yyyy-MM-dd` 并加 GLOB 形状校验。
     *
     * 迁移只增不改：401 只追加 `source_order_no`，不修改 400 的任何语句。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            BASE_VERSION,
            "create_order_tables",
            """
                CREATE TABLE order_header (
                    order_no           TEXT    NOT NULL PRIMARY KEY,
                    order_type         TEXT    NOT NULL,
                    status             TEXT    NOT NULL,
                    customer_id        INTEGER NOT NULL CHECK (customer_id > 0),
                    customer_name      TEXT    NOT NULL CHECK (length(trim(customer_name)) > 0),
                    customer_phone     TEXT    NOT NULL CHECK (length(customer_phone) > 0),
                    customer_address   TEXT,
                    order_date         TEXT    NOT NULL
                                       CHECK (order_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
                    total_amount       TEXT    NOT NULL CHECK (length(total_amount) > 0),
                    receivable_amount  TEXT    NOT NULL CHECK (length(receivable_amount) > 0),
                    received_amount    TEXT    NOT NULL DEFAULT '0.00' CHECK (length(received_amount) > 0),
                    remark             TEXT,
                    version            INTEGER NOT NULL CHECK (version >= 1),
                    client_request_id  TEXT    NOT NULL UNIQUE CHECK (length(trim(client_request_id)) > 0),
                    created_at         TEXT    NOT NULL,
                    created_by         INTEGER NOT NULL CHECK (created_by > 0),
                    updated_at         TEXT
                )
            """.trimIndent(),
            """
                CREATE TABLE order_line (
                    order_no      TEXT    NOT NULL REFERENCES order_header (order_no) ON DELETE CASCADE,
                    seq           INTEGER NOT NULL CHECK (seq >= 1),
                    category_id   INTEGER NOT NULL CHECK (category_id > 0),
                    category_name TEXT    NOT NULL,
                    model         TEXT    NOT NULL,
                    batch_tag     TEXT    NOT NULL DEFAULT '' COLLATE NOCASE,
                    unit          TEXT    NOT NULL,
                    quantity      TEXT    NOT NULL CHECK (length(quantity) > 0),
                    unit_price    TEXT    NOT NULL CHECK (length(unit_price) > 0),
                    amount        TEXT    NOT NULL CHECK (length(amount) > 0),
                    packing_spec  TEXT,
                    PRIMARY KEY (order_no, seq)
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_order_header_customer_date
                    ON order_header (customer_id, order_date, created_at)
            """.trimIndent(),
            """
                CREATE INDEX idx_order_header_date_created
                    ON order_header (order_date, created_at)
            """.trimIndent(),
            """
                CREATE INDEX idx_order_line_price_dimension
                    ON order_line (category_id, batch_tag, order_no)
            """.trimIndent(),
        ),
        Migration.of(
            VERSION,
            "add_order_source_order_no",
            // 1.1.0：退货单关联原单号。历史退货单无法反推原单，保持 NULL（S09 兼容归集模式）。
            "ALTER TABLE order_header ADD COLUMN source_order_no TEXT",
            """
                CREATE INDEX idx_order_header_source_order
                    ON order_header (source_order_no)
            """.trimIndent(),
        ),
    )
}
