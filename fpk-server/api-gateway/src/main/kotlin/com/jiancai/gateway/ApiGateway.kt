/*
 * @file fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/ApiGateway.kt
 * @role 网关核心：请求 → 路由 → 鉴权 → 幂等 → handler → 统一响应。
 * @exports ApiGateway
 * @depends shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/HttpServerAdapter.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.gateway

import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.ContractJson
import com.jiancai.shared.HEADER_CONTRACT_VERSION
import com.jiancai.shared.errors.ErrorCode
import kotlinx.serialization.KSerializer
import java.util.UUID

/**
 * 网关核心：请求 → 路由 → 鉴权 → 幂等 → handler → 统一响应。
 *
 * 职责边界（硬边界「禁止实现业务逻辑」）：
 * - **只转发**：handler 内只有「取参数 → 调 Port → 交 DTO」，没有任何价格 / 数量 / 单号计算；
 * - **只拦截**：契约版本、请求体大小、token、角色、幂等键，全部在调用业务 Port 之前判定；
 * - **只映射**：错误码统一由 [ErrorMapping] 产出，网关不写第二套状态码表（铁律 9）；
 * - **不做权限以外的授权判断**：单据归属、跨日锁定、可退数量等业务不变量仍在各业务模块。
 *
 * 线程安全：本类无可变状态（[IdempotencyStore] 自带锁），可被 HTTP 线程池并发调用。
 *
 * @param deps 全部 Port 依赖
 * @param routes 路由表；默认由 [GatewayRoutes.build] 生成（测试可注入精简路由）
 */
class ApiGateway(
    val deps: GatewayDeps,
    routes: List<Route> = GatewayRoutes.build(deps),
) {
    private val router = Router(routes)
    private val authGate = AuthGate(deps.auth)
    private val idempotency = IdempotencyGuard(deps.idempotency)

    /** 全部已注册路由（只读；文档 / 自检用）。 */
    fun routes(): List<Route> = router.all

    /**
     * 处理一次请求。
     *
     * 任何异常都在这里被翻译成统一错误响应，**不会**穿透到 HTTP 适配层：
     * 适配层只负责字节搬运，不参与错误判定。
     */
    fun handle(request: ApiRequest): ApiResponse {
        val traceId = UUID.randomUUID().toString()
        var idempotencyKey: String? = null
        var fingerprint: String? = null
        try {
            val matched = router.match(request.method, request.path)
                ?: throw GatewayException(
                    code = ErrorCode.E404,
                    message = "接口不存在：${request.method.name} ${request.path}",
                )
            checkContractVersion(request)
            if (request.bodyBytes > deps.maxBodyBytes) {
                throw GatewayException(
                    code = ErrorCode.E413,
                    message = "请求体超过 ${deps.maxBodyBytes} 字节上限",
                )
            }
            val caller = authGate.authorize(matched.route.access, request)
            val context = RouteContext(request, matched.params, caller, traceId)

            if (matched.route.idempotent) {
                val key = idempotency.requireKey(request)
                val current = idempotency.fingerprint(request)
                val replayed = idempotency.replay(key, current)
                if (replayed != null) return decorate(replayed, traceId)
                idempotencyKey = key
                fingerprint = current
            }

            val response = render(matched.route.handler(context))
            if (idempotencyKey != null && response.status in 200..299) {
                idempotency.remember(idempotencyKey!!, fingerprint!!, response)
            }
            return decorate(response, traceId)
        } catch (failure: Throwable) {
            idempotencyKey?.let { idempotency.release(it) }
            return decorate(errorResponse(failure, traceId), traceId)
        }
    }

    /** 每个响应都带契约版本与追踪 id（C00 握手 + 排查）。 */
    private fun decorate(response: ApiResponse, traceId: String): ApiResponse =
        response
            .withHeader(HEADER_CONTRACT_VERSION, CONTRACT_VERSION)
            .withHeader(HEADER_TRACE_ID, traceId)

    /** 契约版本检查：只比较主版本号；缺省视为「老客户端」，不强制。 */
    private fun checkContractVersion(request: ApiRequest) {
        val requested = request.header(HEADER_CONTRACT_VERSION)?.trim().orEmpty()
        if (requested.isEmpty()) return
        val serverMajor = CONTRACT_VERSION.substringBefore('.')
        val clientMajor = requested.substringBefore('.')
        if (clientMajor != serverMajor) {
            throw GatewayException(
                code = ErrorCode.E409,
                message = "客户端契约版本不兼容，请升级",
                details = mapOf(
                    "client" to requested,
                    "server" to CONTRACT_VERSION,
                ),
            )
        }
    }

    /** 载荷 → 线上字节（唯一编码点）。 */
    private fun render(outcome: RouteOutcome): ApiResponse = when (val payload = outcome.payload) {
        is Payload.Json<*> -> ApiResponse(
            status = outcome.status,
            body = encodeJson(payload),
            contentType = ApiResponse.APPLICATION_JSON,
            headers = outcome.headers,
        )

        is Payload.Text -> ApiResponse(
            status = outcome.status,
            body = payload.text,
            contentType = ApiResponse.TEXT_PLAIN,
            headers = outcome.headers,
        )

        Payload.Empty -> ApiResponse(
            status = outcome.status,
            body = "",
            contentType = ApiResponse.APPLICATION_JSON,
            headers = outcome.headers,
        )
    }

    private fun encodeJson(payload: Payload.Json<*>): String {
        @Suppress("UNCHECKED_CAST")
        val serializer = payload.serializer as KSerializer<Any>
        @Suppress("UNCHECKED_CAST")
        val value = payload.value as Any
        return ContractJson.json.encodeToString(serializer, value)
    }

    private fun errorResponse(failure: Throwable, traceId: String): ApiResponse {
        val mapped = ErrorMapping.toResponse(failure, traceId)
        return ApiResponse(
            status = mapped.code.httpStatus,
            body = ErrorMapping.encode(mapped.response),
        )
    }

    /** 网关自有响应头：请求追踪 id。 */
    companion object {
        /** 追踪 id 响应头（与 `ErrorResponse.traceId` 同一个值）。 */
        const val HEADER_TRACE_ID: String = "X-Trace-Id"
    }
}
