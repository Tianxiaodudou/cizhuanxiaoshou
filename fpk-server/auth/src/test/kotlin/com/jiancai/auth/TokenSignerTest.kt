/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   35 行  类 TokenSignerTest
 * 第   46 行  函数 issued_token_round_trips_with_the_expected_layout_and_claims
 * 第   59 行  函数 token_is_opaque_url_safe_text_and_leaks_no_raw_fields
 * 第   70 行  函数 tampering_with_payload_or_mac_is_rejected
 * 第   82 行  函数 expired_tokens_are_rejected_at_and_after_the_expiry_instant
 * 第   91 行  函数 tokens_issued_in_the_future_beyond_the_clock_skew_allowance_are_rejected
 * 第  100 行  函数 a_token_signed_with_another_secret_is_rejected
 * 第  108 行  函数 malformed_or_client_forged_tokens_are_rejected
 * 第  127 行  函数 version_downgrade_is_rejected_even_with_a_valid_mac
 * 第  135 行  函数 oversized_tokens_are_rejected_before_parsing
 * 第  142 行  函数 short_secrets_are_rejected_at_construction_but_32_bytes_is_accepted
 * 第  149 行  函数 flipFirstChar
 */

package com.jiancai.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * token 签发 / 校验硬边界（S02）：线格式、不透明性、验签、过期、时钟偏移、超长输入、密钥下限。
 *
 * `AuthPortTest` 通过 [AuthService] 间接覆盖了一部分；这里直接盯住 [TokenSigner]，
 * 保证「客户端不解析、不自签」「无效 token 一律返回 null 由上层转 E401」这两条承诺不被改坏。
 */
class TokenSignerTest {

    private val signer = TokenSigner("0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8))

    private val issuedAt: Instant = Instant.parse("2026-11-10T02:00:00Z")

    private val expiresAt: Instant = issuedAt.plusSeconds(3_600)

    private val now: Instant = Instant.parse("2026-11-10T02:30:00Z")

    @Test
    fun issued_token_round_trips_with_the_expected_layout_and_claims() {
        val token = signer.issue(42, issuedAt, expiresAt)

        assertEquals(3, token.split(".").size, "线格式：v1.<payload>.<mac>")
        assertTrue(token.startsWith("v1."))

        val claims = assertNotNull(signer.verify(token, now))
        assertEquals(42L, claims.accountId)
        assertEquals(issuedAt, claims.issuedAt)
        assertEquals(expiresAt, claims.expiresAt)
    }

    @Test
    fun token_is_opaque_url_safe_text_and_leaks_no_raw_fields() {
        val token = signer.issue(42, issuedAt, expiresAt)

        assertFalse(token.contains("="), "base64url 不带填充")
        assertFalse(token.contains("+"))
        assertFalse(token.contains("/"))
        assertFalse(token.contains(":"), "payload 必须整体 base64，不能把 accountId:时间戳 直接暴露")
        assertFalse(token.contains("v1.42"))
    }

    @Test
    fun tampering_with_payload_or_mac_is_rejected() {
        val token = signer.issue(42, issuedAt, expiresAt)
        val parts = token.split(".")

        val flippedPayload = flipFirstChar(parts[1])
        assertNull(signer.verify("${parts[0]}.$flippedPayload.${parts[2]}", now), "改 payload 必须验签失败")

        val flippedMac = flipFirstChar(parts[2])
        assertNull(signer.verify("${parts[0]}.${parts[1]}.$flippedMac", now), "改 MAC 必须验签失败")
    }

    @Test
    fun expired_tokens_are_rejected_at_and_after_the_expiry_instant() {
        val token = signer.issue(42, issuedAt, expiresAt)

        assertNotNull(signer.verify(token, expiresAt.minusSeconds(1)), "到期前仍有效")
        assertNull(signer.verify(token, expiresAt), "到期瞬间即失效")
        assertNull(signer.verify(token, expiresAt.plusSeconds(1)))
    }

    @Test
    fun tokens_issued_in_the_future_beyond_the_clock_skew_allowance_are_rejected() {
        val atLimit = signer.issue(42, now.plusSeconds(60), now.plusSeconds(3_600))
        assertNotNull(signer.verify(atLimit, now), "60 秒内的超前容忍")

        val beyondLimit = signer.issue(42, now.plusSeconds(61), now.plusSeconds(3_600))
        assertNull(signer.verify(beyondLimit, now), "超前 61 秒必须拒绝（挡时钟回拨 / 伪造）")
    }

    @Test
    fun a_token_signed_with_another_secret_is_rejected() {
        val foreign = TokenSigner("fedcba9876543210fedcba9876543210".toByteArray(Charsets.UTF_8))
        val alienToken = foreign.issue(42, issuedAt, expiresAt)

        assertNull(signer.verify(alienToken, now))
    }

    @Test
    fun malformed_or_client_forged_tokens_are_rejected() {
        val candidates = listOf(
            "",
            "v1",
            "v1.abc",
            "v1.abc.def",
            "v1.abc.def.ghi",
            "v0.abc.def",
            "Bearer v1.abc.def",
            "not a token",
            "v1.!!!!.!!!!",
        )

        candidates.forEach { candidate ->
            assertNull(signer.verify(candidate, now), "应拒绝：$candidate")
        }
    }

    @Test
    fun version_downgrade_is_rejected_even_with_a_valid_mac() {
        val token = signer.issue(42, issuedAt, expiresAt)
        val downgraded = "v0" + token.removePrefix("v1")

        assertNull(signer.verify(downgraded, now), "签名覆盖方案前缀，降级版本必须失败")
    }

    @Test
    fun oversized_tokens_are_rejected_before_parsing() {
        val huge = signer.issue(42, issuedAt, expiresAt) + "A".repeat(4_096)

        assertNull(signer.verify(huge, now))
    }

    @Test
    fun short_secrets_are_rejected_at_construction_but_32_bytes_is_accepted() {
        assertFailsWith<IllegalArgumentException>("密钥短于 32 字节必须构造失败") {
            TokenSigner(ByteArray(31))
        }
        assertNotNull(TokenSigner(ByteArray(32)), "32 字节（256 位）是允许的下限")
    }

    private fun flipFirstChar(text: String): String =
        (if (text.first() == 'A') 'B' else 'A') + text.drop(1)
}
