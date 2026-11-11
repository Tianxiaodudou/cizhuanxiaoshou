/*
 * @file fpk-server/order/src/test/kotlin/com/jiancai/order/OrderBoundaryTest.kt
 * @role 硬边界（S08）的结构化守卫
 * @exports OrderBoundaryTest
 * @depends db-core, shared
 * @calledBy （无）
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.order

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.UpdateOrderCmd
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S08）的结构化守卫：
 * 1. **不判权限**：[OrderPort] 里没有角色 / token / 账号 DTO；
 * 2. **返回 DTO**：出参只能是 [OrderDto] / [PageDto] / `List<...>` / [Money] / Unit；
 * 3. **SQL 只在 repository**：生产源码里除 [OrderRepository] 外不得出现 DML；
 * 4. **单号不自行拼接**：源码里不得出现 PS / PT / ZS / ZT 前缀字面量，只能走 `SerialType.of`；
 * 5. **打印禁止**：本模块不做任何打印渲染（S12 独占）；
 * 6. **表归属**：本模块只拥有 `order_header` / `order_line` 两张表。
 */
class OrderBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        String::class.java,
        CreateOrderCmd::class.java,
        UpdateOrderCmd::class.java,
        OrderQuery::class.java,
    )

    private val allowedReturns = setOf(
        OrderDto::class.java,
        PageDto::class.java,
        Money::class.java,
        String::class.java,
        java.util.List::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_order_port_never_receives_a_role_token_or_account_dto() {
        OrderPort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "OrderPort.${method.name} 不得接收角色 / token / 账号 DTO（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "OrderPort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_order_port_only_returns_dtos() {
        OrderPort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "OrderPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun sql_only_lives_in_the_order_repository() {
        val sources = mainSources()
        assertTrue(sources.isNotEmpty(), "找不到本模块生产源码目录（测试工作目录应为模块目录）")

        val dml = Regex(
            """\b(?:SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET|DELETE\s+FROM\s)""",
            RegexOption.IGNORE_CASE,
        )
        val withSql = sources.filter { source ->
            source.readLines().any { line ->
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") ||
                    trimmed.startsWith("/*")
                !isComment && dml.containsMatchIn(trimmed)
            }
        }.map { it.name }.toSet()

        assertEquals(
            setOf("OrderRepository.kt"),
            withSql,
            "DML 只允许出现在 OrderRepository（铁律 10）；发现：$withSql",
        )
    }

    @Test
    fun the_module_never_composes_a_serial_number_itself() {
        val sources = mainSources()
        val prefixLiteral = Regex(""""PS"|"PT"|"ZS"|"ZT"|"BZ"|"JS"""")
        val manualConcat = Regex("""\bprefix\s*\+""")

        sources.forEach { source ->
            val text = source.readText()
            assertFalse(
                prefixLiteral.containsMatchIn(text),
                "${source.name} 不得写单号前缀字面量（铁律 8：只能经 SerialType.of 取前缀）",
            )
            assertFalse(
                manualConcat.containsMatchIn(text),
                "${source.name} 不得自行拼接单号（铁律 8）",
            )
        }

        val service = File("src/main/kotlin/com/jiancai/order/OrderService.kt").readText()
        assertTrue(
            service.contains("SerialType.of("),
            "建单必须经 SerialType.of(docType) 取号，而不是自拼前缀",
        )
    }

    @Test
    fun the_module_contains_no_print_rendering() {
        val forbidden = listOf("print(", "println(", "打印")
        mainSources().forEach { source ->
            val text = source.readText()
            forbidden.forEach { keyword ->
                assertFalse(
                    text.contains(keyword),
                    "${source.name} 不得包含打印相关逻辑（$keyword）；打印只在 S12 服务端",
                )
            }
        }
    }

    @Test
    fun the_module_owns_exactly_two_business_tables() {
        orderOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(
                listOf("order_header", "order_line"),
                tables,
                "S08 只允许拥有 order_header / order_line 两张表（铁律 3）",
            )
        }
    }

    private fun mainSources(): List<File> =
        File("src/main/kotlin/com/jiancai/order")
            .listFiles { file -> file.extension == "kt" }
            ?.toList()
            .orEmpty()
}
