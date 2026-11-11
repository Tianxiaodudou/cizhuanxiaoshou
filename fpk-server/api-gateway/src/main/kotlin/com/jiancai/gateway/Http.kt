/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   27 行  类 HttpMethod
 * 第   41 行  类 ApiRequest
 * 第   49 行  函数 header
 * 第   52 行  函数 header
 * 第   66 行  类 ApiResponse
 * 第   73 行  函数 withHeader
 * 第   76 行  对象 ?
 * 第   90 行  接口 Payload
 * 第   93 行  类 Json
 * 第   96 行  类 Text
 * 第   99 行  对象 Empty
 * 第  109 行  类 RouteOutcome
 * 第  116 行  函数 respondJson
 * 第  124 行  函数 respondCreated
 * 第  128 行  函数 respondNoContent
 * 第  131 行  函数 respondText
 */

package com.jiancai.gateway

/**
 * HTTP 方法（只支持网关实际暴露的四种；其它方法一律 404，避免半实现语义）。
 */
enum class HttpMethod { GET, POST, PUT, DELETE }

/**
 * 一次 HTTP 请求（与框架无关的中间形态）。
 *
 * [HttpServerAdapter] 把 JDK `com.sun.net.httpserver` 的请求翻译成它，因此网关核心与测试
 * 都不依赖具体 Web 框架；[headers] 的键统一小写，[header] 大小写不敏感。
 *
 * @property method 请求方法
 * @property path 原始路径（不含查询串，可带或不带前导 `/`）
 * @property query 查询参数（同名参数取第一个；网关只接受单值参数）
 * @property headers 请求头（键小写）
 * @property body 请求体原文（UTF-8）
 */
data class ApiRequest(
    val method: HttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    /** 大小写不敏感地取请求头。 */
    fun header(name: String): String? = headers[name.lowercase()]

    /** 大小写不敏感地取请求头，缺省时给默认值。 */
    fun header(name: String, default: String): String = header(name) ?: default

    /** 请求体字节数（UTF-8；用于大小上限拦截）。 */
    val bodyBytes: Int get() = body.toByteArray(Charsets.UTF_8).size
}

/**
 * 一次 HTTP 响应（网关产出的唯一形态；状态 / 头 / 体都由网关决定）。
 *
 * @property status HTTP 状态码
 * @property body 响应体（JSON 或纯文本）
 * @property contentType Content-Type
 * @property headers 额外响应头（键按字面写出，例如 `X-Trace-Id`）
 */
data class ApiResponse(
    val status: Int,
    val body: String,
    val contentType: String = APPLICATION_JSON,
    val headers: Map<String, String> = emptyMap(),
) {
    /** 追加 / 覆盖一个响应头，返回新实例（响应不可变）。 */
    fun withHeader(name: String, value: String): ApiResponse =
        copy(headers = headers + (name to value))

    companion object {
        /** JSON 响应类型（UTF-8）。 */
        const val APPLICATION_JSON: String = "application/json; charset=utf-8"

        /** 纯文本响应类型（服务端渲染票据）。 */
        const val TEXT_PLAIN: String = "text/plain; charset=utf-8"
    }
}

/**
 * 路由处理结果：业务 DTO 或纯文本，由网关统一编码成 [ApiResponse]。
 *
 * 只有网关知道「DTO → 线格式」，各 handler 只负责调用 Port 并交出 DTO（铁律 2）。
 */
sealed interface Payload {

    /** JSON 载荷：必须显式给出 serializers 模块的序列化器（网关不猜类型）。 */
    class Json<T>(val serializer: kotlinx.serialization.KSerializer<T>, val value: T) : Payload

    /** 纯文本载荷（S12 渲染好的 80mm 票据）。 */
    data class Text(val text: String) : Payload

    /** 空载荷（204 / 无内容）。 */
    data object Empty : Payload
}

/**
 * 路由处理结果。
 *
 * @property status 成功状态码（默认 200；创建类用 201，删除用 204）
 * @property payload 响应载荷
 * @property headers 额外响应头（例如打印次数）
 */
data class RouteOutcome(
    val status: Int = 200,
    val payload: Payload = Payload.Empty,
    val headers: Map<String, String> = emptyMap(),
)

/** 200 / 201：把 DTO 作为 JSON 返回。 */
fun <T> respondJson(
    value: T,
    serializer: kotlinx.serialization.KSerializer<T>,
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
): RouteOutcome = RouteOutcome(status, Payload.Json(serializer, value), headers)

/** 201：创建成功。 */
fun <T> respondCreated(value: T, serializer: kotlinx.serialization.KSerializer<T>): RouteOutcome =
    respondJson(value, serializer, status = 201)

/** 204：删除成功，无响应体。 */
fun respondNoContent(): RouteOutcome = RouteOutcome(status = 204, payload = Payload.Empty)

/** 200：纯文本（服务端渲染结果）。 */
fun respondText(text: String, headers: Map<String, String> = emptyMap()): RouteOutcome =
    RouteOutcome(status = 200, payload = Payload.Text(text), headers = headers)
