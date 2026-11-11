/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   33 行  类 TokenSigner
 * 第   46 行  函数 issue
 * 第   54 行  函数 verify
 * 第   81 行  函数 sign
 * 第   88 行  对象 ?
 * 第  111 行  函数 encode
 * 第  113 行  函数 decode
 * 第  125 行  类 TokenClaims
 */

package com.jiancai.auth

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * token 的签发与校验（HMAC-SHA256 签名，无第三方依赖）。
 *
 * 线格式：`v1.<base64url(payload)>.<base64url(mac)>`
 * - payload 是 `accountId:issuedAtEpochSecond:expiresAtEpochSecond`（服务端自用，不是跨端契约）；
 * - mac = HMAC-SHA256(secret, `v1.<base64url(payload)>`)，签名覆盖方案前缀，防止版本降级；
 * - token 对客户端是**不透明字符串**：客户端不解析、不构造、不延长有效期。
 *
 * 校验顺序：结构 → 签名（定长比较）→ 过期时间 → 签发自未来（挡时钟回拨 / 伪造）。
 * 任一不通过返回 null，由 [AuthService] 统一转成 `E401`；本类不抛业务异常。
 */
internal class TokenSigner(secret: ByteArray) {

    private val key: SecretKeySpec

    init {
        require(secret.size >= MIN_SECRET_BYTES) {
            "token 签名密钥至少 $MIN_SECRET_BYTES 字节（当前：${secret.size}）；" +
                "必须由部署方提供，禁止硬编码或使用短密钥"
        }
        key = SecretKeySpec(secret.copyOf(), MAC_ALGORITHM)
    }

    /** 为 [accountId] 签发 token；有效期由调用方算好传入（不进客户端）。 */
    fun issue(accountId: Long, issuedAt: Instant, expiresAt: Instant): String {
        val payload = "$accountId:${issuedAt.epochSecond}:${expiresAt.epochSecond}"
        val encodedPayload = encode(payload.toByteArray(Charsets.UTF_8))
        val signed = "${SCHEME}${SEPARATOR}${encodedPayload}"
        return "${signed}${SEPARATOR}${encode(sign(signed))}"
    }

    /** 校验 token；返回 claims 或 null（结构错 / 签名不符 / 已过期 / 签发时间在未来）。 */
    fun verify(token: String, now: Instant): TokenClaims? {
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return null
        val parts = token.split(SEPARATOR)
        if (parts.size != PART_COUNT || parts[0] != SCHEME) return null

        val signed = "${parts[0]}${SEPARATOR}${parts[1]}"
        val expectedMac = sign(signed)
        val actualMac = decode(parts[2]) ?: return null
        if (!MessageDigest.isEqual(expectedMac, actualMac)) return null

        val payload = decode(parts[1])?.toString(Charsets.UTF_8) ?: return null
        val fields = payload.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val accountId = fields[0].toLongOrNull() ?: return null
        val issuedAt = fields[1].toLongOrNull() ?: return null
        val expiresAt = fields[2].toLongOrNull() ?: return null

        if (expiresAt <= now.epochSecond) return null
        if (issuedAt > now.epochSecond + MAX_CLOCK_SKEW_SECONDS) return null

        return TokenClaims(
            accountId = accountId,
            issuedAt = Instant.ofEpochSecond(issuedAt),
            expiresAt = Instant.ofEpochSecond(expiresAt),
        )
    }

    private fun sign(text: String): ByteArray {
        // Mac 不是线程安全的，每次新建；HMAC 初始化成本远低于一次数据库访问。
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(key)
        return mac.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        /** 方案版本：改格式时升到 v2，并保留旧版校验窗口（接口只增不改）。 */
        const val SCHEME = "v1"

        const val MAC_ALGORITHM = "HmacSHA256"

        /** 密钥最小长度（256 位）。 */
        const val MIN_SECRET_BYTES = 32

        /** 合法 token 最大长度，挡住超长输入。 */
        const val MAX_TOKEN_LENGTH = 4096

        /** 允许的签发时间超前量（秒）：只用于容忍时钟微调，不放宽过期判断。 */
        const val MAX_CLOCK_SKEW_SECONDS = 60L

        private const val SEPARATOR = "."
        private const val PART_COUNT = 3
        private const val FIELD_SEPARATOR = ":"
        private const val FIELD_COUNT = 3

        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getUrlDecoder()

        private fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

        private fun decode(text: String): ByteArray? =
            runCatching { decoder.decode(text) }.getOrNull()
    }
}

/**
 * token 里的声明（服务端内部结构，不对外暴露、不跨端序列化）。
 *
 * @property accountId 账号主键；校验时据此回库核对账号是否仍存在 / 启用
 * @property issuedAt 签发时间
 * @property expiresAt 过期时间（服务端时钟）
 */
internal data class TokenClaims(
    val accountId: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
