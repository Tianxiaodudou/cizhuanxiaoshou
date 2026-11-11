/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   38 行  对象 LoginRoute
 * 第   44 行  函数 screen
 * 第   63 行  函数 saveServerAddress
 * 第   72 行  函数 login
 */

package com.jiancai.android.login

import com.jiancai.android.network.ApiResult
import com.jiancai.android.network.ServerAddress
import com.jiancai.android.network.ServerAddressStore
import com.jiancai.android.session.LaunchDestination
import com.jiancai.android.session.LoginOutcome
import com.jiancai.android.session.SessionGate
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.FieldSpec
import com.jiancai.android.ui.FieldType
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.UiButton
import com.jiancai.shared.errors.ErrorCode

/**
 * C04 登录页。
 *
 * 职责：
 * - 账号 / 密码 + **可改的服务端地址**（门店换服务器时不用重新装 APK）；
 * - 登录动作只调 [SessionGate.login]：本模块**不保存 token**、不解析 401（C01 单点负责）；
 * - 地址非法时就地报错，不发请求（避免把错地址当成「服务端挂了」）。
 *
 * 依赖：C00（地址存储）、C01（登录态）、C02（组件）。
 */
object LoginRoute {

    /** 路由登记（C03 汇总；登录页不需要登录）。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.LOGIN, title = "登录", requiresLogin = false)

    /** 登录页规格：两个输入框 + 可改的服务端地址。 */
    fun screen(serverAddress: String = ServerAddress.DEFAULT): ScreenSpec = ScreenSpec(
        id = entry.id,
        title = "登录",
        fields = listOf(
            FieldSpec("username", "账号", FieldType.TEXT),
            FieldSpec("password", "密码", FieldType.TEXT),
            FieldSpec("server_address", "服务端地址", FieldType.TEXT),
        ),
        buttons = listOf(
            ButtonSpec("login", "登录", ButtonAction.Submit),
            ButtonSpec("default_address", "恢复默认地址", ButtonAction.Refresh),
        ),
    )

    /**
     * 保存服务端地址（登录页上的「可改」入口）。
     *
     * @throws IllegalArgumentException 地址格式非法（界面就地提示，不发请求）
     */
    fun saveServerAddress(addresses: ServerAddressStore, raw: String): ServerAddress =
        addresses.update(raw)

    /**
     * 登录。地址先在本地归一化保存，再去 C01 登录。
     *
     * @return 登录结果（成功 = 应去首页；失败 = 就地回显服务端文案）
     */
    @UiButton(id = "login", label = "登录")
    fun login(
        gate: SessionGate,
        addresses: ServerAddressStore,
        username: String,
        password: String,
        serverAddress: String,
    ): LoginOutcome {
        val saved = runCatching { addresses.update(serverAddress) }.getOrElse { failure ->
            return LoginOutcome(
                destination = LaunchDestination.LOGIN,
                failure = ApiResult.Failure(
                    wireCode = ErrorCode.E422.name,
                    code = ErrorCode.E422,
                    message = failure.message ?: "服务端地址格式非法",
                    httpStatus = 422,
                ),
            )
        }
        // 地址归一化已落库；登录本身不关心地址（C00 从同一个 store 读取）。
        require(saved.base().isNotEmpty())
        return gate.login(username, password)
    }
}
