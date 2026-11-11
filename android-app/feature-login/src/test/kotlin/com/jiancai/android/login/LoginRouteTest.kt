/*
 * @file android-app/feature-login/src/test/kotlin/com/jiancai/android/login/LoginRouteTest.kt
 * @role C04 验收：登录可用；服务端地址可改。
 * @exports LoginRouteTest
 * @depends core-network, core-session, shared
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.login

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.android.network.InMemoryServerAddressStore
import com.jiancai.android.session.LaunchDestination
import com.jiancai.android.session.SessionGate
import com.jiancai.android.session.SessionState
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C04 验收：登录可用；服务端地址可改。
 *
 * 额外守住硬边界：本模块不保存 token、不解析 401（那是 C01 的唯一职责）。
 */
class LoginRouteTest {

    private val account = AccountDto(
        id = 1,
        username = "admin",
        displayName = "管理员",
        role = Role.ADMIN,
        enabled = true,
        createdAt = "2026-11-01T09:00:00+08:00",
    )

    private class FakeClient(private val result: ApiResult<LoginResultDto>) : ApiClient {
        var calls = 0
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            calls += 1
            @Suppress("UNCHECKED_CAST")
            return result as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test
    fun a_login_with_the_edited_server_address_lands_on_home() {
        val client = FakeClient(
            ApiResult.Success(LoginResultDto("t-1", "2099-01-01T00:00:00+08:00", account)),
        )
        val addresses = InMemoryServerAddressStore()
        val gate = SessionGate(client, clock = clock)

        val outcome = LoginRoute.login(gate, addresses, " admin ", "secret", "http://10.0.0.8:8080/")

        assertTrue(outcome.success)
        assertEquals(LaunchDestination.HOME, outcome.destination)
        assertEquals("http://10.0.0.8:8080", addresses.baseUrl(), "服务端地址必须真的被改掉")
        assertEquals(1, client.calls)
        assertIs<SessionState.Authenticated>(gate.state())
    }

    @Test
    fun a_bad_server_address_is_reported_without_calling_the_network() {
        val client = FakeClient(ApiResult.Success(LoginResultDto("t", "2099-01-01T00:00:00+08:00", account)))
        val gate = SessionGate(client, clock = clock)
        val addresses = InMemoryServerAddressStore()

        val outcome = LoginRoute.login(gate, addresses, "admin", "secret", "ftp://wrong")

        assertFalse(outcome.success)
        assertEquals(ErrorCode.E422, outcome.failure?.code)
        assertEquals(0, client.calls, "地址不合法时不得发登录请求")
        assertEquals("http://127.0.0.1:8080", addresses.baseUrl(), "非法地址不得覆盖已保存地址")
    }

    @Test
    fun a_server_side_login_failure_is_shown_as_is() {
        val client = FakeClient(
            ApiResult.Failure("E6002", ErrorCode.E6002, "用户名或密码错误", 401),
        )
        val gate = SessionGate(client, clock = clock)
        val addresses = InMemoryServerAddressStore()

        val outcome = LoginRoute.login(gate, addresses, "admin", "wrong", "http://127.0.0.1:8080")

        assertFalse(outcome.success)
        assertEquals("用户名或密码错误", outcome.failure?.message)
        assertEquals(LaunchDestination.LOGIN, outcome.destination)
    }

    @Test
    fun the_login_screen_exposes_the_editable_server_address() {
        val screen = LoginRoute.screen()
        assertEquals("/login", screen.id)
        assertTrue(screen.fields.any { it.name == "server_address" && !it.readOnly })
        assertTrue(screen.buttons.any { it.id == "login" })
        assertFalse(LoginRoute.entry.requiresLogin, "登录页本身不需要登录")
    }

    @Test
    fun the_feature_never_stores_the_token_itself() {
        val sources = File("src/main/kotlin/com/jiancai/android/login").listFiles()!!
            .filter { it.extension == "kt" }
        assertTrue(sources.isNotEmpty())
        sources.forEach { source ->
            val text = source.readLines()
                .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
                .joinToString("\n")
            assertFalse(text.contains("LoginResultDto"), "${source.name} 不得接触登录凭证（C01 单点）")
            assertFalse(text.contains("Authorization"), "${source.name} 不得自己拼鉴权头")
            assertFalse(text.contains("SessionStore"), "${source.name} 不得直接操作会话存储")
        }
    }
}
