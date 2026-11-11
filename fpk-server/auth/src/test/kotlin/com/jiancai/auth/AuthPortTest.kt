/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   49 行  类 AuthPortTest
 * 第   54 行  函数 login_returns_a_token_and_the_account_without_the_password
 * 第   80 行  函数 verify_accepts_a_freshly_issued_token_and_returns_the_account
 * 第   95 行  函数 verify_is_stable_across_service_instances_with_the_same_secret
 * 第  109 行  函数 unknown_username_and_wrong_password_share_the_same_error
 * 第  128 行  函数 blank_credentials_are_rejected_with_the_same_login_error
 * 第  143 行  函数 username_is_trimmed_and_case_insensitive
 * 第  153 行  函数 disabled_account_cannot_log_in
 * 第  172 行  函数 verify_rejects_a_tampered_token
 * 第  190 行  函数 verify_rejects_tokens_that_are_not_server_issued
 * 第  206 行  函数 verify_rejects_a_token_signed_with_another_secret
 * 第  223 行  函数 verify_rejects_an_expired_token_but_honours_the_one_issued_before_expiry
 * 第  241 行  函数 verify_rejects_a_token_whose_account_was_deleted
 * 第  257 行  函数 verify_rejects_a_token_once_the_account_is_disabled
 * 第  273 行  函数 token_is_only_accepted_inside_a_fresh_transaction
 * 第  287 行  函数 login_joins_the_callers_transaction
 * 第  323 行  函数 password_is_persisted_only_as_a_pbkdf2_hash
 * 第  339 行  函数 default_hasher_uses_the_documented_cost
 * 第  350 行  函数 database_rejects_a_plaintext_password
 * 第  371 行  函数 bootstrap_only_creates_the_first_admin
 * 第  389 行  函数 weak_or_blank_input_is_rejected_at_account_creation
 */

package com.jiancai.auth

import com.jiancai.db.DbCore
import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import java.sql.SQLException
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验收（S02）：
 * 1. 登录 / 校验通过；
 * 2. 无效 token 被拒（篡改、过期、换密钥、账号删除 / 停用）；
 * 3. 硬边界：明文不落库、token 由服务端签发、客户端不参与鉴权判断。
 */
class AuthPortTest {

    private val password = "admin12345"

    @Test
    fun login_returns_a_token_and_the_account_without_the_password() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")

            val result = auth.login(LoginCmd("admin", password))

            assertTrue(result.token.isNotBlank(), "登录必须签发 token")
            assertEquals(admin.id, result.account.id)
            assertEquals("admin", result.account.username)
            assertEquals("管理员", result.account.displayName)
            assertEquals(Role.ADMIN, result.account.role)
            assertTrue(result.account.enabled)
            assertEquals(CONTRACT_VERSION, result.contractVersion, "登录结果必须带契约版本")
            assertTrue(
                OffsetDateTime.parse(result.expiresAt) > OffsetDateTime.parse(admin.createdAt),
                "过期时间必须晚于账号创建时间（${result.expiresAt}）",
            )
            // 密码是硬边界：不出现在任何出参里。
            assertFalse(result.token.contains(password))
            assertFalse(result.account.toString().contains(password))
            assertFalse(result.toString().contains(password))
        }
    }

    @Test
    fun verify_accepts_a_freshly_issued_token_and_returns_the_account() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            val current = auth.verify(token)

            assertEquals(admin.id, current.id)
            assertEquals(Role.ADMIN, current.role)
            assertTrue(current.enabled)
        }
    }

    @Test
    fun verify_is_stable_across_service_instances_with_the_same_secret() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val token = testService(db).let { auth ->
                auth.bootstrapFirstAdmin("admin", password, "管理员")
                auth.login(LoginCmd("admin", password)).token
            }

            // 模拟服务重启：新实例、同一把密钥、同一张库。
            val restarted = testService(db, clockAt("2026-11-10T03:00:00Z"))
            assertEquals("admin", restarted.verify(token).username)
        }
    }

    @Test
    fun unknown_username_and_wrong_password_share_the_same_error() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")

            val wrongPassword = assertFailsWith<AuthException> {
                auth.login(LoginCmd("admin", "wrongpass123"))
            }
            val unknownUser = assertFailsWith<AuthException> {
                auth.login(LoginCmd("ghost", password))
            }

            assertEquals(ErrorCode.E6002, wrongPassword.code)
            assertEquals(wrongPassword.code, unknownUser.code, "不得按错误码区分账号是否存在")
            assertEquals(wrongPassword.message, unknownUser.message, "文案也必须一致，避免枚举")
        }
    }

    @Test
    fun blank_credentials_are_rejected_with_the_same_login_error() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")

            assertEquals(ErrorCode.E6002, assertFailsWith<AuthException> {
                auth.login(LoginCmd("", password))
            }.code)
            assertEquals(ErrorCode.E6002, assertFailsWith<AuthException> {
                auth.login(LoginCmd("admin", ""))
            }.code)
        }
    }

    @Test
    fun username_is_trimmed_and_case_insensitive() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("Admin", password, "管理员")

            assertEquals("Admin", auth.login(LoginCmd("  ADMIN  ", password)).account.username)
        }
    }

    @Test
    fun disabled_account_cannot_log_in() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")
            db.txRunner.run { tx -> tx.exec("UPDATE auth_account SET enabled = 0 WHERE id = ?", admin.id) }

            assertEquals(
                ErrorCode.E6004,
                assertFailsWith<AuthException> { auth.login(LoginCmd("admin", password)) }.code,
            )
            // 错误密码仍报「用户名或密码错误」：不能借停用状态差异反推密码是否正确。
            assertEquals(
                ErrorCode.E6002,
                assertFailsWith<AuthException> { auth.login(LoginCmd("admin", "wrongpass123")) }.code,
            )
        }
    }

    @Test
    fun verify_rejects_a_tampered_token() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            // 改 payload 段（base64url 的末位可能落在填充位上，改末位不一定改变解码结果）。
            val parts = token.split(".")
            val mutatedPayload = (if (parts[1].first() == 'A') 'B' else 'A') + parts[1].drop(1)
            val tampered = "${parts[0]}.${mutatedPayload}.${parts[2]}"
            assertEquals(
                ErrorCode.E401,
                assertFailsWith<AuthException> { auth.verify(tampered) }.code,
            )
        }
    }

    @Test
    fun verify_rejects_tokens_that_are_not_server_issued() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")

            // 客户端自造 / 空串 / 结构不符，一律 401。
            listOf("", "v1", "v1.abc", "v1.abc.def", "Bearer something", "a.b.c").forEach { candidate ->
                assertEquals(
                    ErrorCode.E401,
                    assertFailsWith<AuthException>("应拒绝：$candidate") { auth.verify(candidate) }.code,
                )
            }
        }
    }

    @Test
    fun verify_rejects_a_token_signed_with_another_secret() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            val foreignVerifier = testService(db, secret = OTHER_SECRET)
            assertEquals(
                ErrorCode.E401,
                assertFailsWith<AuthException> { foreignVerifier.verify(token) }.code,
            )
            // 换密钥前签发的 token 仍由原密钥的实例认可。
            assertEquals("admin", auth.verify(token).username)
        }
    }

    @Test
    fun verify_rejects_an_expired_token_but_honours_the_one_issued_before_expiry() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            // 默认 12 小时有效期：11:59 后仍可通过，12:01 后必须失效。
            testService(db, clockAt("2026-11-10T13:59:00Z")).verify(token)

            val expired = testService(db, clockAt("2026-11-10T14:01:00Z"))
            assertEquals(
                ErrorCode.E401,
                assertFailsWith<AuthException> { expired.verify(token) }.code,
            )
        }
    }

    @Test
    fun verify_rejects_a_token_whose_account_was_deleted() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            db.txRunner.run { tx -> tx.exec("DELETE FROM auth_account WHERE id = ?", admin.id) }

            assertEquals(
                ErrorCode.E401,
                assertFailsWith<AuthException> { auth.verify(token) }.code,
            )
        }
    }

    @Test
    fun verify_rejects_a_token_once_the_account_is_disabled() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            db.txRunner.run { tx -> tx.exec("UPDATE auth_account SET enabled = 0 WHERE id = ?", admin.id) }

            assertEquals(
                ErrorCode.E6004,
                assertFailsWith<AuthException> { auth.verify(token) }.code,
            )
        }
    }

    @Test
    fun token_is_only_accepted_inside_a_fresh_transaction() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            auth.bootstrapFirstAdmin("admin", password, "管理员")
            val token = auth.login(LoginCmd("admin", password)).token

            // 校验是只读操作，但同一事务里调用也必须可用（S13 组合多个读取时）。
            db.txRunner.run { tx ->
                assertEquals("admin", auth.verify(tx, token).username)
            }
        }
    }

    @Test
    fun login_joins_the_callers_transaction() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")
            assertTrue(
                db.txRunner.run { tx ->
                    tx.queryString("SELECT last_login_at FROM auth_account WHERE id = ?", admin.id)
                } == null,
                "预置账号不应有登录时间",
            )

            assertFailsWith<IllegalStateException> {
                db.txRunner.run { tx ->
                    auth.login(tx, LoginCmd("admin", password))
                    error("调用方业务失败")
                }
            }

            assertTrue(
                db.txRunner.run { tx ->
                    tx.queryString("SELECT last_login_at FROM auth_account WHERE id = ?", admin.id)
                } == null,
                "调用方事务回滚时，登录写下的 last_login_at 必须一起回滚（铁律 4）",
            )

            db.txRunner.run { tx -> auth.login(tx, LoginCmd("admin", password)) }
            assertTrue(
                db.txRunner.run { tx ->
                    tx.queryString("SELECT last_login_at FROM auth_account WHERE id = ?", admin.id)
                } != null,
                "事务提交后 last_login_at 必须落库",
            )
        }
    }

    @Test
    fun password_is_persisted_only_as_a_pbkdf2_hash() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val admin = auth.bootstrapFirstAdmin("admin", password, "管理员")

            val stored = db.txRunner.run { tx ->
                tx.queryString("SELECT password_hash FROM auth_account WHERE id = ?", admin.id)
            } ?: error("账号必须落库")

            assertNotEquals(password, stored)
            assertFalse(stored.contains(password), "哈希串里不得出现明文密码")
            assertTrue(stored.startsWith("${Pbkdf2PasswordHasher.PREFIX}\$1000\$"), "哈希串必须自描述算法与成本：$stored")
        }
    }

    @Test
    fun default_hasher_uses_the_documented_cost() {
        val encoded = Pbkdf2PasswordHasher().hash(password)
        val parts = encoded.split("\$")
        assertEquals(4, parts.size, "哈希串格式：算法\$迭代\$盐\$派生密钥")
        assertEquals(Pbkdf2PasswordHasher.PREFIX, parts[0])
        assertEquals(Pbkdf2PasswordHasher.DEFAULT_ITERATIONS.toString(), parts[1])
        assertTrue(Pbkdf2PasswordHasher().verify(password, encoded))
        assertFalse(Pbkdf2PasswordHasher().verify("another12345", encoded))
    }

    @Test
    fun database_rejects_a_plaintext_password() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val failure = assertFailsWith<SQLException> {
                db.txRunner.run { tx ->
                    tx.exec(
                        "INSERT INTO auth_account " +
                            "(username, username_key, display_name, role, enabled, password_hash, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        "plain", "plain", "明文账号", "STAFF", 1, password,
                        "2026-11-10T10:00:00+08:00", "2026-11-10T10:00:00+08:00",
                    )
                }
            }
            assertTrue(
                failure.message.orEmpty().contains("CHECK", ignoreCase = true),
                "库层 CHECK 必须拦住明文密码：${failure.message}",
            )
        }
    }

    @Test
    fun bootstrap_only_creates_the_first_admin() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val first = auth.bootstrapFirstAdmin("admin", password, "管理员")
            assertEquals(Role.ADMIN, first.role)

            assertEquals(
                ErrorCode.E6001,
                assertFailsWith<AuthException> {
                    auth.bootstrapFirstAdmin("second", password, "第二管理员")
                }.code,
            )
            // 引导失败不影响原账号登录。
            assertEquals(first.id, auth.login(LoginCmd("admin", password)).account.id)
        }
    }

    @Test
    fun weak_or_blank_input_is_rejected_at_account_creation() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)

            assertEquals(
                ErrorCode.E422,
                assertFailsWith<AuthException> { auth.bootstrapFirstAdmin("admin", "short", "管理员") }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<AuthException> { auth.bootstrapFirstAdmin("  ", password, "管理员") }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<AuthException> { auth.bootstrapFirstAdmin("admin", password, "  ") }.code,
            )
            // 全部失败后库仍是空的：首次引导仍可正常完成。
            assertEquals(Role.ADMIN, auth.bootstrapFirstAdmin("admin", password, "管理员").role)
        }
    }
}
