/*
 * @file android-app/core-network/src/main/kotlin/com/jiancai/android/network/HttpApiClient.kt
 * @role [ApiClient] 的唯一实现：拼请求 → 发传输层 → 统一解析错误码 / DTO。
 * @exports HttpApiClient
 * @depends shared
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/data/AppContainer.kt, android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.network

import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.ContractJson
import com.jiancai.shared.HEADER_AUTHORIZATION
import com.jiancai.shared.HEADER_CONTRACT_VERSION
import com.jiancai.shared.HEADER_IDEMPOTENCY_KEY
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.errors.ErrorResponse
import kotlinx.serialization.SerializationException

/**
 * [ApiClient] 的唯一实现：拼请求 → 发传输层 → 统一解析错误码 / DTO。
 *
 * 行为口径：
 * - 每次请求都注入 `X-Contract-Version`（C00 握手，服务端因此可拒绝主版本不兼容的客户端）；
 * - token 由 [TokenProvider] 提供（唯一实现是 C01 `SessionGate`），未登录不注入；
 * - 写接口带 `Idempotency-Key`（来自 [ApiRequestSpec]），重放由服务端保证；
 * - 非 2xx：解析 `shared` 的 `ErrorResponse`，错误码未知时保留原始串并走通用文案（前向兼容）；
 * - 网络故障：`E503` + `retryable = true`，**不抛异常**；
 * - 响应格式错：`E500`（服务端 bug），同样不抛异常。
 *
 * @param addresses 服务端地址存储（只读）
 * @param tokens token 提供者（C01）
 * @param transport 传输层（默认真实 JDK HTTP）
 * @param contractVersion 客户端契约版本（默认取 `shared` 的唯一真源）
 */
class HttpApiClient(
    private val addresses: ServerAddressStore,
    private val tokens: TokenProvider,
    private val transport: HttpTransport = JdkHttpTransport(),
    private val contractVersion: String = CONTRACT_VERSION,
) : ApiClient {

    override fun serverAddress(): String = addresses.baseUrl()

    override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
        val url = Urls.build(addresses.baseUrl(), spec.path, spec.query)
        val response = try {
            transport.exchange(
                TransportRequest(
                    method = spec.method,
                    url = url,
                    headers = headers(spec),
                    body = spec.bodyJson,
                ),
            )
        } catch (failure: TransportFailure) {
            return ApiResult.Failure(
                wireCode = null,
                code = ErrorCode.E503,
                message = ErrorCode.E503.message,
                httpStatus = 0,
            )
        }

        if (response.status !in 200..299) return parseFailure(response)
        if (spec.bodyless) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult.Success(Unit as T)
        }
        val serializer = spec.serializer
            ?: run {
                @Suppress("UNCHECKED_CAST")
                return ApiResult.Success(response.body as T)
            }
        return try {
            ApiResult.Success(ContractJson.json.decodeFromString(serializer, response.body))
        } catch (failure: SerializationException) {
            ApiResult.Failure(
                wireCode = null,
                code = ErrorCode.E500,
                message = ErrorCode.E500.message,
                httpStatus = response.status,
                traceId = response.headers[TRACE_HEADER]?.ifEmpty { null },
            )
        }
    }

    /** 请求头：契约版本 + 可选 token + 幂等键 + 有请求体时的 Content-Type。 */
    private fun headers(spec: ApiRequestSpec<*>): Map<String, String> = buildMap {
        put(HEADER_CONTRACT_VERSION, contractVersion)
        put("Accept", "application/json")
        tokens.token()?.takeIf { it.isNotBlank() }?.let { put(HEADER_AUTHORIZATION, "Bearer $it") }
        spec.idempotencyKey?.takeIf { it.isNotBlank() }?.let { put(HEADER_IDEMPOTENCY_KEY, it) }
        if (spec.bodyJson != null) put("Content-Type", "application/json; charset=utf-8")
    }

    /** 非 2xx → 统一失败；错误体解析不出时用 HTTP 状态兜底（仍然不抛异常）。 */
    private fun parseFailure(response: TransportResponse): ApiResult.Failure {
        val traceId = response.headers[TRACE_HEADER]?.ifEmpty { null }
        val body = runCatching {
            ContractJson.json.decodeFromString(ErrorResponse.serializer(), response.body)
        }.getOrNull()
        if (body == null) {
            val fallback = fallbackCode(response.status)
            return ApiResult.Failure(
                wireCode = null,
                code = fallback,
                message = fallback.message,
                httpStatus = response.status,
                traceId = traceId,
            )
        }
        val code = ErrorCode.fromWire(body.code)
        return ApiResult.Failure(
            wireCode = body.code,
            code = code,
            message = body.message.ifBlank { code?.message ?: GENERIC_MESSAGE },
            httpStatus = response.status,
            traceId = body.traceId ?: traceId,
        )
    }

    /** 错误体不可解析时的兜底码（只按 HTTP 语义，不猜业务）。 */
    private fun fallbackCode(status: Int): ErrorCode = when (status) {
        400 -> ErrorCode.E400
        401 -> ErrorCode.E401
        403 -> ErrorCode.E403
        404 -> ErrorCode.E404
        409 -> ErrorCode.E409
        413 -> ErrorCode.E413
        422 -> ErrorCode.E422
        429 -> ErrorCode.E429
        503 -> ErrorCode.E503
        else -> ErrorCode.E500
    }

    companion object {
        /** 服务端追踪 id 响应头（与 S13 一致）。 */
        const val TRACE_HEADER: String = "x-trace-id"

        /** 服务端错误码未知时的通用文案（不暴露技术细节）。 */
        const val GENERIC_MESSAGE: String = "操作失败，请稍后重试"
    }
}
