package com.jiancai.shared

import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ContractInfoDto
import com.jiancai.shared.dto.DEFAULT_PAGE_SIZE
import com.jiancai.shared.dto.LineDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.enums.DocType
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.enums.Role
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.errors.toErrorCode
import com.jiancai.shared.errors.toResponse
import com.jiancai.shared.serial.SerialType
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 契约层自检：保证「唯一真源」在 CI 中真的成立，而不是靠人工约定。
 *
 * 覆盖点：版本镜像一致、金额线格式、DTO 往返、未知字段前向兼容、
 * 单号前缀唯一且覆盖全部单据、错误码结构与映射、枚举线值稳定性。
 */
class ContractTest {

    @Test
    fun contract_version_matches_mirror_file() {
        // Gradle Test 的默认工作目录是模块目录（shared/）；若根工程改动 workingDir 需同步调整。
        val mirror = File("contract.version")
        assertTrue(mirror.exists(), "shared/contract.version 缺失，无法核对契约版本")
        assertEquals(CONTRACT_VERSION, mirror.readText().trim(), "Contract.kt 与 contract.version 不一致，请同步版本号")
        assertTrue(
            Regex("""^\d+\.\d+\.\d+$""").matches(CONTRACT_VERSION),
            "contractVersion 必须是 semver（当前：$CONTRACT_VERSION）",
        )
    }

    @Test
    fun money_serializes_as_decimal_string() {
        assertEquals("\"12.50\"", ContractJson.json.encodeToString(Money.ofPlain("12.5")))
        assertEquals("0.00", Money.ofPlain("-0.0").amount)
        assertEquals("7.50", Money.ofPlain(" 007.5 ").amount)
        assertEquals("-5.00", Money.ofPlain("-5").amount)
        assertEquals("123.45", Money.ofPlain("123.45").display())

        // 反序列化同样归一化（"12.5" → "12.50"），非法金额在解码阶段即被拒绝。
        assertEquals(Money.ofPlain("12.50"), ContractJson.json.decodeFromString<Money>("\"12.50\""))
        assertEquals("12.50", ContractJson.json.decodeFromString<Money>("\"12.5\"").amount)
        assertFailsWith<IllegalArgumentException> { ContractJson.json.decodeFromString<Money>("\"12.345\"") }
        assertFailsWith<IllegalArgumentException> { ContractJson.json.decodeFromString<Money>("\"abc\"") }

        assertFailsWith<IllegalArgumentException> { Money.ofPlain("12.345") }
        assertFailsWith<IllegalArgumentException> { Money.ofPlain("abc") }
        assertFailsWith<IllegalArgumentException> { Money.ofPlain("") }
    }

    @Test
    fun order_dto_roundtrip_keeps_wire_shape() {
        val order = OrderDto(
            orderNo = "PS202611100001",
            orderType = OrderType.PURCHASE,
            status = OrderStatus.CREATED,
            customerId = 1L,
            customerName = "张三",
            customerPhone = "13800000000",
            customerAddress = "建材市场 1 号",
            orderDate = "2026-11-10",
            lines = listOf(
                LineDto(
                    seq = 1,
                    categoryId = 2L,
                    categoryName = "瓷砖",
                    model = "800×800",
                    batchTag = "20261110",
                    unit = PricingUnit.PIECE,
                    quantity = "100",
                    unitPrice = Money.ofPlain("12.50"),
                    amount = Money.ofPlain("1250.00"),
                ),
            ),
            totalAmount = Money.ofPlain("1250.00"),
            receivableAmount = Money.ofPlain("1250.00"),
            receivedAmount = Money.ofPlain("0.00"),
            version = 1L,
            createdAt = "2026-11-10T15:30:00+08:00",
            createdBy = 9L,
        )

        val encoded = ContractJson.json.encodeToString(order)
        assertEquals(order, ContractJson.json.decodeFromString<OrderDto>(encoded))

        // 关键线格式：字段名 snake_case、金额是字符串、单号保持服务端产出。
        assertTrue(encoded.contains("\"order_no\":\"PS202611100001\""), encoded)
        assertTrue(encoded.contains("\"unit_price\":\"12.50\""), encoded)
        assertTrue(encoded.contains("\"order_type\":\"purchase\""), encoded)
    }

    @Test
    fun unknown_fields_and_missing_defaults_are_forward_compatible() {
        val decoded = ContractJson.json.decodeFromString<ContractInfoDto>(
            """{"server_time":"2026-11-10T15:30:00+08:00","future_field":true}""",
        )
        assertEquals(CONTRACT_VERSION, decoded.contractVersion)
        assertNull(decoded.merchantName)
    }

    @Test
    fun category_dto_carries_packing_spec() {
        val category = CategoryDto(
            id = 2L,
            categoryName = "瓷砖",
            model = "800×800",
            unit = PricingUnit.PIECE,
            batchTagRequired = true,
            batchTags = listOf("20261110"),
            packingSpec = PackingSpec(
                baseUnit = PricingUnit.PIECE,
                packageUnit = PricingUnit.BOX,
                unitsPerPackage = 4,
                specText = "800×800",
            ),
            createdAt = "2026-11-10T15:30:00+08:00",
        )

        val decoded = ContractJson.json.decodeFromString<CategoryDto>(
            ContractJson.json.encodeToString(category),
        )
        assertEquals(category, decoded)
        assertEquals(4, decoded.packingSpec?.unitsPerPackage)
    }

    @Test
    fun order_type_covers_four_entry_pages_and_maps_to_serial_prefix() {
        // C06：4 类录入页共用 OrderFormComponent，只传 OrderType。
        assertEquals(4, OrderType.values().size)
        OrderType.values().forEach { type ->
            val serialType = SerialType.of(type.docType)
            assertEquals(type.docType, serialType.docType, "订单类型 ${type.name} 缺少单号前缀映射")
        }
    }

    @Test
    fun serial_prefixes_are_unique_and_cover_all_doc_types() {
        val prefixes = SerialType.values().map { it.prefix }
        assertEquals(prefixes.size, prefixes.toSet().size, "单号前缀重复：$prefixes")
        assertEquals(DocType.values().size, SerialType.values().size, "单据类型与单号前缀未一一对应")

        DocType.values().forEach { docType ->
            assertEquals(docType, SerialType.of(docType).docType)
        }
        SerialType.values().forEach { type ->
            assertTrue(
                Regex("""^[A-Z]{2}$""").matches(type.prefix),
                "前缀应为两位大写字母：${type.prefix}",
            )
        }
        assertEquals("PS", SerialType.PURCHASE.prefix)
        assertEquals("JS", SerialType.SETTLEMENT.prefix)
    }

    @Test
    fun error_codes_are_well_formed_and_map_to_http() {
        assertTrue(ErrorCode.values().size >= 30, "错误码基线不完整")
        val names = mutableSetOf<String>()
        ErrorCode.values().forEach { code ->
            assertTrue(names.add(code.name), "错误码重复：${code.name}")
            assertTrue(Regex("""^E\d{3,4}$""").matches(code.name), "错误码命名非法：${code.name}")
            assertTrue(code.httpStatus in 400..599, "HTTP 状态非法：${code.name}")
            assertTrue(code.message.isNotBlank(), "缺少默认文案：${code.name}")
        }

        assertEquals(401, ErrorCode.E401.httpStatus)
        assertEquals("E404", ErrorCode.E404.toResponse().code)
        assertEquals(ErrorCode.E409, ErrorCode.fromWire("E409"))
        assertNull(ErrorCode.fromWire("E9999"), "未知错误码应返回 null，由客户端兜底")
        assertEquals(ErrorCode.E409, ErrorCode.E409.toResponse().toErrorCode())
    }

    @Test
    fun order_type_and_doc_type_share_the_same_wire_value() {
        // DocType 与 OrderType 的 4 个线值必须一致，否则同一单据在两端会出现两种写法。
        OrderType.values().forEach { type ->
            assertEquals(
                ContractJson.json.encodeToString(type.docType),
                ContractJson.json.encodeToString(type),
                "OrderType.${type.name} 与 DocType.${type.docType.name} 线值漂移",
            )
        }
    }

    @Test
    fun page_dto_roundtrip() {
        val page = PageDto(
            items = listOf(Money.ofPlain("1.00"), Money.ofPlain("2.50")),
            total = 2L,
            pageSize = DEFAULT_PAGE_SIZE,
        )
        val decoded = ContractJson.json.decodeFromString<PageDto<Money>>(
            ContractJson.json.encodeToString(page),
        )
        assertEquals(page, decoded)
    }

    @Test
    fun error_code_wire_values_are_frozen() {
        // 错误码线值就是枚举名，改名等于改协议；这里冻结几个关键码。
        assertEquals("\"E401\"", ContractJson.json.encodeToString(ErrorCode.E401))
        assertEquals("\"E403\"", ContractJson.json.encodeToString(ErrorCode.E403))
        assertEquals("\"E404\"", ContractJson.json.encodeToString(ErrorCode.E404))
        assertEquals("\"E409\"", ContractJson.json.encodeToString(ErrorCode.E409))
        assertEquals("\"E422\"", ContractJson.json.encodeToString(ErrorCode.E422))
    }

    @Test
    fun enum_wire_values_are_stable() {
        // 线值是两端约定的对外格式，改名即破坏契约（需要升主版本号）。
        assertEquals("\"purchase\"", ContractJson.json.encodeToString(OrderType.PURCHASE))
        assertEquals("\"purchase_return\"", ContractJson.json.encodeToString(OrderType.PURCHASE_RETURN))
        assertEquals("\"created\"", ContractJson.json.encodeToString(OrderStatus.CREATED))
        assertEquals("\"settled\"", ContractJson.json.encodeToString(OrderStatus.SETTLED))
        assertEquals("\"note\"", ContractJson.json.encodeToString(DocType.NOTE))
        assertEquals("\"square_meter\"", ContractJson.json.encodeToString(PricingUnit.SQUARE_METER))
        assertEquals("\"receipt\"", ContractJson.json.encodeToString(SettleDirection.RECEIPT))
        assertEquals("\"submitted\"", ContractJson.json.encodeToString(SettleStatus.SUBMITTED))
        assertEquals("\"price\"", ContractJson.json.encodeToString(DiffType.PRICE))
        assertEquals("\"admin\"", ContractJson.json.encodeToString(Role.ADMIN))
        assertEquals("\"ps\"", ContractJson.json.encodeToString(SerialType.PURCHASE))
    }
}
