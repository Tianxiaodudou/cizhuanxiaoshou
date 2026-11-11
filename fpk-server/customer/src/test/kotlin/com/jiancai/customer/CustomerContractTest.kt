/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   30 行  类 CustomerContractTest
 * 第   33 行  函数 customer_dto_roundtrip_keeps_the_shared_wire_shape
 * 第   51 行  函数 create_customer_cmd_decodes_from_the_shared_contract_and_ignores_unknown_fields
 * 第   68 行  函数 an_empty_address_is_encoded_as_null_not_an_empty_string
 */

package com.jiancai.customer

import com.jiancai.shared.ContractJson
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CustomerDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 契约测试（S04）：本模块读写的线格式必须与 `shared` 的唯一真源一致。
 *
 * 守住三点：
 * - 客户出参 / 建档入参的字段名是 snake_case，客户端按 `CustomerDto` 解析即可；
 * - 时间戳是字符串、可空字段（`address` / `updated_at`）往返不丢；
 * - 未知字段前向兼容（服务端新增字段不破坏旧客户端）。
 */
class CustomerContractTest {

    @Test
    fun customer_dto_roundtrip_keeps_the_shared_wire_shape() {
        val customer = CustomerDto(
            id = 7L,
            name = "张三",
            phone = "13800000000",
            address = "建材市场 1 号",
            createdAt = CREATED_AT,
            updatedAt = null,
        )

        val encoded = ContractJson.json.encodeToString(customer)

        assertTrue(encoded.contains("\"created_at\":\"$CREATED_AT\""), encoded)
        assertTrue(encoded.contains("\"updated_at\":null"), encoded)
        assertEquals(customer, ContractJson.json.decodeFromString<CustomerDto>(encoded))
    }

    @Test
    fun create_customer_cmd_decodes_from_the_shared_contract_and_ignores_unknown_fields() {
        val cmd = ContractJson.json.decodeFromString<CreateCustomerCmd>(
            """{"name":"李四","phone":"13900000000","address":null,"future_field":true}""",
        )

        assertEquals("李四", cmd.name)
        assertEquals("13900000000", cmd.phone)
        assertNull(cmd.address)

        // address 可省略（契约默认 null），旧客户端不传也不会解码失败。
        val minimal = ContractJson.json.decodeFromString<CreateCustomerCmd>(
            """{"name":"王五","phone":"13700000000"}""",
        )
        assertNull(minimal.address)
    }

    @Test
    fun an_empty_address_is_encoded_as_null_not_an_empty_string() {
        val customer = CustomerDto(
            id = 1L,
            name = "张三",
            phone = "13800000000",
            address = null,
            createdAt = CREATED_AT,
        )

        val encoded = ContractJson.json.encodeToString(customer)
        assertTrue(encoded.contains("\"address\":null"), encoded)
        assertNull(ContractJson.json.decodeFromString<CustomerDto>(encoded).address)
    }
}
