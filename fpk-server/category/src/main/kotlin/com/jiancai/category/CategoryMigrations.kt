/*
 * @file fpk-server/category/src/main/kotlin/com/jiancai/category/CategoryMigrations.kt
 * @role S05 的迁移（版本号段 300–399，见 `db-core/module.md`；本模块取 310）。
 * @exports CategoryMigrations
 * @depends db-core
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.category

import com.jiancai.db.Migration

/**
 * S05 的迁移（版本号段 300–399，见 `db-core/module.md`；本模块取 310）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, CategoryMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object CategoryMigrations {

    /** 本模块迁移版本号（300–399 段内，S04 用 300、S06 用 320）。 */
    const val VERSION: Int = 310

    /**
     * 型号库两张表（S05 独占，其它模块禁止读写）：
     * - `category`：型号主表。`(category_name, model)` 大小写不敏感唯一；
     *   装箱规格平铺成 `pack_*` 列，整组为空表示「该型号未配置装箱规格」；
     * - `category_batch_tag`：批号类型可选值，按 `position` 保序，随型号级联删除。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_category_tables",
            """
                CREATE TABLE category (
                    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
                    category_name          TEXT    NOT NULL COLLATE NOCASE,
                    model                  TEXT    NOT NULL COLLATE NOCASE,
                    unit                   TEXT    NOT NULL,
                    batch_tag_required     INTEGER NOT NULL DEFAULT 0
                                           CHECK (batch_tag_required IN (0, 1)),
                    pack_base_unit         TEXT,
                    pack_package_unit      TEXT,
                    pack_units_per_package INTEGER
                                           CHECK (pack_units_per_package IS NULL
                                                  OR pack_units_per_package > 0),
                    pack_weight_kg         TEXT,
                    pack_volume_m3         TEXT,
                    pack_spec_text         TEXT,
                    enabled                INTEGER NOT NULL DEFAULT 1
                                           CHECK (enabled IN (0, 1)),
                    created_at             TEXT    NOT NULL,
                    updated_at             TEXT,
                    UNIQUE (category_name, model),
                    CHECK (
                        pack_base_unit IS NOT NULL
                        OR (pack_package_unit IS NULL
                            AND pack_units_per_package IS NULL
                            AND pack_weight_kg IS NULL
                            AND pack_volume_m3 IS NULL
                            AND pack_spec_text IS NULL)
                    )
                )
            """.trimIndent(),
            """
                CREATE TABLE category_batch_tag (
                    category_id INTEGER NOT NULL
                                REFERENCES category (id) ON DELETE CASCADE,
                    tag         TEXT    NOT NULL COLLATE NOCASE,
                    position    INTEGER NOT NULL CHECK (position >= 0),
                    PRIMARY KEY (category_id, tag),
                    UNIQUE (category_id, position)
                )
            """.trimIndent(),
        ),
    )
}
