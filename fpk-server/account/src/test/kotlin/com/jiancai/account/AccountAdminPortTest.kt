/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   43 行  类 AccountAdminPortTest
 * 第   46 行  函数 admin_can_create_read_list_update_and_delete_an_account
 * 第   71 行  函数 list_is_ordered_by_id_so_the_admin_screen_is_stable
 * 第   84 行  函数 a_duplicate_username_is_rejected_with_e6001
 * 第  100 行  函数 blank_username_or_display_name_is_rejected_with_e422_and_writes_nothing
 * 第  116 行  函数 unknown_account_is_rejected_with_e6005
 * 第  133 行  函数 the_last_enabled_admin_cannot_be_deleted_demoted_or_disabled
 * 第  166 行  函数 renaming_the_last_admin_is_still_allowed
 * 第  181 行  函数 an_admin_can_be_demoted_while_another_enabled_admin_remains
 * 第  196 行  函数 a_disabled_admin_does_not_count_as_an_available_admin
 * 第  211 行  函数 a_staff_account_can_always_be_disabled_or_deleted
 * 第  227 行  函数 a_blank_password_means_keep_the_current_password
 * 第  250 行  函数 every_convenience_overload_opens_exactly_one_transaction
 * 第  268 行  函数 tx_overloads_reuse_the_callers_transaction_and_never_open_their_own
 * 第  294 行  函数 account_dtos_never_carry_the_password
 */

package com.jiancai.account

import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 验收（S03 · 账号 CRUD）：
 * 1. 管理员用例：增 / 改 / 查 / 列表 / 删；
 * 2. 业务不变量：至少保留一个已启用管理员（`E6003`）、重名（`E6001`）、不存在（`E6005`）、空入参（`E422`）；
 * 3. 硬边界：返回 DTO 不含密码、明文只透传给 S02、写操作全部在事务内、不改密码时保留原密码。
 *
 * 「非管理员被 api-gateway 拒绝」不属于本模块的用例（权限唯一拦截点在 S13），见 module.md。
 */
class AccountAdminPortTest {

    @Test
    fun admin_can_create_read_list_update_and_delete_an_account() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)

            val created = service.create(CreateAccountCmd("  zhangsan  ", TEST_PASSWORD, " 张三 ", Role.STAFF))

            assertEquals("zhangsan", created.username, "用户名首尾空白应被裁掉")
            assertEquals("张三", created.displayName)
            assertEquals(Role.STAFF, created.role)
            assertTrue(created.enabled, "新建账号默认启用")
            assertEquals(TEST_PASSWORD, store.lastCreatedPassword, "明文密码只透传给 S02，本模块不哈希")

            val updated = service.update(UpdateAccountCmd(created.id, "张三丰", Role.STAFF, true))
            assertEquals("张三丰", updated.displayName)
            assertEquals("张三丰", service.get(created.id).displayName)
            assertEquals(listOf(created.id), service.list().map { it.id })

            service.delete(created.id)
            assertTrue(service.list().isEmpty())
            assertEquals(ErrorCode.E6005, assertFailsWith<AccountAdminException> { service.get(created.id) }.code)
        }
    }

    @Test
    fun list_is_ordered_by_id_so_the_admin_screen_is_stable() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            val first = store.seed("first")
            val second = store.seed("second")
            val third = store.seed("third")

            assertEquals(listOf(first.id, second.id, third.id), service.list().map { it.id })
        }
    }

    @Test
    fun a_duplicate_username_is_rejected_with_e6001() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            store.seed("Admin")

            // 重名由 S02 的唯一约束裁决（大小写不敏感），本模块只负责把 E6001 透出。
            val failure = assertFailsWith<AccountAdminException> {
                service.create(CreateAccountCmd("admin", TEST_PASSWORD, "另一个管理员", Role.ADMIN))
            }
            assertEquals(ErrorCode.E6001, failure.code)
            assertEquals(0, store.createCount, "重名时不得写入")
        }
    }

    @Test
    fun blank_username_or_display_name_is_rejected_with_e422_and_writes_nothing() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)

            assertEquals(ErrorCode.E422, assertFailsWith<AccountAdminException> {
                service.create(CreateAccountCmd("   ", TEST_PASSWORD, "张三", Role.STAFF))
            }.code)
            assertEquals(ErrorCode.E422, assertFailsWith<AccountAdminException> {
                service.create(CreateAccountCmd("zhangsan", TEST_PASSWORD, "  ", Role.STAFF))
            }.code)
            assertEquals(0, store.createCount)
        }
    }

    @Test
    fun unknown_account_is_rejected_with_e6005() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)

            assertEquals(ErrorCode.E6005, assertFailsWith<AccountAdminException> { service.get(42) }.code)
            assertEquals(
                ErrorCode.E6005,
                assertFailsWith<AccountAdminException> {
                    service.update(UpdateAccountCmd(42, "张三", Role.STAFF, true))
                }.code,
            )
            assertEquals(ErrorCode.E6005, assertFailsWith<AccountAdminException> { service.delete(42) }.code)
        }
    }

    @Test
    fun the_last_enabled_admin_cannot_be_deleted_demoted_or_disabled() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            val first = store.seed("admin1")
            val second = store.seed("admin2")

            service.delete(first.id)

            assertEquals(ErrorCode.E6003, assertFailsWith<AccountAdminException> { service.delete(second.id) }.code)
            assertEquals(
                ErrorCode.E6003,
                assertFailsWith<AccountAdminException> {
                    service.update(UpdateAccountCmd(second.id, "管理员", Role.STAFF, true))
                }.code,
                "降级最后一个管理员同样会收窄管理入口",
            )
            assertEquals(
                ErrorCode.E6003,
                assertFailsWith<AccountAdminException> {
                    service.update(UpdateAccountCmd(second.id, "管理员", Role.ADMIN, false))
                }.code,
                "停用最后一个管理员同样会收窄管理入口",
            )
            assertEquals(ErrorCode.E6003, assertFailsWith<AccountAdminException> { service.delete(second.id) }.code)

            // 被拒之后账号仍然可用，且没被改动。
            assertTrue(service.get(second.id).enabled)
            assertEquals(0, store.updateCount)
        }
    }

    @Test
    fun renaming_the_last_admin_is_still_allowed() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            val only = store.seed("admin")

            val renamed = service.update(UpdateAccountCmd(only.id, "老板", Role.ADMIN, true))

            assertEquals("老板", renamed.displayName)
            assertEquals(Role.ADMIN, renamed.role)
            assertTrue(renamed.enabled)
        }
    }

    @Test
    fun an_admin_can_be_demoted_while_another_enabled_admin_remains() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            val first = store.seed("admin1")
            val second = store.seed("admin2")

            val demoted = service.update(UpdateAccountCmd(first.id, "管理员一", Role.STAFF, true))

            assertEquals(Role.STAFF, demoted.role)
            assertEquals(Role.ADMIN, service.get(second.id).role)
        }
    }

    @Test
    fun a_disabled_admin_does_not_count_as_an_available_admin() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            val active = store.seed("active")
            val disabled = store.seed("disabled", enabled = false)

            // 停用的管理员本来就不能登录，不算可用管理入口。
            assertEquals(ErrorCode.E6003, assertFailsWith<AccountAdminException> { service.delete(active.id) }.code)
            service.delete(disabled.id)
            assertEquals(listOf(active.id), service.list().map { it.id })
        }
    }

    @Test
    fun a_staff_account_can_always_be_disabled_or_deleted() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            store.seed("admin")
            val staff = store.seed("staff", role = Role.STAFF)

            val disabled = service.update(UpdateAccountCmd(staff.id, "店员", Role.STAFF, false))
            assertFalse(disabled.enabled)

            service.delete(staff.id)
            assertEquals(listOf("admin"), service.list().map { it.username })
        }
    }

    @Test
    fun a_blank_password_means_keep_the_current_password() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)
            store.seed("admin")
            val staff = store.seed("staff", role = Role.STAFF)

            assertEquals(TEST_PASSWORD, store.storedPassword(staff.id))

            service.update(UpdateAccountCmd(staff.id, "店员", Role.STAFF, true, password = null))
            assertEquals(TEST_PASSWORD, store.storedPassword(staff.id))
            assertNull(store.lastUpdatedPassword, "null 不应被当成新密码透传")

            service.update(UpdateAccountCmd(staff.id, "店员", Role.STAFF, true, password = "   "))
            assertEquals(TEST_PASSWORD, store.storedPassword(staff.id), "空白密码不得覆盖原密码")
            assertNull(store.lastUpdatedPassword)

            service.update(UpdateAccountCmd(staff.id, "店员", Role.STAFF, true, password = "newpass12345"))
            assertEquals("newpass12345", store.storedPassword(staff.id))
        }
    }

    @Test
    fun every_convenience_overload_opens_exactly_one_transaction() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val runner = CountingTxRunner(db.txRunner)
            val service = AccountAdminService(runner, store)

            val created = service.create(CreateAccountCmd("staff", TEST_PASSWORD, "店员", Role.STAFF))
            assertEquals(1, runner.runs, "create 便捷重载必须自开一个事务")

            service.list()
            service.get(created.id)
            service.update(UpdateAccountCmd(created.id, "店员二", Role.STAFF, true))
            service.delete(created.id)
            assertEquals(5, runner.runs, "每个便捷重载各开一个事务，且没有多开")
        }
    }

    @Test
    fun tx_overloads_reuse_the_callers_transaction_and_never_open_their_own() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val runner = CountingTxRunner(db.txRunner)
            val service = AccountAdminService(runner, store)
            store.seed("admin")

            db.txRunner.run { tx ->
                val created = service.create(tx, CreateAccountCmd("staff", TEST_PASSWORD, "店员", Role.STAFF))
                assertSame(tx, store.seenTx.last(), "create(tx, ...) 必须复用调用方事务")

                service.list(tx)
                assertSame(tx, store.seenTx.last())
                service.get(tx, created.id)
                assertSame(tx, store.seenTx.last())
                service.update(tx, UpdateAccountCmd(created.id, "店员二", Role.STAFF, true))
                assertSame(tx, store.seenTx.last())
                service.delete(tx, created.id)
                assertSame(tx, store.seenTx.last())
            }

            assertEquals(0, runner.runs, "带 tx 的重载不得自行开启事务")
        }
    }

    @Test
    fun account_dtos_never_carry_the_password() {
        testDb().use { db ->
            val store = FakeAccountStore()
            val service = AccountAdminService(db.txRunner, store)

            val created = service.create(CreateAccountCmd("staff", TEST_PASSWORD, "店员", Role.STAFF))
            val updated = service.update(UpdateAccountCmd(created.id, "店员", Role.STAFF, true, "newpass12345"))
            val listed = service.list()

            listOf(created, updated).forEach { dto ->
                assertFalse(dto.toString().contains(TEST_PASSWORD), "出参不得包含密码")
                assertFalse(dto.toString().contains("newpass12345"), "出参不得包含新密码")
            }
            assertFalse(listed.toString().contains(TEST_PASSWORD), "列表出参不得包含密码")
        }
    }
}
