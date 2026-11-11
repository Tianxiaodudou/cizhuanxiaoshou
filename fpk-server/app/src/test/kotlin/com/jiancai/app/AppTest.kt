/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   49 行  类 AppTest
 * 第   54 行  函数 closeApps
 * 第   58 行  函数 newApp
 * 第   74 行  函数 migrations_cover_every_phase_one_module
 * 第   87 行  函数 a_fresh_database_without_deployment_config_fails_fast
 * 第   94 行  函数 account_created_through_s03_can_login_through_s02
 * 第  112 行  函数 duplicate_username_is_translated_to_the_s03_error_code
 * 第  126 行  函数 short_password_is_translated_to_e422
 * 第  138 行  函数 nonexistent_account_maps_to_e6005
 * 第  152 行  函数 the_last_enabled_admin_guard_survives_the_adapter
 * 第  162 行  函数 adapter_participates_in_the_callers_transaction
 * 第  179 行  函数 queryString
 * 第  182 行  函数 queryString
 * 第  188 行  对象 ?
 */

package com.jiancai.app

import com.jiancai.account.AccountAdminException
import com.jiancai.auth.AuthException
import com.jiancai.auth.Pbkdf2PasswordHasher
import com.jiancai.config.ConfigException
import com.jiancai.config.DeploymentConfig
import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.serial.SerialType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 组合根联调用例：真实 SQLite（内存）+ 真实 S02/S03 实现，证明
 * 「S03 AccountStore ← 适配器 ← S02 AccountStorePort」在运行期确实接上了。
 */
class AppTest {

    private val apps = mutableListOf<App>()

    @AfterTest
    fun closeApps() {
        apps.forEach { runCatching { it.close() } }
    }

    private fun newApp(withDeploymentConfig: Boolean = true): App {
        val app = App.create(
            db = DbCore.testDatabase(App.migrations),
            deploymentConfig = if (withDeploymentConfig) DeploymentConfig.of("宁波建材") else null,
            secret = SECRET,
            clock = CLOCK,
            // 低成本哈希：联调用例不校验默认成本（那由 S02 的 PasswordHasherTest 守）。
            hasher = Pbkdf2PasswordHasher(iterations = 1_000),
        )
        apps += app
        return app
    }

    // —— 迁移与装配 ——

    @Test
    fun migrations_cover_every_phase_one_module() {
        val app = newApp()

        assertEquals(210, app.db.migrator.currentVersion(), "阶段一最高迁移版本应为 auth 的 210")
        assertEquals(emptyList(), app.db.migrator.pendingVersions(), "打开后不应还有待应用迁移")

        // serial / config / auth 三张表都由组合根合并后建好：各取一个 Port 调用即证明装配成功。
        assertEquals("PS202611110001", app.serial.next(SerialType.PURCHASE))
        assertEquals("宁波建材", app.config.getMerchantName())
        assertEquals(emptyList(), app.accountAdmin.list())
    }

    @Test
    fun a_fresh_database_without_deployment_config_fails_fast() {
        assertFailsWith<ConfigException> { newApp(withDeploymentConfig = false) }
    }

    // —— S03 → 适配器 → S02：账号真的落进 auth_account，密码真的哈希 ——

    @Test
    fun account_created_through_s03_can_login_through_s02() {
        val app = newApp()

        val created = app.accountAdmin.create(
            CreateAccountCmd(username = "boss", password = "boss12345", displayName = "老板", role = Role.ADMIN),
        )
        val login = app.auth.login(LoginCmd(username = "boss", password = "boss12345"))

        assertEquals(created.id, login.account.id)
        assertEquals("boss", login.account.username)
        assertTrue(login.token.isNotEmpty())

        val storedHash = queryString(app, "SELECT password_hash FROM auth_account WHERE id = ?", created.id)
        assertTrue(storedHash!!.startsWith("pbkdf2-"), "库中必须是哈希：$storedHash")
        assertNotEquals("boss12345", storedHash)
    }

    @Test
    fun duplicate_username_is_translated_to_the_s03_error_code() {
        val app = newApp()
        app.bootstrapFirstAdmin(username = "admin", password = "admin12345", displayName = "管理员")

        val failure = assertFailsWith<AccountAdminException> {
            app.accountAdmin.create(
                CreateAccountCmd(username = "ADMIN", password = "admin12345", displayName = "重复", role = Role.STAFF),
            )
        }
        assertEquals(ErrorCode.E6001, failure.code)
        assertTrue(failure.cause is AuthException, "适配器应保留 S02 原始异常作为 cause")
    }

    @Test
    fun short_password_is_translated_to_e422() {
        val app = newApp()

        val failure = assertFailsWith<AccountAdminException> {
            app.accountAdmin.create(
                CreateAccountCmd(username = "staff", password = "short", displayName = "店员", role = Role.STAFF),
            )
        }
        assertEquals(ErrorCode.E422, failure.code)
    }

    @Test
    fun nonexistent_account_maps_to_e6005() {
        val app = newApp()

        assertEquals(ErrorCode.E6005, assertFailsWith<AccountAdminException> { app.accountAdmin.get(999) }.code)
        assertEquals(
            ErrorCode.E6005,
            assertFailsWith<AccountAdminException> {
                app.accountAdmin.update(UpdateAccountCmd(999, "改名", Role.STAFF, enabled = true))
            }.code,
        )
        assertEquals(ErrorCode.E6005, assertFailsWith<AccountAdminException> { app.accountAdmin.delete(999) }.code)
    }

    @Test
    fun the_last_enabled_admin_guard_survives_the_adapter() {
        val app = newApp()
        val admin = app.bootstrapFirstAdmin(username = "admin", password = "admin12345", displayName = "管理员")

        assertEquals(ErrorCode.E6003, assertFailsWith<AccountAdminException> { app.accountAdmin.delete(admin.id) }.code)
    }

    // —— 事务口径：适配器不自开事务，回滚由调用方掌控 ——

    @Test
    fun adapter_participates_in_the_callers_transaction() {
        val app = newApp()
        val adapter = AccountStoreAdapter(app.accounts)

        assertFailsWith<IllegalStateException> {
            app.db.txRunner.run { tx ->
                adapter.create(
                    tx,
                    CreateAccountCmd(username = "rolled-back", password = "staff12345", displayName = "回滚", role = Role.STAFF),
                )
                error("调用方回滚，账号不应留下")
            }
        }

        assertEquals(emptyList(), app.accountAdmin.list(), "回滚后不应存在任何账号")
    }

    private fun queryString(app: App, sql: String, vararg args: Any?): String? =
        app.db.txRunner.run { tx -> queryString(tx, sql, *args) }

    private fun queryString(tx: TxContext, sql: String, vararg args: Any?): String? =
        tx.connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private companion object {
        /** 固定密钥（32 字节，只用于测试）。 */
        val SECRET: ByteArray = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)

        /** 固定时钟：业务日期 2026-11-11（+08:00），单号断言才有确定值。 */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
    }
}
