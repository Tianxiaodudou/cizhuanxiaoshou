/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   55 行  类 App
 * 第   78 行  函数 bootstrapFirstAdmin
 * 第   82 行  函数 close
 * 第   84 行  对象 ?
 * 第   96 行  函数 create
 * 第  122 行  函数 open
 */

package com.jiancai.app

import com.jiancai.account.AccountAdminPort
import com.jiancai.account.AccountAdminService
import com.jiancai.auth.AccountStorePort
import com.jiancai.auth.AuthPort
import com.jiancai.auth.AuthService
import com.jiancai.config.ConfigMigrations
import com.jiancai.config.ConfigPort
import com.jiancai.config.ConfigService
import com.jiancai.config.DeploymentConfig
import com.jiancai.db.DbConfig
import com.jiancai.db.DbCore
import com.jiancai.db.Migration
import com.jiancai.serial.SerialMigrations
import com.jiancai.serial.SerialPort
import com.jiancai.serial.SerialService
import com.jiancai.auth.AuthMigrations
import com.jiancai.auth.PasswordHasher
import com.jiancai.auth.Pbkdf2PasswordHasher
import com.jiancai.shared.dto.AccountDto
import java.io.Closeable
import java.time.Clock

/**
 * 组合根：进程启动时把 S00–S07 唯一装配一次，之后只对外暴露 Port。
 *
 * 为什么需要它：S03 account 只声明 `AccountStore`、编译期不依赖 S02；S02 提供 `AccountStorePort`。
 * 两边靠 [AccountStoreAdapter] 接上，而这个适配器**不属于任何业务模块**，
 * 只能放在这里（见 `account/module.md`「与 S02 的分工」与 `auth/module.md` 的组合根示例）。
 *
 * 装配顺序（[create]）：
 * 1. `ConfigService` —— 构造即落库 / 生效（失败即部署配置缺失，启动失败）；
 * 2. `AuthService` —— 账号表唯一 SQL 出口，同时实现 `AuthPort` 与 `AccountStorePort`；
 * 3. `AccountStoreAdapter` + `AccountAdminService` —— S03 用例层拿到账号存储能力；
 * 4. `SerialService` —— 单号生成，注入给 S08。
 *
 * 迁移：各模块 `*Migrations.all` 在此合并（[migrations]），由调用方在打开库后 `migrate()`；
 * 也提供 [open]，一次完成「打开 + 迁移 + 装配」，失败自动关库。
 *
 * 权限不在这里判断（铁律 7）：本类只是把 `verify → ADMIN` 所需的 Port 交给 S13，
 * 不提供任何路由 / 鉴权逻辑。
 */
class App private constructor(
    /** db-core 门面：调用方负责 [close]（[open] / `use` 已托管）。 */
    val db: DbCore,
    /** 部署级配置（商家名称），只读。 */
    val config: ConfigPort,
    /** 鉴权 Port：S13 每请求 `verify`。 */
    val auth: AuthPort,
    /** S02 的账号存储 Port；S03 已由 [accountAdmin] 封装，S13 通常不需要直接用它。 */
    val accounts: AccountStorePort,
    /** 管理员账号 CRUD：S13 必须在 `verify → ADMIN` 通过后才转发（`E403` 在网关拦截）。 */
    val accountAdmin: AccountAdminPort,
    /** 单号生成：注入给 S08。 */
    val serial: SerialPort,
    /** 仅组合根可见的落实例：首次启动引导不属于 `AuthPort`。 */
    private val authService: AuthService,
) : Closeable {

    /**
     * 首次启动引导：只在库中还没有任何账号时创建第一个管理员（不是日常账号 CRUD）。
     *
     * 已有账号时抛 `AuthException E6001`，因此不能作为线上提权后门；
     * 日常账号管理走 [accountAdmin]。
     */
    fun bootstrapFirstAdmin(username: String, password: String, displayName: String): AccountDto =
        authService.bootstrapFirstAdmin(username, password, displayName)

    /** 关闭唯一数据库连接；可重复调用。 */
    override fun close() = db.close()

    companion object {

        /** 阶段一全部迁移（按版本升序：serial 100 → config 200 → auth 210）；阶段二模块在 S13 装配时追加。 */
        val migrations: List<Migration> =
            (SerialMigrations.all + ConfigMigrations.all + AuthMigrations.all).sortedBy { it.version }

        /**
         * 在已打开并迁移完成的库上装配全部 Port。
         *
         * @param deploymentConfig 部署方提供的商家名称；为 null 时回退库里已保存的值，都没有则抛 `ConfigException`
         * @param secret token 签名密钥（≥32 字节），由部署方提供，禁止硬编码
         */
        fun create(
            db: DbCore,
            deploymentConfig: DeploymentConfig?,
            secret: ByteArray,
            clock: Clock = Clock.systemDefaultZone(),
            hasher: PasswordHasher = Pbkdf2PasswordHasher(),
        ): App {
            val configService = ConfigService(db.txRunner, deploymentConfig, clock)
            val authService = AuthService(db.txRunner, secret, clock, hasher = hasher)
            val adapter = AccountStoreAdapter(authService)
            return App(
                db = db,
                config = configService,
                auth = authService,
                accounts = authService,
                accountAdmin = AccountAdminService(db.txRunner, adapter),
                serial = SerialService(db.txRunner, clock),
                authService = authService,
            )
        }

        /**
         * 打开库 → 迁移到最新 → 装配；任一步失败自动关闭连接，不泄漏。
         *
         * @param dbConfig SQLite 连接配置（文件库 / `:memory:`）
         */
        fun open(
            dbConfig: DbConfig,
            deploymentConfig: DeploymentConfig?,
            secret: ByteArray,
            clock: Clock = Clock.systemDefaultZone(),
            hasher: PasswordHasher = Pbkdf2PasswordHasher(),
        ): App {
            val db = DbCore.open(dbConfig, migrations)
            return try {
                db.migrator.migrate()
                create(db, deploymentConfig, secret, clock, hasher)
            } catch (failure: Throwable) {
                runCatching { db.close() }
                throw failure
            }
        }
    }
}
