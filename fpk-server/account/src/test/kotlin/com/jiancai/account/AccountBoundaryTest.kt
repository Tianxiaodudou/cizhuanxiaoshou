/*
 * @file fpk-server/account/src/test/kotlin/com/jiancai/account/AccountBoundaryTest.kt
 * @role 硬边界（S03）的结构化守卫
 * @exports AccountBoundaryTest
 * @depends db-core, shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.account

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S03）的结构化守卫：
 * 1. **禁止模块内自判权限**：Port 的签名里没有任何「调用者身份」输入
 *    （没有 token、没有操作者 [AccountDto]、没有 `Role`），权限判断只可能在 S13；
 * 2. **返回 DTO**：所有出参只能是 [AccountDto] / `List<AccountDto>` / Unit，不出现持久化实体；
 * 3. **不接收密码回传**：[AccountDto] 没有密码字段（密码只在入参里，由 S02 哈希后落库）。
 */
class AccountBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        CreateAccountCmd::class.java,
        UpdateAccountCmd::class.java,
    )

    private val allowedReturns = setOf(
        AccountDto::class.java,
        java.util.List::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_admin_port_never_receives_the_callers_identity_or_role() {
        AccountAdminPort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "AccountAdminPort.${method.name} 不得接收调用者身份 / 角色 / token 参数（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
            }
        }
    }

    @Test
    fun the_account_store_contract_never_asks_for_permission_inputs() {
        AccountStore::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "AccountStore.${method.name} 只描述数据能力，不得接收身份 / 角色 / token（发现 ${type.name}）",
                )
            }
        }
    }

    @Test
    fun the_admin_port_only_returns_dtos() {
        AccountAdminPort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "AccountAdminPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun the_account_dto_has_no_password_field() {
        val passwordLike = AccountDto::class.java.declaredFields
            .map { it.name }
            .filter { it.contains("password", ignoreCase = true) }

        assertTrue(passwordLike.isEmpty(), "AccountDto 不得包含密码相关字段：$passwordLike")
        assertFalse(
            AccountDto::class.java.declaredFields.any { it.name.contains("hash", ignoreCase = true) },
            "AccountDto 不得包含密码哈希",
        )
    }
}
