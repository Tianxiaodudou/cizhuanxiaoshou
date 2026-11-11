/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/AndroidTransport.kt
 * @role Android 侧 HTTP 传输实现（java.net.HttpURLConnection；Android 没有 java.net.http）
 * @exports AndroidTransport
 * @depends core-network
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/MainActivity.kt
 * @sideEffects 网络请求（HTTP）
 */
package com.jiancai.android.app

import com.jiancai.android.network.HttpMethod
import com.jiancai.android.network.HttpTransport
import com.jiancai.android.network.TransportFailure
import com.jiancai.android.network.TransportRequest
import com.jiancai.android.network.TransportResponse
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 [HttpURLConnection] 实现 C00 的传输层（Android 上没有 `java.net.http`）。
 *
 * 只负责发字节、收字节；错误码解析、token 注入仍在 C00 的 `HttpApiClient`。
 */
class AndroidTransport(
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000,
) : HttpTransport {

    override fun exchange(request: TransportRequest): TransportResponse {
        val connection = try {
            (URL(request.url).openConnection() as HttpURLConnection)
        } catch (failure: Exception) {
            throw TransportFailure("地址不可用：${failure.message}", failure)
        }
        try {
            connection.requestMethod = request.method.name
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.joinToString(",") }
            return TransportResponse(status = status, body = body, headers = headers)
        } catch (failure: TransportFailure) {
            throw failure
        } catch (failure: Exception) {
            throw TransportFailure("网络不可用：${failure.message}", failure)
        } finally {
            connection.disconnect()
        }
    }
}

/** 便于日志输出方法名（避免在 UI 里写魔法字符串）。 */
internal fun HttpMethod.label(): String = name
