/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   38 行  类 PermissionTest
 * 第   41 行  函数 the_login_and_contract_endpoints_are_public
 * 第   53 行  函数 a_missing_or_invalid_token_is_rejected_with_e401
 * 第   66 行  函数 a_non_bearer_authorization_header_is_rejected_with_e401
 * 第   80 行  函数 a_staff_account_cannot_reach_an_admin_endpoint
 * 第   91 行  函数 a_staff_account_cannot_create_a_master_data_record
 * 第  101 行  函数 a_staff_account_cannot_change_a_price_and_the_price_port_is_never_called
 * 第  115 行  函数 an_admin_can_reach_the_admin_endpoints
 * 第  123 行  函数 a_staff_account_may_reach_the_business_endpoints
 * 第  134 行  函数 the_gateway_passes_the_authenticated_identity_down_as_a_data_field
 * 第  149 行  函数 the_me_endpoint_returns_the_caller
 * 第  158 行  函数 a_public_route_never_touches_the_token
 * 第  167 行  函数 the_router_exposes_the_permission_level_of_every_route
 */

package com.jiancai.gateway

import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收「权限在 API 层拦截」。
 *
 * 断言口径：
 * - 公开接口不需要 token；
 * - 需要登录的接口缺 token / 坏 token → `E401`；
 * - 管理员接口被普通店员访问 → `E403`，**业务 Port 一次都没被调用**；
 * - 管理员通过 → 200，且业务 Port 只被调用一次（没有重复鉴权副作用）。
 */
class PermissionTest {

    @Test
    fun the_login_and_contract_endpoints_are_public() {
        FakeGateway().use { fixture ->
            assertEquals(200, fixture.gateway.handle(get_("/contract")).status)
            val login = fixture.gateway.handle(
                post_("/auth/login", """{"username":"admin","password":"secret"}"""),
            )
            assertEquals(200, login.status)
            assertEquals("token-admin", login.decode(LoginResultDto.serializer()).token)
        }
    }

    @Test
    fun a_missing_or_invalid_token_is_rejected_with_e401() {
        FakeGateway().use { fixture ->
            val missing = fixture.gateway.handle(get_("/orders/today"))
            assertEquals(401, missing.status)
            assertEquals(ErrorCode.E401, missing.errorCode())

            val invalid = fixture.gateway.handle(get_("/orders/today", token = "not-a-token"))
            assertEquals(401, invalid.status)
            assertEquals(ErrorCode.E401, invalid.errorCode())
        }
    }

    @Test
    fun a_non_bearer_authorization_header_is_rejected_with_e401() {
        FakeGateway().use { fixture ->
            val request = ApiRequest(
                method = HttpMethod.GET,
                path = "/orders/today",
                headers = mapOf("authorization" to "Basic abc"),
            )
            val response = fixture.gateway.handle(request)
            assertEquals(401, response.status)
            assertEquals(ErrorCode.E401, response.errorCode())
        }
    }

    @Test
    fun a_staff_account_cannot_reach_an_admin_endpoint() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(get_("/accounts", token = "token-staff"))

            assertEquals(403, response.status)
            assertEquals(ErrorCode.E403, response.errorCode())
            assertEquals(0, fixture.world.authVerifyCount - 1, "鉴权只应发生一次（不重复验 token）")
        }
    }

    @Test
    fun a_staff_account_cannot_create_a_master_data_record() {
        FakeGateway().use { fixture ->
            val body = """{"category_name":"水泥","model":"P.O42.5","unit":"bag"}"""
            val response = fixture.gateway.handle(post_("/categories", body, token = "token-staff"))
            assertEquals(403, response.status)
            assertEquals(ErrorCode.E403, response.errorCode())
        }
    }

    @Test
    fun a_staff_account_cannot_change_a_price_and_the_price_port_is_never_called() {
        FakeGateway().use { fixture ->
            val body = """
                {"customer_id":11,"category_id":22,"batch_tag":"A1","new_price":"120.00","reason":"议价"}
            """.trimIndent()
            val response = fixture.gateway.handle(post_("/prices/change", body, token = "token-staff"))

            assertEquals(403, response.status)
            assertEquals(0, fixture.world.priceChangeCount, "权限拒绝时业务 Port 不得被调用")
            assertEquals(0, fixture.world.auditEvents.size, "权限拒绝时不得留痕")
        }
    }

    @Test
    fun an_admin_can_reach_the_admin_endpoints() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(get_("/accounts", token = "token-admin"))
            assertEquals(200, response.status)
        }
    }

    @Test
    fun a_staff_account_may_reach_the_business_endpoints() {
        FakeGateway().use { fixture ->
            assertEquals(200, fixture.gateway.handle(get_("/orders/today", token = "token-staff")).status)
            assertEquals(
                200,
                fixture.gateway.handle(get_("/customers", mapOf("phone" to "13800000000"), token = "token-staff")).status,
            )
        }
    }

    @Test
    fun the_gateway_passes_the_authenticated_identity_down_as_a_data_field() {
        FakeGateway().use { fixture ->
            val body = """
                {"client_request_id":"r-1","order_type":"purchase","customer_id":11,
                 "lines":[{"category_id":22,"batch_tag":"A1","unit":"bag","quantity":"2"}]}
            """.trimIndent()
            val response = fixture.gateway.handle(
                post_("/orders", body, token = "token-staff", idempotencyKey = "k-1"),
            )
            assertEquals(201, response.status)
            assertEquals(2, fixture.world.orders.values.single().createdBy, "created_by 必须是已认证账号 id")
        }
    }

    @Test
    fun the_me_endpoint_returns_the_caller() {
        FakeGateway().use { fixture ->
            val response = fixture.gateway.handle(get_("/me", token = "token-admin"))
            assertEquals(200, response.status)
            assertEquals(Role.ADMIN, response.decode(com.jiancai.shared.dto.AccountDto.serializer()).role)
        }
    }

    @Test
    fun a_public_route_never_touches_the_token() {
        FakeGateway().use { fixture ->
            fixture.gateway.handle(get_("/contract"))
            assertEquals(0, fixture.world.authVerifyCount)
            assertNull(fixture.gateway.routes().first { it.template == "/contract" }.let { null })
        }
    }

    @Test
    fun the_router_exposes_the_permission_level_of_every_route() {
        FakeGateway().use { fixture ->
            val routes = fixture.gateway.routes()
            assertTrue(routes.any { it.template == "/accounts" && it.access == RouteAccess.ADMIN })
            assertTrue(routes.any { it.template == "/auth/login" && it.access == RouteAccess.PUBLIC })
            assertTrue(routes.any { it.template == "/orders" && it.access == RouteAccess.AUTHENTICATED })
            assertTrue(routes.size >= 25, "路由表应覆盖 S03–S12 的全部用例，当前 ${routes.size}")
        }
    }
}
