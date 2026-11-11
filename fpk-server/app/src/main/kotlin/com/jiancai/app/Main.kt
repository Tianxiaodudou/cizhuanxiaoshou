/*
 * @file fpk-server/app/src/main/kotlin/com/jiancai/app/Main.kt
 * @role 启动自检入口：打开库 → 迁移 → 装配 Port → （可选）创建初始管理员，然后退出。
 * @exports main
 * @depends auth, config, db-core, shared
 * @calledBy （无）
 * @sideEffects 文件 IO
 */

package com.jiancai.app

import com.jiancai.auth.AuthException
import com.jiancai.config.DeploymentConfig
import com.jiancai.db.DbConfig
import com.jiancai.shared.errors.ErrorCode

/**
 * 启动自检入口：打开库 → 迁移 → 装配 Port → （可选）创建初始管理员，然后退出。
 *
 * 这是一个**部署自检 / 一次性引导**命令，不是常驻 HTTP 服务：网关（S13 api-gateway）落地后
 * 由它的 `main` 复用 [App.create] / [App.open]，不会用到这里。
 *
 * 必需的环境变量：
 * - `JIANCAI_DB_PATH`      SQLite 文件路径（父目录自动创建）；
 * - `JIANCAI_AUTH_SECRET`  token 签名密钥（≥32 字节，禁止硬编码）；
 * - 商家名称：`JIANCAI_MERCHANT_NAME` 或 `-Djiancai.merchantName`（新建库时必需）。
 *
 * 可选：
 * - `JIANCAI_ADMIN_PASSWORD` 仅在库中没有任何账号时创建初始管理员 `admin`；已有账号则跳过。
 */
fun main() {
    val dbPath = requireEnv("JIANCAI_DB_PATH")
    val secret = requireEnv("JIANCAI_AUTH_SECRET").toByteArray(Charsets.UTF_8)

    App.open(
        dbConfig = DbConfig.file(dbPath),
        deploymentConfig = DeploymentConfig.fromEnvironment(),
        secret = secret,
    ).use { app ->
        val password = System.getenv("JIANCAI_ADMIN_PASSWORD")?.takeIf { it.isNotBlank() }
        if (password != null) {
            try {
                app.bootstrapFirstAdmin(username = "admin", password = password, displayName = "管理员")
                println("已创建初始管理员：admin")
            } catch (failure: AuthException) {
                // 已有账号（E6001）是正常情况：重复启动不应失败，也不应覆盖任何账号。
                if (failure.code != ErrorCode.E6001) throw failure
                println("库中已有账号，跳过初始管理员创建")
            }
        }

        println(
            "启动自检通过：商家名称=${app.config.getMerchantName()}，" +
                "数据库=$dbPath，迁移版本=${app.db.migrator.currentVersion()}",
        )
    }
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("缺少环境变量 $name（见 fpk-server/app/module.md 的启动说明）")
