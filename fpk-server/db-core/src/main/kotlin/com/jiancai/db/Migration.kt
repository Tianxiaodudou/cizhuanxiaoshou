/*
 * @file fpk-server/db-core/src/main/kotlin/com/jiancai/db/Migration.kt
 * @role 一条迁移 = 一个版本号 + 一组语句。
 * @exports Migration
 * @depends （无）
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayMigrations.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/App.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.db

import java.security.MessageDigest

/**
 * 一条迁移 = 一个版本号 + 一组语句。
 *
 * 规则（写进 module.md，违反必被 [Migrator.migrate] 拦下）：
 * - 版本号全局唯一、按升序应用；只增不改——已上线的迁移文件**一个字都不能改**，
 *   要改表就新增一条迁移（改了会被 checksum 漂移检测拦下）；
 * - 每条语句只写一条 SQL（不靠 db-core 拆分号，避免字符串里的 `;` 被误切）；
 * - 归属模块自己写自己的表：db-core 只负责按序执行与记账，不认识任何业务表。
 *
 * @property version 迁移版本号（>= 1，段位约定见 module.md）。
 * @property name 人类可读名称，参与 checksum，必须全局唯一。
 * @property statements 该迁移要执行的 SQL，顺序敏感。
 */
data class Migration(
    val version: Int,
    val name: String,
    val statements: List<String>,
) {
    init {
        require(version >= 1) { "迁移版本号必须 >= 1（当前：$version）" }
        require(name.isNotBlank()) { "迁移 $version 的 name 不能为空" }
        require(statements.isNotEmpty()) { "迁移 $version（$name）至少需要一条语句" }
        require(statements.none { it.isBlank() }) { "迁移 $version（$name）存在空语句" }
    }

    /**
     * 内容指纹（SHA-256）：由版本号、名称、语句逐字算出。
     *
     * 与语句顺序相关，因此「调换两条语句」同样算漂移，能挡住「本地改了历史迁移却没新增版本」的事故。
     */
    val checksum: String = sha256("$version\n$name\n" + statements.joinToString("\n"))

    /** 日志/报告用标签：`3:create_order_table`。 */
    val label: String get() = "$version:$name"

    companion object {
        fun of(version: Int, name: String, vararg statements: String): Migration =
            Migration(version, name, statements.toList())

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
