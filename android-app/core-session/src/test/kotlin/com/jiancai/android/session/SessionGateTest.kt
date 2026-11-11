/*
 * @file android-app/core-session/src/test/kotlin/com/jiancai/android/session/SessionGateTest.kt
 * @role C01 验收：token 判断仅此一处。
 * @exports SessionGateTest
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.session

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C01 验收：token 判断仅此一处。
 *
 * 除了行为用例，还有一个**跨模块源码扫描**：除 C00（持有 `TokenProvider` 接口）与 C01 外，
 * 任何客户端模块出现 token / Authorization / 登录态判断都算越界。
 */
class SessionGateTest {

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

    private fun success(expiresAt: String = "2099-01-01T00:00:00+08:00") = ApiResult.Success(
        LoginResultDto(token = "token-1", expiresAt = expiresAt, account = account),
    )

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test
    fun a_guest_lands_on_the_login_page() {
        val gate = SessionGate(FakeClient(success()), clock = fixedClock)
        assertIs<SessionState.Anonymous>(gate.state())
        assertEquals(LaunchDestination.LOGIN, gate.destination())
        assertNull(gate.token(), "未登录时不得给出 token")
    }

    @Test
    fun a_successful_login_stores_the_server_token_and_lands_on_home() {
        val gate = SessionGate(FakeClient(success()), clock = fixedClock)
        val outcome = gate.login(" admin ", "secret")

        assertTrue(outcome.success)
        assertEquals(LaunchDestination.HOME, gate.destination())
        assertEquals("token-1", gate.token())
        val state = assertIs<SessionState.Authenticated>(gate.state())
        assertEquals(Role.ADMIN, state.account.role)
    }

    @Test
    fun a_failed_login_keeps_the_user_on_the_login_page_with_the_server_message() {
        val failure = ApiResult.Failure(
            wireCode = "E6002",
            code = ErrorCode.E6002,
            message = "用户名或密码错误",
            httpStatus = 401,
        )
        val gate = SessionGate(FakeClient(failure), clock = fixedClock)
        val outcome = gate.login("admin", "wrong")

        assertFalse(outcome.success)
        assertEquals(LaunchDestination.LOGIN, outcome.destination)
        assertEquals("用户名或密码错误", outcome.failure?.message)
        assertNull(gate.token())
    }

    @Test
    fun a_blank_username_is_rejected_without_touching_the_network() {
        val client = FakeClient(success())
        val gate = SessionGate(client, clock = fixedClock)
        val outcome = gate.login("   ", "secret")

        assertFalse(outcome.success)
        assertEquals(0, client.calls, "入参不合法时不得发请求")
        assertEquals(ErrorCode.E422, outcome.failure?.code)
    }

    @Test
    fun an_expired_session_is_treated_as_logged_out_and_cleared() {
        val store = InMemorySessionStore()
        val gate = SessionGate(FakeClient(success(expiresAt = "2026-01-01T00:00:00+08:00")), store, fixedClock)
        store.save(
            Session("old-token", account, "2026-01-01T00:00:00+08:00"),
        )

        assertNull(gate.token())
        assertEquals(LaunchDestination.LOGIN, gate.destination())
        assertNull(store.load(), "过期会话必须被清除")
    }

    @Test
    fun a_401_clears_the_session_and_asks_the_caller_to_go_to_login() {
        val store = InMemorySessionStore(Session("t", account, "2099-01-01T00:00:00+08:00"))
        val gate = SessionGate(FakeClient(success()), store, fixedClock)

        val handled = gate.onFailure(
            ApiResult.Failure("E401", ErrorCode.E401, "登录已失效", 401),
        )

        assertTrue(handled)
        assertNull(store.load())
        assertEquals(LaunchDestination.LOGIN, gate.destination())
    }

    @Test
    fun a_non_auth_failure_is_left_to_the_feature() {
        val gate = SessionGate(FakeClient(success()), clock = fixedClock)
        val handled = gate.onFailure(ApiResult.Failure("E422", ErrorCode.E422, "参数错误", 422))

        assertFalse(handled, "非 401 失败不应被登录态吞掉")
    }

    @Test
    fun logout_clears_everything() {
        val store = InMemorySessionStore()
        val gate = SessionGate(FakeClient(success()), store, fixedClock)
        gate.login("admin", "secret")
        gate.logout()

        assertNull(gate.token())
        assertEquals(LaunchDestination.LOGIN, gate.destination())
    }

    @Test
    fun token_judgement_exists_only_in_c00_and_c01() {
        val root = File("../..")
        assertTrue(root.isDirectory, "找不到客户端根目录（测试工作目录应为模块目录）")

        val allowed = setOf("core-network", "core-session")
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.path.contains("/src/") }
            .forEach { file ->
                val module = file.path.substringAfter("android-app/").substringBefore('/')
                if (module in allowed) return@forEach
                val text = file.readText()
                listOf("token", "Token", "Authorization", "LoginResultDto", "expiresAt").forEach { keyword ->
                    if (text.contains(keyword)) violations += "$module/${file.name}: $keyword"
                }
            }

        assertTrue(
            violations.isEmpty(),
            "token 判断只能在 C01（C00 仅持有 TokenProvider 接口）；越界：$violations",
        )
    }
}
