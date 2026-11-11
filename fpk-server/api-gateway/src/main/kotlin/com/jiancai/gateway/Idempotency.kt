/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   38 行  接口 IdempotencyStore
 * 第   41 行  函数 get
 * 第   44 行  函数 put
 * 第   47 行  函数 remove
 * 第   56 行  类 StoredResponse
 * 第   69 行  类 InMemoryIdempotencyStore
 * 第   73 行  函数 removeEldestEntry
 * 第   77 行  函数 get
 * 第   79 行  函数 put
 * 第   83 行  函数 remove
 * 第   90 行  对象 ?
 * 第  105 行  类 IdempotencyGuard
 * 第  108 行  函数 requireKey
 * 第  124 行  函数 replay
 * 第  137 行  函数 remember
 * 第  142 行  函数 release
 * 第  147 行  函数 fingerprint
 * 第  158 行  对象 ?
 */

package com.jiancai.gateway

import com.jiancai.shared.HEADER_IDEMPOTENCY_KEY
import com.jiancai.shared.errors.ErrorCode
import java.security.MessageDigest

/**
 * 幂等键存储：接口在这里，实现在本模块内（生产可换成持久化实现，不需要改路由）。
 *
 * 语义（与 S08 `client_request_id` 的口径配合，不是替代）：
 * - 网关层保证**同一个 `Idempotency-Key` + 同一个请求指纹只执行一次**，重放直接返回首次响应；
 * - 同一个 key 换一份请求体 = 客户端 bug，返回 `E409`，不执行；
 * - 只缓存成功响应：失败的请求不占用 key，客户端可以用同一个 key 重试。
 */
interface IdempotencyStore {

    /** 取首次成功响应；没有返回 null。 */
    fun get(key: String): StoredResponse?

    /** 记录首次成功响应。 */
    fun put(key: String, value: StoredResponse)

    /** 丢弃（失败时调用，允许同 key 重试）。 */
    fun remove(key: String)
}

/**
 * 已完成的幂等请求。
 *
 * @property fingerprint 请求指纹（方法 + 路径 + 请求体），用于发现「同 key 不同请求」
 * @property response 首次响应（逐字节重放，客户端看到的结果完全一致）
 */
data class StoredResponse(val fingerprint: String, val response: ApiResponse)

/**
 * 进程内幂等键存储（唯一实现；单实例部署够用）。
 *
 * 边界与局限：
 * - 有界 LRU（[maxEntries]），最老条目先淘汰；淘汰只影响重放保护时长，不影响数据正确性
 *   （真正防重复下单仍由 S08 的 `client_request_id` 唯一约束兜底）；
 * - 多实例部署需要换成共享存储（换 [IdempotencyStore] 实现即可，属后续工作，已列 module.md）；
 * - 线程安全：内部锁保护，HTTP 线程池并发安全。
 *
 * @param maxEntries 最多记住多少个 key
 */
class InMemoryIdempotencyStore(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) : IdempotencyStore {

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, StoredResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StoredResponse>?): Boolean =
            size > maxEntries
    }

    override fun get(key: String): StoredResponse? = synchronized(lock) { entries[key] }

    override fun put(key: String, value: StoredResponse) {
        synchronized(lock) { entries[key] = value }
    }

    override fun remove(key: String) {
        synchronized(lock) { entries.remove(key) }
    }

    /** 当前记住的 key 数量（自检 / 测试用）。 */
    val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        /** 默认最多记住 10000 个幂等键。 */
        const val DEFAULT_MAX_ENTRIES: Int = 10_000
    }
}

/**
 * 幂等键校验（验收「幂等键校验」）。
 *
 * 规则：
 * - 只有 [Route.idempotent] 的路由要求该头（建单 / 建备注 / 结算提交这类会产生单据的写接口）；
 * - 缺失或空白 → `E422`（并且在调用任何业务 Port 之前失败，不产生副作用）；
 * - 同 key 同指纹 → 直接重放首次响应，**不再调用业务 Port**（用例里用计数 Port 证明「只执行一次」）；
 * - 同 key 不同指纹 → `E409`。
 */
class IdempotencyGuard(private val store: IdempotencyStore) {

    /** 读取并校验幂等键；返回 key 或抛 422。 */
    fun requireKey(request: ApiRequest): String {
        val key = request.header(HEADER_IDEMPOTENCY_KEY)?.trim()
        if (key.isNullOrEmpty()) {
            throw GatewayException(
                code = ErrorCode.E422,
                message = "缺少幂等键请求头 $HEADER_IDEMPOTENCY_KEY",
                details = mapOf("header" to HEADER_IDEMPOTENCY_KEY),
            )
        }
        if (key.length > MAX_KEY_LENGTH) {
            throw GatewayException(ErrorCode.E422, "幂等键超过 $MAX_KEY_LENGTH 字符")
        }
        return key
    }

    /** 已在存储里的同 key 响应；指纹不一致 → 409。 */
    fun replay(key: String, fingerprint: String): ApiResponse? {
        val stored = store.get(key) ?: return null
        if (stored.fingerprint != fingerprint) {
            throw GatewayException(
                code = ErrorCode.E409,
                message = "幂等键已用于另一个请求，请换一个新的幂等键",
                details = mapOf("header" to HEADER_IDEMPOTENCY_KEY),
            )
        }
        return stored.response.withHeader(HEADER_IDEMPOTENT_REPLAY, "true")
    }

    /** 成功响应入账。 */
    fun remember(key: String, fingerprint: String, response: ApiResponse) {
        store.put(key, StoredResponse(fingerprint, response))
    }

    /** 失败时释放 key，允许客户端用同一个 key 重试。 */
    fun release(key: String) {
        store.remove(key)
    }

    /** 请求指纹：方法 + 路径 + 请求体（查询参数已包含在路径语义里，这里显式拼上）。 */
    fun fingerprint(request: ApiRequest): String {
        val canonical = buildString {
            append(request.method.name).append('\n')
            append(request.path.trim('/')).append('\n')
            request.query.toSortedMap().forEach { (name, value) -> append(name).append('=').append(value).append('&') }
            append('\n').append(request.body)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    companion object {
        /** 幂等键最大长度（防止把请求头当存储用）。 */
        const val MAX_KEY_LENGTH: Int = 128

        /** 重放标记响应头：客户端据此知道「这次是重放，服务端没有重复执行」。 */
        const val HEADER_IDEMPOTENT_REPLAY: String = "X-Idempotent-Replay"
    }
}
