/*
 * @file android-app/core-network/src/main/kotlin/com/jiancai/android/network/HttpTransport.kt
 * @role 传输层抽象：只做「发字节、收字节」。
 * @exports TransportRequest, TransportResponse, TransportFailure, HttpTransport, JdkHttpTransport
 * @depends （无）
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/AndroidTransport.kt, android-app/core-network/src/main/kotlin/com/jiancai/android/network/HttpApiClient.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.network

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 一次真实的 HTTP 交换（C00 内部类型，测试可注入替身）。
 */
data class TransportRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** 一次 HTTP 响应（状态 + 原始文本 + 需要的响应头）。 */
data class TransportResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 传输层故障（断网 / 超时 / DNS），由 [HttpApiClient] 归一为 `E503`。 */
class TransportFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 传输层抽象：只做「发字节、收字节」。
 *
 * 换成 OkHttp / Cronet 时只实现这个接口，C00 之上的所有模块不用动。
 */
interface HttpTransport {
    fun exchange(request: TransportRequest): TransportResponse
}

/**
 * JDK `java.net.http.HttpClient` 实现（零第三方依赖；真实 Android 工程可换 OkHttp）。
 *
 * 所有异常都包成 [TransportFailure]（不吞、不重试；重试策略属于业务界面）。
 */
class JdkHttpTransport(
    private val timeout: Duration = Duration.ofSeconds(15),
) : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun exchange(request: TransportRequest): TransportResponse {
        val builder = HttpRequest.newBuilder(URI.create(request.url)).timeout(timeout)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val publisher = request.body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()
        builder.method(request.method.name, publisher)

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TransportFailure("请求被中断", failure)
        } catch (failure: Exception) {
            throw TransportFailure("网络不可用：${failure.message}", failure)
        }
        return TransportResponse(
            status = response.statusCode(),
            body = response.body() ?: "",
            headers = response.headers().map().mapValues { (_, values) -> values.joinToString(",") },
        )
    }
}

/** URL 拼装（query 编码只在这里做一次）。 */
internal object Urls {

    fun build(base: String, path: String, query: Map<String, String>): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val url = base.trimEnd('/') + normalizedPath
        if (query.isEmpty()) return url
        val joined = query.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return if (joined.isEmpty()) url else "$url?$joined"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
