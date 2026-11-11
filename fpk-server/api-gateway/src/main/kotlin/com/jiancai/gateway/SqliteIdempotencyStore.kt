/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   37 行  类 GatewayIdempotencyRepository
 * 第   40 行  函数 find
 * 第   58 行  函数 upsert
 * 第   73 行  函数 delete
 * 第   81 行  函数 purgeExpired
 * 第   89 行  函数 count
 * 第   97 行  对象 ?
 * 第  132 行  类 StoredIdempotency
 * 第  153 行  类 SqliteIdempotencyStore
 * 第  161 行  函数 get
 * 第  177 行  函数 put
 * 第  193 行  函数 remove
 * 第  198 行  函数 size
 * 第  200 行  函数 encodeHeaders
 * 第  203 行  函数 decodeHeaders
 * 第  211 行  对象 ?
 */

package com.jiancai.gateway

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.ContractJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Clock
import java.time.Duration

/**
 * `gateway_idempotency` 的唯一 SQL 出口（铁律 10）。
 *
 * 表归 S13 所有：只存「首次成功响应」的快照，不承载任何业务数据。
 */
internal class GatewayIdempotencyRepository {

    /** 读取一条未过期的记录。 */
    fun find(tx: TxContext, key: String, now: Long): StoredIdempotency? =
        tx.connection.prepareStatement(SELECT).use { statement ->
            statement.setString(1, key)
            statement.setLong(2, now)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                StoredIdempotency(
                    fingerprint = rows.getString("fingerprint"),
                    status = rows.getInt("status"),
                    contentType = rows.getString("content_type"),
                    headersJson = rows.getString("headers_json"),
                    body = rows.getString("body"),
                    expiresAt = rows.getLong("expires_at"),
                )
            }
        }

    /** 写入 / 覆盖一条快照（同一个 key 成功后不会被再次执行，覆盖只用于测试与重试场景）。 */
    fun upsert(tx: TxContext, key: String, record: StoredIdempotency, now: Long) {
        tx.connection.prepareStatement(UPSERT).use { statement ->
            statement.setString(1, key)
            statement.setString(2, record.fingerprint)
            statement.setInt(3, record.status)
            statement.setString(4, record.contentType)
            statement.setString(5, record.headersJson)
            statement.setString(6, record.body)
            statement.setLong(7, now)
            statement.setLong(8, record.expiresAt)
            statement.executeUpdate()
        }
    }

    /** 删除一个 key（失败时释放，允许客户端用同一个 key 重试）。 */
    fun delete(tx: TxContext, key: String) {
        tx.connection.prepareStatement(DELETE).use { statement ->
            statement.setString(1, key)
            statement.executeUpdate()
        }
    }

    /** 清理过期行（每次写入顺带做一次，避免无限增长）。 */
    fun purgeExpired(tx: TxContext, now: Long) {
        tx.connection.prepareStatement(PURGE).use { statement ->
            statement.setLong(1, now)
            statement.executeUpdate()
        }
    }

    /** 当前行数（自检 / 测试用）。 */
    fun count(tx: TxContext): Int =
        tx.connection.prepareStatement(COUNT).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "gateway_idempotency 计数失败" }
                rows.getInt(1)
            }
        }

    private companion object {
        val SELECT = """
            SELECT fingerprint, status, content_type, headers_json, body, expires_at
            FROM gateway_idempotency
            WHERE idempotency_key = ? AND expires_at > ?
        """.trimIndent()

        val UPSERT = """
            INSERT INTO gateway_idempotency (
                idempotency_key, fingerprint, status, content_type, headers_json, body, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO UPDATE SET
                fingerprint = excluded.fingerprint,
                status = excluded.status,
                content_type = excluded.content_type,
                headers_json = excluded.headers_json,
                body = excluded.body,
                created_at = excluded.created_at,
                expires_at = excluded.expires_at
        """.trimIndent()

        const val DELETE = "DELETE FROM gateway_idempotency WHERE idempotency_key = ?"

        const val PURGE = "DELETE FROM gateway_idempotency WHERE expires_at <= ?"

        const val COUNT = "SELECT COUNT(*) FROM gateway_idempotency"
    }
}

/**
 * 幂等快照的持久化形态（内部，不对外）。
 *
 * @property headersJson 响应头 JSON（`Map<String,String>`）
 * @property expiresAt 过期时间（epoch millis）
 */
internal data class StoredIdempotency(
    val fingerprint: String,
    val status: Int,
    val contentType: String,
    val headersJson: String,
    val body: String,
    val expiresAt: Long,
)

/**
 * SQLite 版幂等键存储：重启 / 多进程仍能重放（验收「幂等键校验」的持久化版本）。
 *
 * 与 [InMemoryIdempotencyStore] 的差别：
 * - 真实落库，进程重启后同一个 `Idempotency-Key` 仍返回首次响应；
 * - TTL 过期后自动失效（[ttl] 默认 24 小时），每次写入顺带清理过期行；
 * - 线程安全由 db-core 的 `TxRunner`（单连接 + 全局锁）保证。
 *
 * @param txRunner db-core 事务 Port
 * @param clock 服务端时钟（只用于 TTL，不参与业务日期）
 * @param ttl 幂等快照有效期
 */
class SqliteIdempotencyStore(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ttl: Duration = DEFAULT_TTL,
) : IdempotencyStore {

    private val repository = GatewayIdempotencyRepository()

    override fun get(key: String): StoredResponse? {
        val now = clock.millis()
        return txRunner.run { tx ->
            val stored = repository.find(tx, key, now) ?: return@run null
            StoredResponse(
                fingerprint = stored.fingerprint,
                response = ApiResponse(
                    status = stored.status,
                    body = stored.body,
                    contentType = stored.contentType,
                    headers = decodeHeaders(stored.headersJson),
                ),
            )
        }
    }

    override fun put(key: String, value: StoredResponse) {
        val now = clock.millis()
        val record = StoredIdempotency(
            fingerprint = value.fingerprint,
            status = value.response.status,
            contentType = value.response.contentType,
            headersJson = encodeHeaders(value.response.headers),
            body = value.response.body,
            expiresAt = now + ttl.toMillis(),
        )
        txRunner.run { tx ->
            repository.purgeExpired(tx, now)
            repository.upsert(tx, key, record, now)
        }
    }

    override fun remove(key: String) {
        txRunner.run { tx -> repository.delete(tx, key) }
    }

    /** 当前台账行数（自检 / 测试用）。 */
    fun size(): Int = txRunner.run { tx -> repository.count(tx) }

    private fun encodeHeaders(headers: Map<String, String>): String =
        ContractJson.json.encodeToString(HEADERS_SERIALIZER, LinkedHashMap(headers))

    private fun decodeHeaders(json: String): Map<String, String> =
        try {
            ContractJson.json.decodeFromString(HEADERS_SERIALIZER, json)
        } catch (failure: RuntimeException) {
            // 台账损坏不应让客户端无法重放：宁可退化成「无额外响应头」。
            emptyMap()
        }

    companion object {
        /** 默认有效期：24 小时（足够覆盖门店当天重试，又不会让台账无限增长）。 */
        val DEFAULT_TTL: Duration = Duration.ofHours(24)

        private val HEADERS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
