/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   30 行  接口 PasswordHasher
 * 第   36 行  函数 hash
 * 第   39 行  函数 verify
 * 第   48 行  类 Pbkdf2PasswordHasher
 * 第   63 行  函数 hash
 * 第   70 行  函数 verify
 * 第   86 行  函数 derive
 * 第   95 行  对象 ?
 * 第  127 行  函数 encode
 * 第  129 行  函数 decode
 */

package com.jiancai.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 密码哈希策略（硬边界：**禁止明文存密码**）。
 *
 * 对外暴露成接口是为了让组合根 / 测试能注入不同的成本参数；
 * 生产默认实现 [Pbkdf2PasswordHasher]，编码串自带算法与迭代次数，便于日后平滑升级。
 */
interface PasswordHasher {

    /**
     * 生成自描述哈希串：`pbkdf2-sha256$<迭代次数>$<salt(base64)>$<derivedKey(base64)>`。
     * 每次调用使用新的随机盐，同一密码两次哈希结果不同。
     */
    fun hash(rawPassword: String): String

    /** 校验明文密码与哈希串是否匹配；哈希串损坏 / 算法未知识别为 **不匹配**，不抛异常。 */
    fun verify(rawPassword: String, encoded: String): Boolean
}

/**
 * PBKDF2-HMAC-SHA256 实现（JDK 自带 `SecretKeyFactory`，不引入第三方依赖）。
 *
 * 默认参数：210_000 次迭代、16 字节随机盐、32 字节派生密钥。
 * 迭代次数写入哈希串，因此后续调高默认值不影响老账号校验。
 */
class Pbkdf2PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val saltLength: Int = DEFAULT_SALT_LENGTH,
    private val keyLength: Int = DEFAULT_KEY_LENGTH,
) : PasswordHasher {

    private val random = SecureRandom()

    init {
        require(iterations >= MIN_ITERATIONS) { "PBKDF2 迭代次数必须 >= $MIN_ITERATIONS（当前：$iterations）" }
        require(saltLength >= MIN_SALT_LENGTH) { "盐长度必须 >= $MIN_SALT_LENGTH 字节（当前：$saltLength）" }
        require(keyLength >= MIN_KEY_LENGTH) { "派生密钥长度必须 >= $MIN_KEY_LENGTH 字节（当前：$keyLength）" }
        require(keyLength <= MAX_KEY_LENGTH) { "派生密钥长度必须 <= $MAX_KEY_LENGTH 字节（当前：$keyLength）" }
    }

    override fun hash(rawPassword: String): String {
        require(rawPassword.isNotEmpty()) { "密码不能为空" }
        val salt = ByteArray(saltLength).also { random.nextBytes(it) }
        val derived = derive(rawPassword, salt, iterations, keyLength)
        return listOf(PREFIX, iterations.toString(), encode(salt), encode(derived)).joinToString(SEPARATOR)
    }

    override fun verify(rawPassword: String, encoded: String): Boolean {
        if (rawPassword.isEmpty()) return false
        val parts = encoded.split(SEPARATOR)
        if (parts.size != PART_COUNT || parts[0] != PREFIX) return false
        val cost = parts[1].toIntOrNull() ?: return false
        if (cost < MIN_ITERATIONS || cost > MAX_ITERATIONS) return false
        val salt = decode(parts[2]) ?: return false
        val expected = decode(parts[3]) ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        // 派生密钥长度也做上下界：哈希串被篡改成超大输出会变成 CPU / 内存放大攻击。
        if (expected.size < MIN_KEY_LENGTH || expected.size > MAX_KEY_LENGTH) return false
        val actual = derive(rawPassword, salt, cost, expected.size)
        // 定长比较，避免按字节提前返回造成的时序差异。
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(rawPassword: String, salt: ByteArray, cost: Int, length: Int): ByteArray {
        val spec = PBEKeySpec(rawPassword.toCharArray(), salt, cost, length * 8)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        /** 新哈希的默认迭代次数；测试可注入更小的成本（不得低于 [MIN_ITERATIONS]）。 */
        const val DEFAULT_ITERATIONS: Int = 210_000

        /** 盐长度（字节）。 */
        const val DEFAULT_SALT_LENGTH: Int = 16

        /** 派生密钥长度（字节）。 */
        const val DEFAULT_KEY_LENGTH: Int = 32

        /** 允许的最小迭代次数（测试下限；生产默认值远高于此）。 */
        const val MIN_ITERATIONS: Int = 1_000

        /** 校验时接受的最大迭代次数，挡住「哈希串被改成天价成本」的拒绝服务输入。 */
        const val MAX_ITERATIONS: Int = 5_000_000

        private const val MIN_SALT_LENGTH = 8
        private const val MIN_KEY_LENGTH = 16
        private const val MAX_KEY_LENGTH = 64

        /** JDK 标准算法名。 */
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"

        /** 哈希串算法前缀（数据库 CHECK 也按它拦明文，见 `AuthMigrations`）。 */
        const val PREFIX = "pbkdf2-sha256"

        private const val SEPARATOR = "\$"
        private const val PART_COUNT = 4

        private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getDecoder()

        private fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

        private fun decode(text: String): ByteArray? =
            runCatching { decoder.decode(text) }.getOrNull()
    }
}
