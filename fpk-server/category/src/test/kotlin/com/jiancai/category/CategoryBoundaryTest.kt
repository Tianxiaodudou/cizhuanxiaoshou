/*
 * @file fpk-server/category/src/test/kotlin/com/jiancai/category/CategoryBoundaryTest.kt
 * @role 硬边界（S05）的结构化守卫
 * @exports CategoryBoundaryTest
 * @depends db-core, shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.category

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 硬边界（S05）的结构化守卫：
 * 1. **禁止模块内自判权限**：Port 的任何参数都不是调用者身份
 *    （没有 token、没有操作者 [CategoryDto]、没有 `Role`），权限判断只可能在 S13；
 * 2. **返回 DTO**：所有出参只能是 [CategoryDto] / `List<CategoryDto>` / Unit，不出现持久化实体。
 *
 * 「普通用户被拒」的端到端用例属于 S13 api-gateway（当前仓库还没有该模块）：
 * 本模块只保证「签名层面拿不到身份、也没有任何权限分支」这一前置条件。
 */
class CategoryBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        java.lang.Boolean.TYPE,
        CategoryCmd::class.java,
    )

    private val allowedReturns = setOf(
        CategoryDto::class.java,
        java.util.List::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_category_port_never_receives_the_callers_identity_or_role() {
        CategoryPort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "CategoryPort.${method.name} 不得接收调用者身份 / 角色 / token 参数（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
            }
        }
    }

    @Test
    fun the_category_port_only_returns_dtos() {
        CategoryPort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "CategoryPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }
}
