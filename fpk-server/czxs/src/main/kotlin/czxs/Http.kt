package czxs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class Req(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val cookies: Map<String, String>,
)

data class Resp(
    val status: Int = 200,
    val contentType: String = "application/json; charset=utf-8",
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/** 极简 HTTP/1.1 服务：TCP（客户端 JSON）+ Unix Socket（飞牛统一网关 → 管理页 HTML）。 */
class HttpServer(private val handler: (Req) -> Resp) {

    fun startTcp(port: Int) {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("0.0.0.0", port), 64)
        server.createContext("/") { exchange ->
            val req = readTcp(exchange)
            val resp = try {
                handler(req)
            } catch (e: ApiError) {
                Json.error(e.code, e.message)
            } catch (e: Throwable) {
                Json.error("INTERNAL", e.message ?: "服务异常")
            }
            writeTcp(exchange, resp)
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.start()
    }

    fun startUnix(socketPath: String) {
        val path = Path.of(socketPath)
        Files.createDirectories(path.parent)
        if (Files.exists(path)) Files.deleteIfExists(path)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path), 64)
        Thread {
            while (true) {
                val channel = runCatching { server.accept() }.getOrNull() ?: break
                Thread { handleUnix(channel) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handleUnix(channel: SocketChannel) {
        channel.use { ch ->
            val input = Charsets.UTF_8.newDecoder()
            val req = readUnix(ch) ?: return
            val resp = try {
                handler(req)
            } catch (e: ApiError) {
                Json.error(e.code, e.message)
            } catch (e: Throwable) {
                Json.error("INTERNAL", e.message ?: "服务异常")
            }
            writeUnix(ch, req, resp)
        }
    }

    private fun readTcp(exchange: com.sun.net.httpserver.HttpExchange): Req {
        val uri = exchange.requestURI
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() }
        return Req(
            exchange.requestMethod, uri.path, parseQuery(uri.rawQuery), headers, body, parseCookies(headers["cookie"].orEmpty()),
        )
    }

    private fun writeTcp(exchange: com.sun.net.httpserver.HttpExchange, resp: Resp) {
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", resp.contentType)
        resp.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.sendResponseHeaders(resp.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readUnix(ch: SocketChannel): Req? {
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val target = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()
        return Req(method, path, query, headers, body, parseCookies(headers["cookie"].orEmpty()))
    }

    private fun writeUnix(ch: SocketChannel, req: Req, resp: Resp) {
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${resp.status} ${statusText(resp.status)}\r\n")
            append("Content-Type: ${resp.contentType}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            resp.headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        val out: OutputStream = Channels.newOutputStream(ch)
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"
        else -> "OK"
    }

    companion object {
        fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split('&').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i < 0) null else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
            }.toMap()
        }

        fun parseCookies(raw: String): Map<String, String> =
            raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i < 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

        fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
    }
}
