/*
 * @file android-app/core-network/src/main/kotlin/com/jiancai/android/network/ApiClient.kt
 * @role 客户端唯一入口。
 * @exports HttpMethod, ApiRequestSpec, ApiResult, TokenProvider, ApiClient
 * @depends shared
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/AndroidTransport.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/data/AppContainer.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/ui/common/Api.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.network

import com.jiancai.shared.errors.ErrorCode
import kotlinx.serialization.KSerializer

/**
 * 客户端 HTTP 方法（与 S13 路由表一致；客户端只允许这四种）。
 */
enum class HttpMethod { GET, POST, PUT, DELETE }

/**
 * 一次接口调用的描述（C00 是**唯一**拼 URL / 注入 token / 解析错误码的地方）。
 *
 * @property method HTTP 方法
 * @property path 服务端路径（必须来自 [ApiPaths]，客户端不得就地写字符串）
 * @property query 查询参数（值由调用方提供，C00 负责 URL 编码）
 * @property bodyJson 请求体（`shared` 的 Cmd 序列化结果；GET / DELETE 为 null）
 * @property serializer 响应反序列化器；为 null 表示「原始文本」（S12 打印）或「无响应体」
 * @property idempotencyKey 幂等键（写接口必填；客户端直接用 Cmd 里的 `client_request_id`）
 * @property bodyless 是否没有响应体（204 删除），成功时返回 `Unit`
 */
data class ApiRequestSpec<T>(
    val method: HttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val bodyJson: String? = null,
    val serializer: KSerializer<T>? = null,
    val idempotencyKey: String? = null,
    val bodyless: Boolean = false,
)

/**
 * 统一调用结果。
 *
 * 硬边界：客户端**不解释业务**。成功就是服务端给的 DTO（金额 / 单号 / 状态原样展示）；
 * 失败就是服务端给的错误码 + 文案，客户端不根据 HTTP 状态自己编提示。
 */
sealed interface ApiResult<out T> {

    /** 成功：载荷是服务端 DTO（或原始文本 / Unit）。 */
    data class Success<T>(val value: T) : ApiResult<T>

    /**
     * 失败：错误码来自 `shared` 契约。
     *
     * @property wireCode 服务端原始错误码字符串（未知码时用于排查，前向兼容）
     * @property code 已知错误码；服务端新增错误码时旧客户端为 null（走通用文案）
     * @property message 服务端产出的用户文案（C00 不覆盖、不自编）
     * @property httpStatus HTTP 状态（0 表示没拿到响应：断网 / 超时）
     * @property traceId 服务端追踪 id（与日志对齐）
     */
    data class Failure(
        val wireCode: String?,
        val code: ErrorCode?,
        val message: String,
        val httpStatus: Int,
        val traceId: String? = null,
    ) : ApiResult<Nothing> {

        /** 是否可自动重试（取 [ErrorCode.retryable]；未知码不重试）。 */
        val retryable: Boolean get() = code?.retryable ?: false

        /** 是否是「登录失效」，由 C01 单点决定是否回登录页。 */
        val isUnauthorized: Boolean get() = code == ErrorCode.E401
    }
}

/**
 * 调用结果便捷取值（`when` 的语法糖，测试与 ViewModel 共用）。
 */
inline fun <T, R> ApiResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (ApiResult.Failure) -> R,
): R = when (this) {
    is ApiResult.Success -> onSuccess(value)
    is ApiResult.Failure -> onFailure(this)
}

/**
 * token 提供者：**唯一实现方是 C01 `SessionGate`**。
 *
 * C00 只声明「需要 token 时问谁」，不保存、不判断登录态（铁律：客户端 token 判断只在 C01 一处）。
 */
fun interface TokenProvider {
    /** 当前 token；未登录返回 null（此时 C00 不注入 Authorization 头）。 */
    fun token(): String?
}

/**
 * 客户端唯一入口。
 *
 * 职责（验收「错误码统一解析」「token 自动注入」）：
 * - 拼 URL / query / 请求头（契约版本、token、幂等键、Content-Type）；
 * - 统一解析非 2xx 的 `ErrorResponse`（`shared` 契约），未知错误码走通用文案；
 * - 把网络故障归一为 `E503` 失败（可重试），不抛异常给界面层。
 *
 * 禁止：业务逻辑、金额 / 单号计算、本地缓存业务数据。
 */
interface ApiClient {

    /** 发起一次调用；**永不抛异常**，网络/解析故障都变成 [ApiResult.Failure]。 */
    fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T>

    /** 当前服务端地址（只读展示；修改走 [ServerAddressStore]，仅 C04 设置页可改）。 */
    fun serverAddress(): String
}
