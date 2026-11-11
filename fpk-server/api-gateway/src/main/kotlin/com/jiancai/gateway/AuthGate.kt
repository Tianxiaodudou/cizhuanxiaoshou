/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   30 行  类 AuthGate
 * 第   38 行  函数 authorize
 * 第   52 行  函数 tokenOf
 */

package com.jiancai.gateway

import com.jiancai.auth.AuthPort
import com.jiancai.shared.AUTH_SCHEME_BEARER
import com.jiancai.shared.HEADER_AUTHORIZATION
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.enums.Role
import com.jiancai.shared.errors.ErrorCode

/**
 * 鉴权 + 权限拦截（验收「权限在 API 层拦截」，铁律 7）。
 *
 * 唯一入口：业务 Port 的签名里没有任何身份参数，网关在这里 `AuthPort.verify` 之后
 * 只把**已认证账号 id** 当留痕字段往下传，业务模块不看角色、不看 token。
 *
 * 判定顺序（先认证再授权，避免把「无权限」暴露给未登录者）：
 * 1. 缺 `Authorization` 头 / 不是 `Bearer` 方案 / token 空白 → `E401`；
 * 2. `AuthPort.verify` 失败（过期 / 伪造）→ 保留 S02 的 `E401`；
 * 3. 账号被禁用 → 保留 S02 的 `E6004`；
 * 4. 角色不满足路由要求 → `E403`（`E403` 只能从网关抛出）。
 */
class AuthGate(private val auth: AuthPort) {

    /**
     * 按路由的权限级别认证并授权。
     *
     * @return 已认证账号；[RouteAccess.PUBLIC] 返回 null（**不解析 token**，登录接口不校验旧 token）
     * @throws GatewayException `E401` / `E403`
     */
    fun authorize(access: RouteAccess, request: ApiRequest): AccountDto? {
        if (access == RouteAccess.PUBLIC) return null
        val account = auth.verify(tokenOf(request))
        if (access == RouteAccess.ADMIN && account.role != Role.ADMIN) {
            throw GatewayException(
                code = ErrorCode.E403,
                message = "需要管理员权限",
                details = mapOf("required_role" to Role.ADMIN.name),
            )
        }
        return account
    }

    /** 从请求头取 Bearer token；任何不合规形态都是 `E401`（不区分原因，避免探测）。 */
    fun tokenOf(request: ApiRequest): String {
        val raw = request.header(HEADER_AUTHORIZATION)
            ?: throw GatewayException(ErrorCode.E401, "缺少登录凭证")
        if (!raw.startsWith(AUTH_SCHEME_BEARER)) {
            throw GatewayException(ErrorCode.E401, "登录凭证格式错误")
        }
        val token = raw.removePrefix(AUTH_SCHEME_BEARER).trim()
        if (token.isEmpty()) throw GatewayException(ErrorCode.E401, "登录凭证为空")
        return token
    }
}
