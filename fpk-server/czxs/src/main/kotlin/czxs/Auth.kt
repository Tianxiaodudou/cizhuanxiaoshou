package czxs

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 管理端与客户端两套认证互不通用（规格 61）：
 * - 客户端 token 存 api_tokens；
 * - 管理端 session 存 admin_sessions（Cookie）。
 */
object Auth {
    private val random = SecureRandom()

    fun hashPassword(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = pbkdf2(password, salt)
        return "pbkdf2$" + b64(salt) + "$" + b64(hash)
    }

    fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 3 || parts[0] != "pbkdf2") return false
        val salt = Base64.getUrlDecoder().decode(parts[1])
        val expected = Base64.getUrlDecoder().decode(parts[2])
        return MessageDigest.isEqual(pbkdf2(password, salt), expected)
    }

    fun newToken(): String = b64(ByteArray(32).also { random.nextBytes(it) })

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
