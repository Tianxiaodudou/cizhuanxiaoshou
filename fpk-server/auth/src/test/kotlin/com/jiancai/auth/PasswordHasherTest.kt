/*
 * @file fpk-server/auth/src/test/kotlin/com/jiancai/auth/PasswordHasherTest.kt
 * @role 密码散列硬边界（S02）：自描述格式、随机盐、损坏 / 越界输入一律判为「不匹配」，不抛 500。
 * @exports PasswordHasherTest
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 密码散列硬边界（S02）：自描述格式、随机盐、损坏 / 越界输入一律判为「不匹配」，不抛 500。
 *
 * `auth/module.md`「密码存储」里的每条承诺在这里都有直接用例；`AuthPortTest` 只覆盖了
 * 默认成本与库层 CHECK，防止本模块内部实现被改成「解析失败就抛异常」或「成本无上下界」。
 */
class PasswordHasherTest {

    private val hasher = Pbkdf2PasswordHasher(iterations = 1_000)

    private val password = "correct horse battery"

    @Test
    fun encoding_is_self_describing_and_carries_no_plaintext() {
        val encoded = hasher.hash(password)
        val parts = encoded.split("\$")

        assertEquals(4, parts.size, "哈希串格式：算法\$迭代\$盐\$派生密钥")
        assertEquals(Pbkdf2PasswordHasher.PREFIX, parts[0])
        assertEquals("1000", parts[1], "迭代次数必须写进哈希串，便于日后平滑升级")
        assertEquals(16, Base64.getDecoder().decode(parts[2]).size, "盐长度沿用默认值")
        assertEquals(32, Base64.getDecoder().decode(parts[3]).size, "派生密钥长度沿用默认值")
        assertFalse(encoded.contains(password), "哈希串里不得出现明文密码")
        assertTrue(hasher.verify(password, encoded))
    }

    @Test
    fun every_hash_uses_a_fresh_random_salt() {
        val first = hasher.hash(password)
        val second = hasher.hash(password)

        assertNotEquals(first, second, "同一密码两次哈希必须不同（随机盐）")
        assertTrue(hasher.verify(password, first))
        assertTrue(hasher.verify(password, second))
    }

    @Test
    fun wrong_or_blank_password_never_verifies() {
        val encoded = hasher.hash(password)

        assertFalse(hasher.verify("another password", encoded))
        assertFalse(hasher.verify("", encoded), "空密码直接判为不匹配，不做无意义派生")
    }

    @Test
    fun corrupt_or_unknown_encodings_are_not_matching_instead_of_throwing() {
        val valid = hasher.hash(password)
        val parts = valid.split("\$")
        val corrupted = listOf(
            "",
            password,
            "bcrypt\$1000\$c2FsdA\$aGFzaA",
            "pbkdf2-sha256",
            "pbkdf2-sha256\$abc\$c2FsdA\$aGFzaA",
            "pbkdf2-sha256\$${Pbkdf2PasswordHasher.MIN_ITERATIONS - 1}\$c2FsdA\$aGFzaA",
            "pbkdf2-sha256\$${Pbkdf2PasswordHasher.MAX_ITERATIONS + 1}\$c2FsdA\$aGFzaA",
            "pbkdf2-sha256\$1000\$!!!\$aGFzaA",
            "pbkdf2-sha256\$1000\$\$aGFzaA",
            "pbkdf2-sha256\$1000\$c2FsdA\$!!!",
            "pbkdf2-sha256\$1000\$c2FsdA\$",
            listOf(parts[0], parts[1], parts[2], "aGFzaA").joinToString("\$"),
        )

        corrupted.forEach { candidate ->
            assertFalse(hasher.verify(password, candidate), "损坏 / 越界的哈希串必须判为不匹配：$candidate")
        }
    }

    @Test
    fun tampering_with_salt_or_derived_key_breaks_the_match() {
        val encoded = hasher.hash(password)
        val parts = encoded.split("\$")

        val key = Base64.getDecoder().decode(parts[3])
        val flippedKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forgedKey = listOf(
            parts[0],
            parts[1],
            parts[2],
            Base64.getEncoder().withoutPadding().encodeToString(flippedKey),
        ).joinToString("\$")
        assertFalse(hasher.verify(password, forgedKey), "派生密钥被改必须不匹配")

        val salt = Base64.getDecoder().decode(parts[2])
        val flippedSalt = salt.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forgedSalt = listOf(
            parts[0],
            parts[1],
            Base64.getEncoder().withoutPadding().encodeToString(flippedSalt),
            parts[3],
        ).joinToString("\$")
        assertFalse(hasher.verify(password, forgedSalt), "盐被改必须不匹配")
    }

    @Test
    fun unsafe_cost_parameters_are_rejected_at_construction() {
        assertFailsWith<IllegalArgumentException>("迭代次数低于下限必须构造失败") {
            Pbkdf2PasswordHasher(iterations = Pbkdf2PasswordHasher.MIN_ITERATIONS - 1)
        }
        assertFailsWith<IllegalArgumentException>("盐过短必须构造失败") {
            Pbkdf2PasswordHasher(saltLength = 4)
        }
        assertFailsWith<IllegalArgumentException>("派生密钥过短必须构造失败") {
            Pbkdf2PasswordHasher(keyLength = 8)
        }
        assertFailsWith<IllegalArgumentException>("派生密钥过长必须构造失败") {
            Pbkdf2PasswordHasher(keyLength = 128)
        }
    }

    @Test
    fun hashing_a_blank_password_is_a_programming_error() {
        assertFailsWith<IllegalArgumentException>("空密码不允许生成哈希") { hasher.hash("") }
    }
}
