/*
 * @file fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayMigrations.kt
 * @role S13 的迁移（版本号段 800–899；100 S07、200 S01、210 S02、300 S04、310 S05、320 S06、
 * @exports GatewayMigrations
 * @depends db-core
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.db.Migration

/**
 * S13 的迁移（版本号段 800–899；100 S07、200 S01、210 S02、300 S04、310 S05、320 S06、
 * 400/401 S08、500 S10、510 S11、600 S12、700 S14）。
 *
 * 网关只拥有**一张**表：幂等键台账。它不承载任何业务数据，删掉只会让「重启后重放保护」失效，
 * 不会影响订单 / 结算 / 备注单（真正防重复仍由 S08 / S11 的唯一约束兜底）。
 */
object GatewayMigrations {

    /** 本模块迁移版本号（段位 800–899 的首位）。 */
    const val VERSION: Int = 800

    /**
     * 幂等键台账（S13 独占）：
     *
     * - `idempotency_key` 是客户端请求头里的值（主键，长度上限与 `IdempotencyGuard.MAX_KEY_LENGTH` 一致）；
     * - `fingerprint` 是「方法 + 路径 + 查询 + 请求体」的 SHA-256，用来发现「同 key 不同请求」；
     * - `status` / `body` / `content_type` / `headers_json` 是首次成功响应的完整快照（逐字节重放）；
     * - `expires_at` 是过期时间（毫秒），过期的 key 不再重放，可被新请求复用。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_gateway_idempotency_table",
            """
                CREATE TABLE gateway_idempotency (
                    idempotency_key TEXT    NOT NULL PRIMARY KEY
                                    CHECK (length(idempotency_key) BETWEEN 1 AND 128),
                    fingerprint     TEXT    NOT NULL
                                    CHECK (length(fingerprint) = 64),
                    status          INTEGER NOT NULL CHECK (status BETWEEN 200 AND 299),
                    content_type    TEXT    NOT NULL,
                    headers_json    TEXT    NOT NULL DEFAULT '{}',
                    body            TEXT    NOT NULL,
                    created_at      INTEGER NOT NULL,
                    expires_at      INTEGER NOT NULL
                )
            """.trimIndent(),
            """
                CREATE INDEX idx_gateway_idempotency_expires
                    ON gateway_idempotency (expires_at)
            """.trimIndent(),
        ),
    )
}
