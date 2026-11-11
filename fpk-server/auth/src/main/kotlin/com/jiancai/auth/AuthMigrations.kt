/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   20 行  对象 AuthMigrations
 */

package com.jiancai.auth

import com.jiancai.db.Migration

/**
 * S02 的迁移（版本号段 210–219，见 `db-core/module.md` 与 `config/module.md` 的子段约定：
 * S01 = 200–209、S02 = 210–219、S03 = 220–229）。
 *
 * 组合根把 [all] 与其它模块迁移合并后交给 `DbCore.open(..., migrations)`：
 * ```
 * val db = DbCore.open(config, AuthMigrations.all + otherModules)
 * ```
 * 迁移一经上线不可修改；改表只能追加更高版本号（db-core 的 checksum 会拦截改动）。
 */
object AuthMigrations {

    /** 本模块首个迁移版本（S02 段位 210–219 的首位）。 */
    const val VERSION: Int = 210

    /**
     * 账号表（S02 独占）：
     * - `username_key` 是 trim + 小写的规范键，唯一约束保证「同一账号不重复注册」、登录大小写不敏感；
     * - `password_hash` 只存 `pbkdf2-sha256$迭代次数$盐$派生密钥`，并用 `CHECK ... LIKE 'pbkdf2-%'`
     *   在库层再挡一次明文（应用层之外的第二道闸）；
     * - `role` 存 `Role` 枚举名（ADMIN / STAFF），线值仍以 `shared` 的 `@SerialName` 为唯一真源；
     * - `enabled` 用 0/1 表达停用，登录与 token 校验都会核对。
     */
    val all: List<Migration> = listOf(
        Migration.of(
            VERSION,
            "create_auth_account",
            """
                CREATE TABLE auth_account (
                    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    username      TEXT    NOT NULL CHECK (length(trim(username)) > 0),
                    username_key  TEXT    NOT NULL UNIQUE CHECK (length(trim(username_key)) > 0),
                    display_name  TEXT    NOT NULL CHECK (length(trim(display_name)) > 0),
                    role          TEXT    NOT NULL CHECK (role IN ('ADMIN', 'STAFF')),
                    enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
                    password_hash TEXT    NOT NULL CHECK (password_hash LIKE 'pbkdf2-%'),
                    created_at    TEXT    NOT NULL,
                    updated_at    TEXT    NOT NULL,
                    last_login_at TEXT
                )
            """.trimIndent(),
        ),
    )
}
