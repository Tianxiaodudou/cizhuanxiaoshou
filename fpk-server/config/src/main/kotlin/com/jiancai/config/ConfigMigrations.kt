package com.jiancai.config

import com.jiancai.db.Migration

/**
 * S01 的迁移（版本号段 200–299，见 `db-core/module.md`）。
 *
 * 段位内再分子段，避免 S02 / S03 抢号：
 * | 子段 | 归属 |
 * | --- | --- |
 * | 200–209 | S01 config |
 * | 210–219 | S02 auth |
 * | 220–229 | S03 account |
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, ConfigMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object ConfigMigrations {

    /** 本模块首个迁移版本号（子段 200–209 的首位）。 */
    const val VERSION: Int = 200

    /**
     * 部署配置表：单行（`id = 1`）保存商家名称。
     *
     * 约束把「部署级唯一一份配置」和「商家名称非空白」落到库层：
     * 客户端即使绕过接口也写不进第二行 / 空名称。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_deployment_config",
            """
                CREATE TABLE deployment_config (
                    id            INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                    merchant_name TEXT    NOT NULL CHECK (length(trim(merchant_name)) > 0),
                    updated_at    TEXT    NOT NULL
                )
            """.trimIndent(),
        ),
    )
}
