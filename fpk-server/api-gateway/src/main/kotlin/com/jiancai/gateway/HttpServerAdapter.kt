/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   42 行  类 HttpServerAdapter
 * 第   62 行  函数 start
 * 第   73 行  函数 stop
 * 第   80 行  函数 close
 * 第   85 行  函数 handle
 * 第  104 行  函数 write
 * 第  116 行  函数 readBody
 * 第  128 行  函数 parseQuery
 * 第  140 行  函数 decode
 * 第  147 行  函数 toHttpMethod
 * 第  149 行  对象 ?
 */

package com.jiancai.gateway

import com.jiancai.shared.errors.ErrorCode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * JDK 自带 HTTP 服务器适配层（唯一与具体服务器绑定的一层）。
 *
 * 为什么不引第三方框架：网关的职责是「路由 / 拦截 / 映射」，这些都在 [ApiGateway] 里且不依赖框架；
 * 绑定层只做字节搬运，换成其它服务器时只改这个文件。
 *
 * 硬边界：
 * - **不含任何业务判断**：状态码、错误码、响应体全部来自 [ApiGateway]；
 * - **不做权限判断**：token / 角色由 [AuthGate] 处理；
 * - **有界读**：请求体最多读 `maxBodyBytes + 1` 字节，超出由网关统一回 `E413`，不会把内存读爆。
 *
 * @param gateway 网关核心
 * @param executor 处理请求的线程池（默认 4 线程；单机门店够用，线程数可调）
 */
class HttpServerAdapter(
    private val gateway: ApiGateway,
    private val executor: ExecutorService = Executors.newFixedThreadPool(DEFAULT_THREADS),
) : AutoCloseable {

    private var server: HttpServer? = null

    /** 实际监听端口（启动前为 -1；传 0 启动时由系统分配）。 */
    val port: Int get() = server?.address?.port ?: -1

    /** 是否已启动。 */
    val isRunning: Boolean get() = server != null

    /**
     * 启动监听。
     *
     * @param port 监听端口，0 = 由系统分配（测试用）
     * @param host 绑定地址，默认只监听回环（门店内网部署时改成 `0.0.0.0`）
     * @return 实际监听端口
     */
    fun start(port: Int = 0, host: String = DEFAULT_HOST): Int {
        check(server == null) { "HTTP 服务器已启动" }
        val created = HttpServer.create(InetSocketAddress(host, port), BACKLOG)
        created.executor = executor
        created.createContext("/") { exchange -> exchange.use { handle(it) } }
        created.start()
        server = created
        return created.address.port
    }

    /** 停止监听并释放端口（可重复调用）。 */
    fun stop() {
        val running = server ?: return
        server = null
        running.stop(STOP_DELAY_SECONDS)
    }

    /** 关闭服务器与线程池。 */
    override fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase().toHttpMethod()
        if (method == null) {
            write(exchange, ErrorMapping.responseOf(ErrorCode.E404, "不支持的方法：${exchange.requestMethod}"))
            return
        }
        val request = ApiRequest(
            method = method,
            path = exchange.requestURI.path.orEmpty(),
            query = parseQuery(exchange.requestURI.rawQuery),
            headers = exchange.requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.joinToString(",")
            },
            body = readBody(exchange.requestBody, gateway.deps.maxBodyBytes),
        )
        write(exchange, gateway.handle(request))
    }

    /** 写响应：空体走 204 语义，其余按 UTF-8 字节写出并带 Content-Type。 */
    private fun write(exchange: HttpExchange, response: ApiResponse) {
        response.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        exchange.responseHeaders.set("Content-Type", response.contentType)
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(response.status, -1)
            return
        }
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readBody(input: InputStream, maxBodyBytes: Int): String {
        val limit = maxBodyBytes + 1
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = input.read(buffer, read, limit - read)
            if (count <= 0) break
            read += count
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val name = pair.substringBefore('=').decode()
            val value = pair.substringAfter('=', "").decode()
            if (name.isNotEmpty()) result.putIfAbsent(name, value)
        }
        return result
    }

    private fun String.decode(): String =
        try {
            URLDecoder.decode(this, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            this
        }

    private fun String.toHttpMethod(): HttpMethod? = HttpMethod.entries.firstOrNull { it.name == this }

    companion object {
        /** 默认只监听回环。 */
        const val DEFAULT_HOST: String = "127.0.0.1"

        /** 默认工作线程数。 */
        const val DEFAULT_THREADS: Int = 4

        /** 监听队列长度。 */
        const val BACKLOG: Int = 64

        /** 停止时等待未完成请求的秒数。 */
        const val STOP_DELAY_SECONDS: Int = 1
    }
}
