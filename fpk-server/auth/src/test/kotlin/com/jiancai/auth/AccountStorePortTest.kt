/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   41 行  类 AccountStorePortTest
 * 第   45 行  函数 staffCmd
 * 第   49 行  函数 create_list_and_get_round_trip
 * 第   72 行  函数 create_rejects_duplicate_usernames_case_insensitively
 * 第   87 行  函数 create_rejects_invalid_input_with_validation_error
 * 第  109 行  函数 update_without_password_keeps_the_old_password
 * 第  127 行  函数 update_with_a_new_password_rotates_it
 * 第  148 行  函数 update_rejects_a_too_short_new_password_and_keeps_the_old_one
 * 第  165 行  函数 disabled_account_can_no_longer_log_in_and_its_token_is_rejected
 * 第  188 行  函数 delete_removes_the_account_and_revokes_its_token
 * 第  204 行  函数 update_and_delete_missing_accounts_report_absence_without_throwing
 * 第  218 行  函数 created_password_is_persisted_only_as_a_hash
 * 第  235 行  函数 writes_join_the_callers_transaction_and_roll_back_together
 */

package com.jiancai.auth

import com.jiancai.db.DbCore
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AccountStorePort` 验收：S03 account 的账号 CRUD 依赖本 Port。
 *
 * 覆盖：增删改查、唯一性（大小写不敏感）、入参校验、改密与「password 为 null 不改密」、
 * 停用 / 删除后的登录与 token 失效、密码只存哈希、以及「不自开事务」（铁律 4）。
 */
class AccountStorePortTest {

    private val password = "password123"

    private fun staffCmd(username: String = "staff") =
        CreateAccountCmd(username = username, password = password, displayName = "店员", role = Role.STAFF)

    @Test
    fun create_list_and_get_round_trip() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)

            val admin = db.txRunner.run { tx -> auth.create(tx, CreateAccountCmd("Admin", password, "管理员", Role.ADMIN)) }
            val staff = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }

            assertTrue(admin.id < staff.id, "id 必须自增")
            assertEquals(Role.ADMIN, admin.role)
            assertEquals(Role.STAFF, staff.role)
            assertTrue(admin.enabled, "新账号默认启用")

            assertEquals(
                listOf("Admin", "staff"),
                db.txRunner.run { tx -> auth.list(tx) }.map { it.username },
                "list 必须按 id 升序",
            )
            assertEquals("店员", db.txRunner.run { tx -> auth.get(tx, staff.id) }?.displayName)
            assertNull(db.txRunner.run { tx -> auth.get(tx, 9_999L) }, "不存在的账号返回 null")
        }
    }

    @Test
    fun create_rejects_duplicate_usernames_case_insensitively() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            db.txRunner.run { tx -> auth.create(tx, staffCmd("Staff")) }

            val duplicate = assertFailsWith<AuthException> {
                db.txRunner.run { tx -> auth.create(tx, staffCmd("  staff  ")) }
            }

            assertEquals(ErrorCode.E6001, duplicate.code, "重名必须由 S02 的唯一键裁决")
            assertEquals(1L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM auth_account") })
        }
    }

    @Test
    fun create_rejects_invalid_input_with_validation_error() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)

            val invalid = listOf(
                CreateAccountCmd("  ", password, "店员", Role.STAFF),
                CreateAccountCmd("staff", password, "   ", Role.STAFF),
                CreateAccountCmd("staff", "short", "店员", Role.STAFF),
            )
            invalid.forEach { cmd ->
                assertEquals(
                    ErrorCode.E422,
                    assertFailsWith<AuthException>("应拒绝不合法的账号入参") {
                        db.txRunner.run { tx -> auth.create(tx, cmd) }
                    }.code,
                )
            }
            assertEquals(0L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM auth_account") })
        }
    }

    @Test
    fun update_without_password_keeps_the_old_password() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val created = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }

            val updated = assertNotNull(
                db.txRunner.run { tx ->
                    auth.update(tx, UpdateAccountCmd(created.id, "新店员", Role.STAFF, enabled = true, password = null))
                },
            )

            assertEquals("新店员", updated.displayName)
            // 旧密码仍可登录：password 为 null 表示不改密。
            assertEquals(created.id, auth.login(LoginCmd("staff", password)).account.id)
        }
    }

    @Test
    fun update_with_a_new_password_rotates_it() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val created = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }

            val updated = assertNotNull(
                db.txRunner.run { tx ->
                    auth.update(tx, UpdateAccountCmd(created.id, "店员", Role.STAFF, enabled = true, password = "newpassword123"))
                },
            )

            assertEquals(
                ErrorCode.E6002,
                assertFailsWith<AuthException> { auth.login(LoginCmd("staff", password)) }.code,
                "改密后旧密码必须失效",
            )
            assertTrue(auth.login(LoginCmd("staff", "newpassword123")).token.isNotBlank())
        }
    }

    @Test
    fun update_rejects_a_too_short_new_password_and_keeps_the_old_one() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val created = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }

            val failure = assertFailsWith<AuthException> {
                db.txRunner.run { tx ->
                    auth.update(tx, UpdateAccountCmd(created.id, "店员", Role.STAFF, enabled = true, password = "short"))
                }
            }

            assertEquals(ErrorCode.E422, failure.code)
            assertEquals(created.id, auth.login(LoginCmd("staff", password)).account.id, "校验失败不得改动密码")
        }
    }

    @Test
    fun disabled_account_can_no_longer_log_in_and_its_token_is_rejected() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val created = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }
            val token = auth.login(LoginCmd("staff", password)).token

            db.txRunner.run { tx ->
                auth.update(tx, UpdateAccountCmd(created.id, "店员", Role.STAFF, enabled = false, password = null))
            }

            assertEquals(
                ErrorCode.E6004,
                assertFailsWith<AuthException> { auth.login(LoginCmd("staff", password)) }.code,
            )
            assertEquals(
                ErrorCode.E6004,
                assertFailsWith<AuthException> { auth.verify(token) }.code,
                "停用立即生效：旧 token 也不能再用",
            )
        }
    }

    @Test
    fun delete_removes_the_account_and_revokes_its_token() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val created = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }
            val token = auth.login(LoginCmd("staff", password)).token

            assertTrue(db.txRunner.run { tx -> auth.delete(tx, created.id) })
            assertNull(db.txRunner.run { tx -> auth.get(tx, created.id) })
            assertEquals(
                ErrorCode.E401,
                assertFailsWith<AuthException> { auth.verify(token) }.code,
            )
        }
    }

    @Test
    fun update_and_delete_missing_accounts_report_absence_without_throwing() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)

            assertNull(
                db.txRunner.run { tx ->
                    auth.update(tx, UpdateAccountCmd(9_999L, "不存在", Role.STAFF, enabled = true, password = null))
                },
            )
            assertFalse(db.txRunner.run { tx -> auth.delete(tx, 9_999L) })
        }
    }

    @Test
    fun created_password_is_persisted_only_as_a_hash() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)
            val created = db.txRunner.run { tx -> auth.create(tx, staffCmd()) }

            val stored = db.txRunner.run { tx ->
                tx.queryString("SELECT password_hash FROM auth_account WHERE id = ?", created.id)
            }.orEmpty()

            assertFalse(stored == password)
            assertFalse(stored.contains(password), "哈希串里不得出现明文密码")
            assertTrue(stored.startsWith("${Pbkdf2PasswordHasher.PREFIX}\$"), "必须自描述算法：$stored")
            assertFalse(created.toString().contains(password), "DTO 不得包含密码")
        }
    }

    @Test
    fun writes_join_the_callers_transaction_and_roll_back_together() {
        DbCore.testDatabase(AuthMigrations.all).use { db ->
            val auth = testService(db)

            assertFailsWith<IllegalStateException> {
                db.txRunner.run { tx ->
                    auth.create(tx, CreateAccountCmd("ghost", password, "幽灵", Role.STAFF))
                    error("调用方业务失败")
                }
            }

            assertEquals(
                0L,
                db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM auth_account") },
                "调用方回滚时，Port 的写入必须一起回滚（铁律 4）",
            )
        }
    }
}
