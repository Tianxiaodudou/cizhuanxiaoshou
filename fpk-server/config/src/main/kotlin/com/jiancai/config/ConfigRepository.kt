/*
 * @file fpk-server/config/src/main/kotlin/com/jiancai/config/ConfigRepository.kt
 * @role `deployment_config` 的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core
 * @calledBy fpk-server/config/src/main/kotlin/com/jiancai/config/ConfigService.kt
 * @sideEffects 写 deployment_config（INSERT / UPDATE / DELETE）
 */

package com.jiancai.config

import com.jiancai.db.TxContext

/**
 * `deployment_config` 的唯一 SQL 出口（铁律 10）。
 *
 * 表归 S01 所有，其它模块不得直接读写；对外只暴露 [ConfigPort]。
 * 表结构由 [ConfigMigrations] 建立，单行（`id = 1`）保存部署级配置。
 */
internal class ConfigRepository {

    /** 读取已保存的商家名称；首次启动（尚未写入）返回 null。 */
    fun findMerchantName(tx: TxContext): String? =
        tx.connection.prepareStatement(SELECT_MERCHANT_NAME).use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    /** 写入 / 覆盖唯一的部署配置行（`id = 1`），并记录服务端 UTC 时间。 */
    fun save(tx: TxContext, merchantName: String, updatedAt: String) {
        tx.connection.prepareStatement(UPSERT_CONFIG).use { statement ->
            statement.setString(1, merchantName)
            statement.setString(2, updatedAt)
            statement.executeUpdate()
        }
    }

    private companion object {
        const val SELECT_MERCHANT_NAME =
            "SELECT merchant_name FROM deployment_config WHERE id = 1"

        val UPSERT_CONFIG = """
            INSERT INTO deployment_config (id, merchant_name, updated_at)
            VALUES (1, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                merchant_name = excluded.merchant_name,
                updated_at = excluded.updated_at
        """.trimIndent()
    }
}
