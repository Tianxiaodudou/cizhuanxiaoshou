/*
 * @file fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/Route.kt
 * @role 一条路由（方法 + 路径模板 + 权限 + 幂等要求 + handler）。
 * @exports RouteAccess, Route, RouteContext, Router
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ApiGateway.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/AuthGate.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayRoutes.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.ContractJson
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.errors.ErrorCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * 路由的权限级别（铁律 7：权限**只**在这里判断，业务模块不各自判断）。
 *
 * - [PUBLIC]：无需 token（登录、契约握手）；
 * - [AUTHENTICATED]：必须带有效 token，任何角色都可以；
 * - [ADMIN]：必须带有效 token 且角色为 [com.jiancai.shared.enums.Role.ADMIN]。
 */
enum class RouteAccess { PUBLIC, AUTHENTICATED, ADMIN }

/**
 * 一条路由（方法 + 路径模板 + 权限 + 幂等要求 + handler）。
 *
 * 路径模板用 `{name}` 声明路径参数，例如 `/orders/{orderNo}/receivable`；
 * 模板**不含正则**，避免把校验规则藏进路由表（入参校验仍在各业务模块）。
 *
 * @property method 请求方法
 * @property template 路径模板（必须以 `/` 开头）
 * @property access 权限级别
 * @property idempotent 是否要求幂等键（写类接口必须具备重放保护）
 * @property handler 处理器：只调 Port / 编码 DTO，不做业务判定
 */
class Route(
    val method: HttpMethod,
    val template: String,
    val access: RouteAccess,
    val idempotent: Boolean = false,
    val handler: (RouteContext) -> RouteOutcome,
) {
    /** 模板拆成段；`{name}` 表示路径参数。 */
    internal val segments: List<String> = template.trim('/').split('/').filter { it.isNotEmpty() }

    init {
        require(template.startsWith("/")) { "路由模板必须以 / 开头：$template" }
        require(template != "/" || segments.isEmpty()) { "根路径固定为 /" }
    }

    /** Spring 风格的调试标签，例如 `GET /orders/{orderNo}`。 */
    val label: String get() = "${method.name} $template"

    /** 按段匹配；不匹配返回 null。 */
    internal fun match(method: HttpMethod, segments: List<String>): Map<String, String>? {
        if (this.method != method || this.segments.size != segments.size) return null
        val params = LinkedHashMap<String, String>()
        this.segments.forEachIndexed { index, expected ->
            val actual = segments[index]
            if (expected.startsWith("{") && expected.endsWith("}")) {
                if (actual.isEmpty()) return null
                params[expected.substring(1, expected.length - 1)] = actual
            } else if (expected != actual) {
                return null
            }
        }
        return params
    }
}

/**
 * 路由上下文：一次已经过权限校验的调用所能看到的一切。
 *
 * 硬边界：**只有网关**能构造它（`internal constructor`），业务模块拿不到这里的东西；
 * [caller] 是网关 `AuthPort.verify` 的结果，handler 只把它的 id 当留痕字段传给业务 Port。
 *
 * @property request 原始请求
 * @property params 路径参数
 * @property caller 已认证账号；[RouteAccess.PUBLIC] 路由为 null
 * @property traceId 本次请求的追踪 id
 */
class RouteContext internal constructor(
    val request: ApiRequest,
    val params: Map<String, String>,
    val caller: AccountDto?,
    val traceId: String,
) {
    /** 取路径参数；缺失属于路由配置错误，直接抛 500。 */
    fun path(name: String): String =
        params[name] ?: throw GatewayException(ErrorCode.E500, "路由缺少路径参数 $name")

    /** 取已认证账号 id；[RouteAccess.PUBLIC] 路由调用它是编程错误。 */
    fun callerId(): Long = caller?.id ?: throw GatewayException(ErrorCode.E500, "缺少已认证账号")

    /** 取查询参数（未提供返回 null）。 */
    fun query(name: String): String? = request.query[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** 必填查询参数。 */
    fun requireQuery(name: String): String =
        query(name) ?: throw GatewayException(ErrorCode.E422, "缺少查询参数 $name")

    /** 取 long 查询参数（缺省返回 null；格式非法 → 422）。 */
    fun queryLong(name: String): Long? {
        val raw = query(name) ?: return null
        return raw.toLongOrNull() ?: throw GatewayException(ErrorCode.E422, "查询参数 $name 必须是整数")
    }

    /** 取 int 查询参数（缺省用 [default]；格式非法 → 422）。 */
    fun queryInt(name: String, default: Int): Int {
        val raw = query(name) ?: return default
        return raw.toIntOrNull() ?: throw GatewayException(ErrorCode.E422, "查询参数 $name 必须是整数")
    }

    /** 取 boolean 查询参数（缺省返回 null；只接受 true / false）。 */
    fun queryBoolean(name: String): Boolean? {
        val raw = query(name) ?: return null
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw GatewayException(ErrorCode.E422, "查询参数 $name 必须是 true / false")
        }
    }

    /** 取枚举查询参数（缺省返回 null；线值非法 → 422）。 */
    inline fun <reified E : Enum<E>> queryEnum(name: String): E? {
        val raw = query(name) ?: return null
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw GatewayException(ErrorCode.E422, "查询参数 $name 不是合法枚举值")
    }

    /** 把请求体解析成 [T]；空体 / 格式错误一律 400（不把解析细节泄漏给客户端）。 */
    fun <T> decode(serializer: KSerializer<T>): T {
        if (request.body.isBlank()) throw GatewayException(ErrorCode.E400, "请求体不能为空")
        return try {
            ContractJson.json.decodeFromString(serializer, request.body)
        } catch (failure: SerializationException) {
            throw GatewayException(
                code = ErrorCode.E400,
                message = "请求体格式错误",
                details = mapOf("reason" to (failure.message?.lineSequence()?.firstOrNull() ?: "无法解析")),
                cause = failure,
            )
        }
    }
}

/** 一次成功的匹配。 */
internal data class MatchedRoute(val route: Route, val params: Map<String, String>)

/**
 * 路由表：模板匹配 + 冲突检测。
 *
 * 冲突（同方法同模板注册两次）在构造时就失败，避免「后注册的悄悄覆盖前一个」。
 */
class Router(routes: List<Route>) {

    private val routes: List<Route> = routes.toList()

    init {
        val duplicates = this.routes.groupBy { it.label }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "路由重复注册：$duplicates" }
    }

    /** 全部路由（只读，供文档 / 自检使用）。 */
    val all: List<Route> get() = routes

    /** 按方法与路径查找路由；找不到返回 null。 */
    internal fun match(method: HttpMethod, path: String): MatchedRoute? {
        val segments = normalize(path)
        for (route in routes) {
            val params = route.match(method, segments) ?: continue
            return MatchedRoute(route, params)
        }
        return null
    }

    /** 路径规范化：去查询串、去尾斜杠、拆段。 */
    internal fun normalize(path: String): List<String> =
        path.substringBefore('?').trim('/').split('/').filter { it.isNotEmpty() }
}
