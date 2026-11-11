/*
 * @file android-app/feature-admin/src/test/kotlin/com/jiancai/android/admin/AdminRouteTest.kt
 * @role C11 验收：4 子页可用；权限由服务端把关。
 * @exports AdminRouteTest
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.admin

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.enums.Role
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C11 验收：4 子页可用；权限由服务端把关。
 *
 * 断言：
 * - 四个子页的屏规格齐全；
 * - 每个动作都走 C00 的接口清单（正确的路径 / 方法）；
 * - **本模块没有任何角色判断**（`Role` 只出现在测试里，不出现于生产代码）；
 * - 越权时服务端 `E403` 原样回显。
 */
class AdminRouteTest {

    private class FakeClient(default: ApiResult<Any>) : ApiClient {
        val requests = mutableListOf<ApiRequestSpec<*>>()
        private val response = default
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            requests += spec
            @Suppress("UNCHECKED_CAST")
            return response as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    @Test
    fun the_four_sub_pages_are_present() {
        val screens = AdminRoute.screens()
        assertEquals(4, screens.size)
        assertEquals(
            listOf("型号库", "价格", "结算审核", "账号"),
            screens.map { it.title },
        )
        assertEquals(4, screens.map { it.id }.distinct().size)
    }

    @Test
    fun the_category_page_reads_and_saves_through_the_api_catalog() {
        val category = CategoryDto(
            id = 22, categoryName = "水泥", model = "P.O42.5", unit = PricingUnit.BAG,
            createdAt = "2026-11-01T09:00:00+08:00",
        )
        val client = FakeClient(ApiResult.Success(category))

        AdminRoute.categories(client)
        AdminRoute.saveCategory(client, CategoryCmd(categoryName = "水泥", model = "P.O42.5", unit = PricingUnit.BAG))
        AdminRoute.saveCategory(
            client,
            CategoryCmd(id = 22, categoryName = "水泥", model = "P.O42.5", unit = PricingUnit.BAG),
        )

        assertEquals(listOf("GET /categories", "POST /categories", "PUT /categories/22"), client.requests.map {
            "${it.method.name} ${it.path}"
        })
    }

    @Test
    fun the_settle_audit_page_forwards_the_admin_decision() {
        val settle = SettleDto(
            settleNo = "JS1", orderNo = "PS1", customerId = 11, customerName = "张三",
            direction = SettleDirection.RECEIPT, amount = Money.ofPlain("100.00"),
            status = SettleStatus.APPROVED, submittedAt = "2026-11-11T09:00:00+08:00",
            submittedBy = 2, auditedAt = "2026-11-11T09:30:00+08:00", auditedBy = 1,
        )
        val client = FakeClient(ApiResult.Success(settle))

        AdminRoute.auditSettlement(client, AuditSettleCmd("JS1", approved = true, remark = "核对无误"))

        assertEquals("/settlements/JS1/audit", client.requests.single().path)
        assertTrue(client.requests.single().bodyJson!!.contains("\"approved\":true"))
    }

    @Test
    fun an_over_permission_rejection_is_echoed_from_the_server() {
        val client = FakeClient(ApiResult.Failure("E403", ErrorCode.E403, "需要管理员权限", 403))
        val result = AdminRoute.accounts(client)

        val failure = assertIs<ApiResult.Failure>(result)
        assertEquals(ErrorCode.E403, failure.code)
        assertEquals("需要管理员权限", failure.message)
    }

    @Test
    fun the_admin_module_never_decides_permissions_itself() {
        val sources = File("src/main/kotlin/com/jiancai/android/admin").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        assertFalse(text.contains("Role.ADMIN"), "客户端不得自行判断角色（权限只在 S13）")
        assertFalse(text.contains("Role."), "客户端不得引用角色枚举")
        assertFalse(text.contains("isAdmin"), "不得有前端管理员分支")
        assertEquals(Role.ADMIN, Role.ADMIN, "角色枚举仍然只由服务端解释")
    }

    @Test
    fun the_account_page_never_touches_a_password_field() {
        val sources = File("src/main/kotlin/com/jiancai/android/admin").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }
        assertFalse(text.contains("passwordHash"), "客户端不接触密码哈希")
        assertFalse(
            AccountDto::class.java.declaredFields.any { it.name.contains("password", ignoreCase = true) },
            "AccountDto 不得含密码字段（密码永不下发）",
        )
    }
}
