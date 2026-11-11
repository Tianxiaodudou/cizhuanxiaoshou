/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   43 行  类 ReturnCheckBoundaryTest
 * 第   57 行  函数 the_port_never_receives_a_role_token_or_account_dto
 * 第   78 行  函数 the_port_only_returns_dtos_defined_in_shared
 * 第   95 行  函数 the_module_defines_no_wire_dto_of_its_own
 * 第  106 行  函数 the_module_has_no_table_no_migration_and_no_sql
 * 第  135 行  函数 only_the_order_port_is_used_and_only_its_read_methods
 * 第  152 行  函数 the_module_depends_on_no_module_other_than_shared_db_core_and_order
 * 第  176 行  函数 the_module_never_composes_serial_numbers_or_prints
 * 第  196 行  函数 quantities_and_batch_tags_come_from_the_shared_caliber
 * 第  218 行  函数 the_available_batch_dto_carries_only_server_produced_quantities
 * 第  235 行  函数 the_error_codes_used_by_this_module_live_in_the_contract_bands
 * 第  253 行  函数 the_port_exposes_exactly_the_two_use_cases
 * 第  265 行  函数 mainSources
 */

package com.jiancai.returncheck

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S09）的结构化守卫：
 * 1. **只读**：本模块没有表、没有迁移、没有 DML / DDL，源码里连 `Connection` 都不出现；
 * 2. **只经 S08 的 Port 读**：只 import `com.jiancai.order.OrderPort`，不碰 S08 的仓储 / 表名；
 * 3. **不改订单数据**：源码里对 `orders` 只调 `getRecent` / `getReceivable`，不调用建单 / 改单 / 取号；
 * 4. **不判权限**：[ReturnCheckPort] 里没有角色 / token / 账号类型；
 * 5. **返回 DTO**：出参是 `List<ReturnableBatchDto>` / [ReturnCheckResultDto]，不返回 Entity；
 * 6. **线格式只在 shared**：1.1.0 起本模块不得再定义 `@Serializable` / `@SerialName`（铁律 9）；
 * 7. **不生成单号、不打印**：没有单号前缀字面量、没有打印相关逻辑（S12 独占）。
 */
class ReturnCheckBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        OrderDto::class.java,
        java.util.List::class.java,
    )

    private val allowedReturns = setOf(
        java.util.List::class.java,
        ReturnCheckResultDto::class.java,
    )

    @Test
    fun the_port_never_receives_a_role_token_or_account_dto() {
        ReturnCheckPort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "ReturnCheckPort.${method.name} 不得接收角色 / token / 账号 DTO（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "ReturnCheckPort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_port_only_returns_dtos_defined_in_shared() {
        ReturnCheckPort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "ReturnCheckPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
        // 出参 DTO 的包名必须是 com.jiancai.shared.dto（1.1.0 起线格式唯一真源）。
        assertEquals(
            "com.jiancai.shared.dto",
            ReturnableBatchDto::class.java.packageName,
            "可退批次 DTO 必须来自 shared，模块内不得再定义一套",
        )
        assertEquals("com.jiancai.shared.dto", ReturnCheckResultDto::class.java.packageName)
    }

    @Test
    fun the_module_defines_no_wire_dto_of_its_own() {
        mainSources().forEach { source ->
            val text = source.readText()
            assertFalse(
                text.contains("@Serializable") || text.contains("@SerialName"),
                "${source.name} 不得定义线格式：DTO 只在 shared（铁律 9，1.1.0 起 shared 已持有 ReturnCheck*Dto）",
            )
        }
    }

    @Test
    fun the_module_has_no_table_no_migration_and_no_sql() {
        val sources = mainSources()
        assertTrue(sources.isNotEmpty(), "找不到本模块生产源码目录（测试工作目录应为模块目录）")

        val sql = Regex(
            """\b(?:SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET|DELETE\s+FROM\s|CREATE\s+(?:TABLE|INDEX|VIEW)\s)""",
            RegexOption.IGNORE_CASE,
        )
        val offenders = sources.filter { source -> sql.containsMatchIn(source.readText()) }.map { it.name }
        assertEquals(
            emptySet<String>(),
            offenders.toSet(),
            "S09 没有自己的表，生产源码不应出现任何 SQL（铁律 10）：$offenders",
        )

        sources.forEach { source ->
            val text = source.readText()
            assertFalse(
                text.contains("Migration(") || text.contains(": Migration"),
                "${source.name} 不应定义迁移：S09 不落库（可退数量由 S08 的单据派生）",
            )
            assertFalse(
                text.contains("DriverManager") || text.contains("Connection"),
                "${source.name} 不得自开数据库连接（铁律 10）",
            )
        }
    }

    @Test
    fun only_the_order_port_is_used_and_only_its_read_methods() {
        val service = File("src/main/kotlin/com/jiancai/returncheck/ReturnCheckService.kt")
        assertTrue(service.exists(), "找不到 ReturnCheckService.kt")

        val calls = Regex("""orders\.(\w+)\(""")
            .findAll(service.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf("getReceivable", "getRecent"),
            calls,
            "S09 只能通过 OrderPort 读，且只能读（发现：$calls）；建单 / 改单 / 取号归 S08",
        )
    }

    @Test
    fun the_module_depends_on_no_module_other_than_shared_db_core_and_order() {
        val allowed = listOf(
            "java.",
            "kotlin.",
            "kotlinx.",
            "com.jiancai.shared.",
            "com.jiancai.db.",
            "com.jiancai.order.",
            "com.jiancai.returncheck.",
        )
        val importPattern = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)

        mainSources().forEach { source ->
            importPattern.findAll(source.readText()).forEach { match ->
                val imported = match.groupValues[1]
                assertTrue(
                    allowed.any { imported.startsWith(it) },
                    "${source.name} 引入了不允许的模块：$imported（S09 只依赖 shared / db-core / order）",
                )
            }
        }
    }

    @Test
    fun the_module_never_composes_serial_numbers_or_prints() {
        val prefixLiteral = Regex(""""PS"|"PT"|"ZS"|"ZT"|"BZ"|"JS"""")
        val forbidden = listOf("print(", "println(", "打印", "SerialType")

        mainSources().forEach { source ->
            val text = source.readText()
            assertFalse(
                prefixLiteral.containsMatchIn(text),
                "${source.name} 不得写单号前缀字面量（铁律 8：单号只由 S08 经 S07 产出）",
            )
            forbidden.forEach { keyword ->
                assertFalse(
                    text.contains(keyword),
                    "${source.name} 不得包含打印 / 取号逻辑（$keyword）；打印归 S12、取号归 S07 / S08",
                )
            }
        }
    }

    @Test
    fun quantities_and_batch_tags_come_from_the_shared_caliber() {
        val rules = File("src/main/kotlin/com/jiancai/returncheck/ReturnRules.kt").readText()

        assertTrue(
            rules.contains("Quantity.ofPlain("),
            "数量校验必须调 shared 的 Quantity.ofPlain（1.1.0 唯一真源），不得自写正则 / 小数位判断",
        )
        assertTrue(
            rules.contains("BatchTagRules.comparisonKey("),
            "批号比较必须调 shared 的 BatchTagRules，与 S08 的 COLLATE NOCASE 对齐",
        )
        assertFalse(
            rules.contains("lowercase()"),
            "批号大小写折叠只能在 shared 的 BatchTagRules，本模块不得复制",
        )
        assertFalse(
            Regex("""batchTag\s*\.\s*trim\(""").containsMatchIn(rules),
            "批号 trim 只能在 shared 的 BatchTagRules.normalize，本模块不得复制",
        )
    }

    @Test
    fun the_available_batch_dto_carries_only_server_produced_quantities() {
        val dto = ReturnableBatchDto(
            orderNo = "PS202611110001",
            categoryId = 1L,
            categoryName = "水泥",
            model = "P.O 42.5",
            batchTag = "A",
            unit = PricingUnit.TON,
            orderedQuantity = "10",
            returnedQuantity = "4",
            returnableQuantity = "6",
        )
        // 数量是字符串（不是 Double / Float），客户端只展示、零计算（铁律 5）。
        assertEquals("6", dto.returnableQuantity)
    }

    @Test
    fun the_error_codes_used_by_this_module_live_in_the_contract_bands() {
        val s09Codes = listOf(
            ErrorCode.E3001,
            ErrorCode.E3002,
            ErrorCode.E3003,
            ErrorCode.E3004,
            ErrorCode.E3005,
        )
        s09Codes.forEach { code ->
            assertTrue(
                code.name.startsWith("E3"),
                "${code.name} 应属于 S09 的 E3xxx 段位（错误码由 shared 统一分配）",
            )
        }
        assertEquals(413, ErrorCode.E413.httpStatus, "数据量超限用 shared 的 E413，不再借用 E503")
    }

    @Test
    fun the_port_exposes_exactly_the_two_use_cases() {
        val names = ReturnCheckPort::class.java.declaredMethods
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("availableBatches", "validate"),
            names,
            "S09 的 Port 只应有两组能力（可退批次 / 校验）；发现：$names",
        )
    }

    private fun mainSources(): List<File> =
        File("src/main/kotlin/com/jiancai/returncheck")
            .listFiles { file -> file.extension == "kt" }
            ?.toList()
            .orEmpty()
}
