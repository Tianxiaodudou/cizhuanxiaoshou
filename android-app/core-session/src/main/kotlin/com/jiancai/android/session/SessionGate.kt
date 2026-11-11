/*
 * @file android-app/core-session/src/main/kotlin/com/jiancai/android/session/SessionGate.kt
 * @role 登录态与导航守卫：**全客户端唯一的 token 判断点**（验收「token 判断仅此一处」）。
 * @exports SessionState, LaunchDestination, Session, SessionStore, InMemorySessionStore, LoginOutcome, SessionGate
 * @depends core-network, shared
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/data/AppContainer.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/navigation/AppNav.kt, android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.session

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.android.network.TokenProvider
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.errors.ErrorCode
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 登录态（C01 内部模型，不跨端）。
 */
sealed interface SessionState {

    /** 未登录（或已过期 / 已被服务端判为失效）。 */
    data object Anonymous : SessionState

    /** 已登录：账号信息与过期时间都来自服务端登录响应，客户端不自行推算。 */
    data class Authenticated(val account: AccountDto, val expiresAt: String) : SessionState
}

/**
 * 启动落地页：**只有 C01 能决定**，C03 路由表照此跳转。
 */
enum class LaunchDestination { LOGIN, HOME }

/**
 * 已登录会话（token 只存在这里，不进任何业务 DTO）。
 */
data class Session(
    val token: String,
    val account: AccountDto,
    val expiresAt: String,
)

/**
 * 会话存储：接口在这里，Android 侧用 DataStore / SharedPreferences 实现（本机用内存实现跑单测）。
 *
 * 硬边界：token 只能存在这里（以及 C00 的内存请求头里），不得写进业务对象、不得打日志。
 */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

/** 进程内实现（测试 / 预览；重启即丢，真实 Android 必须换持久化实现）。 */
class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    override fun load(): Session? = session

    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

/**
 * 一次登录尝试的结果（登录页据此就地回显，不做二次判断）。
 *
 * @property failure 失败时的服务端错误（成功为 null）
 */
data class LoginOutcome(
    val destination: LaunchDestination,
    val failure: ApiResult.Failure? = null,
) {
    /** 是否登录成功（唯一判据：落到了 HOME）。 */
    val success: Boolean get() = destination == LaunchDestination.HOME
}

/**
 * 登录态与导航守卫：**全客户端唯一的 token 判断点**（验收「token 判断仅此一处」）。
 *
 * 职责：
 * - 实现 [TokenProvider]，把当前 token 交给 C00 自动注入（C00 不判断登录态）；
 * - 登录成功才落 token；过期 / 401 / 主动退出都走 [logout] 清除；
 * - [destination] 统一回答「该去登录页还是首页」，C03 不自己看 token；
 * - 业务模块只 `client.call(...)`，遇到 401 交给 [onUnauthorized] 处理。
 *
 * @param client C00 客户端（登录接口唯一调用点）
 * @param store 会话存储
 * @param clock 本地时钟；**只用于判断服务端给的过期时间是否已过**（不参与任何业务日期）
 */
class SessionGate(
    private val client: ApiClient,
    private val store: SessionStore = InMemorySessionStore(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : TokenProvider {

    /** 当前 token；过期即视为未登录（并顺手清掉，避免把过期 token 发给服务端）。 */
    override fun token(): String? {
        val session = store.load() ?: return null
        if (isExpired(session)) {
            store.clear()
            return null
        }
        return session.token
    }

    /** 当前登录态（不含 token，供界面展示账号名 / 角色）。 */
    fun state(): SessionState {
        val session = store.load() ?: return SessionState.Anonymous
        if (isExpired(session)) {
            store.clear()
            return SessionState.Anonymous
        }
        return SessionState.Authenticated(session.account, session.expiresAt)
    }

    /** 启动 / 回到前台时应落在哪一页。 */
    fun destination(): LaunchDestination =
        if (state() is SessionState.Authenticated) LaunchDestination.HOME else LaunchDestination.LOGIN

    /**
     * 登录：调一次 C00，成功才保存会话。
     *
     * 失败不做任何本地清理（可能本来就未登录），错误文案直接取服务端。
     */
    fun login(username: String, password: String): LoginOutcome {
        val normalizedName = username.trim()
        if (normalizedName.isEmpty() || password.isEmpty()) {
            return LoginOutcome(
                destination = LaunchDestination.LOGIN,
                failure = ApiResult.Failure(
                    wireCode = ErrorCode.E422.name,
                    code = ErrorCode.E422,
                    message = "请输入账号和密码",
                    httpStatus = 422,
                ),
            )
        }
        return when (val result = client.call(ApiPaths.login(LoginCmd(normalizedName, password)))) {
            is ApiResult.Success -> {
                val login = result.value
                store.save(
                    Session(
                        token = login.token,
                        account = login.account,
                        expiresAt = login.expiresAt,
                    ),
                )
                LoginOutcome(destination = LaunchDestination.HOME)
            }

            is ApiResult.Failure -> LoginOutcome(destination = LaunchDestination.LOGIN, failure = result)
        }
    }

    /** 主动退出（本地清除；token 是服务端签名凭证，无服务端注销接口）。 */
    fun logout() {
        store.clear()
    }

    /**
     * 任何业务调用返回 401 时调用：清会话并返回「需要回登录页」。
     *
     * 这是把「登录失效」收敛到一处的入口：业务模块不解析 401，只把失败结果交给这里。
     *
     * @return true = 已登出（调用方应导航到登录页）；false = 不是登录失效，原样处理
     */
    fun onFailure(failure: ApiResult.Failure): Boolean {
        if (!failure.isUnauthorized) return false
        store.clear()
        return true
    }

    private fun isExpired(session: Session): Boolean {
        val expiresAt = runCatching {
            OffsetDateTime.parse(session.expiresAt, DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP))
        }.getOrNull() ?: return true
        return !expiresAt.toInstant().isAfter(clock.instant())
    }
}
