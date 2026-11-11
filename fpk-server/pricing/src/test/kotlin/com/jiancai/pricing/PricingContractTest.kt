/*
 * @file fpk-server/pricing/src/test/kotlin/com/jiancai/pricing/PricingContractTest.kt
 * @role 契约测试（S06）：本模块的线格式必须与 `shared` 的唯一真源一致。
 * @exports PricingContractTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.pricing

import com.jiancai.shared.ContractJson
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.PriceDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 契约测试（S06）：本模块的线格式必须与 `shared` 的唯一真源一致。
 *
 * 守住三点：
 * - 定价出参 / 改价入参 / 留痕的字段名是 snake_case，金额是十进制字符串；
 * - `locked` 由服务端布尔值直接上线，客户端不自行比较日期；
 * - 未知字段前向兼容（服务端新增字段不破坏旧客户端）。
 */
class PricingContractTest {

    @Test
    fun price_dto_roundtrip_keeps_the_shared_wire_shape() {
        val price = PriceDto(
            id = 1L,
            customerId = 2L,
            categoryId = 3L,
            batchTag = "A",
            unitPrice = Money.ofPlain("12.30"),
            effectiveDate = TODAY,
            locked = false,
            updatedAt = NOW_TIMESTAMP,
            updatedBy = 7L,
        )

        val encoded = ContractJson.json.encodeToString(price)

        assertTrue(encoded.contains("\"customer_id\":2"), encoded)
        assertTrue(encoded.contains("\"batch_tag\":\"A\""), encoded)
        assertTrue(encoded.contains("\"unit_price\":\"12.30\""), encoded)
        assertTrue(encoded.contains("\"effective_date\":\"$TODAY\""), encoded)
        assertTrue(encoded.contains("\"locked\":false"), encoded)
        assertFalse(encoded.contains("\"locked\":\"false\""), "locked 是布尔值，不是字符串")
        assertEquals(price, ContractJson.json.decodeFromString<PriceDto>(encoded))
    }

    @Test
    fun change_price_cmd_decodes_from_the_shared_contract_and_ignores_unknown_fields() {
        val cmd = ContractJson.json.decodeFromString<ChangePriceCmd>(
            """{"customer_id":1,"category_id":2,"batch_tag":"A","new_price":"12.30","reason":"调价","future_field":true}""",
        )

        assertEquals(1L, cmd.customerId)
        assertEquals(2L, cmd.categoryId)
        assertEquals("A", cmd.batchTag)
        assertEquals(Money.ofPlain("12.30"), cmd.newPrice)
        assertEquals("调价", cmd.reason)
    }

    @Test
    fun price_change_log_roundtrip_keeps_money_as_decimal_strings() {
        val log = PriceChangeLog(
            id = 5L,
            customerId = 2L,
            categoryId = 3L,
            batchTag = "A",
            oldPrice = Money.ofPlain("12.30"),
            newPrice = Money.ofPlain("13.00"),
            adminId = 7L,
            reason = "补涨",
            changedAt = NOW_TIMESTAMP,
        )

        val encoded = ContractJson.json.encodeToString(log)

        assertTrue(encoded.contains("\"old_price\":\"12.30\""), encoded)
        assertTrue(encoded.contains("\"new_price\":\"13.00\""), encoded)
        assertTrue(encoded.contains("\"admin_id\":7"), encoded)
        assertEquals(log, ContractJson.json.decodeFromString<PriceChangeLog>(encoded))
    }
}
