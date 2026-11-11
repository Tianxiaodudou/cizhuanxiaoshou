/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   39 行  类 SettleBoundaryTest
 * 第   56 行  函数 the_settle_port_never_receives_a_role_token_or_account_dto
 * 第   77 行  函数 the_settle_port_only_returns_dtos
 * 第   87 行  函数 sql_only_lives_in_the_settle_repository
 * 第  112 行  函数 the_module_never_writes_the_order_tables_or_the_received_amount
 * 第  144 行  函数 the_module_never_composes_a_serial_number_itself
 * 第  169 行  函数 the_module_contains_no_print_rendering
 * 第  183 行  函数 the_module_owns_exactly_one_business_table
 * 第  191 行  函数 mainSources
 */

package com.jiancai.settlement

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S11）的结构化守卫：
 * 1. **不判权限**：[SettlePort] 里没有角色 / token / 账号 DTO；
 * 2. **返回 DTO**：出参只能是 [SettleDto] / [ReceivableDto]；
 * 3. **SQL 只在 repository**：生产源码里除 [SettleRepository] 外不得出现 DML；
 * 4. **不碰订单表 / 不改已收**：不得读写 `order_header` / `order_line`，不得写 `received_amount`，
 *    订单侧只经 `OrderPort.getRecent` / `getReceivable` 读取，不调用任何订单写方法；
 * 5. **单号不自行拼接**：源码里不得出现结算单前缀字面量，只能走 `SerialType.of`；
 * 6. **打印禁止**：本模块不做任何打印渲染（S12 独占）；
 * 7. **表归属**：本模块只拥有 `settlement` 一张表。
 */
class SettleBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        String::class.java,
        SubmitSettleCmd::class.java,
        AuditSettleCmd::class.java,
    )

    private val allowedReturns = setOf(
        SettleDto::class.java,
        ReceivableDto::class.java,
    )

    @Test
    fun the_settle_port_never_receives_a_role_token_or_account_dto() {
        SettlePort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "SettlePort.${method.name} 不得接收角色 / token / 账号 DTO（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "SettlePort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_settle_port_only_returns_dtos() {
        SettlePort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "SettlePort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun sql_only_lives_in_the_settle_repository() {
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
            setOf("SettleRepository.kt"),
            withSql,
            "DML 只允许出现在 SettleRepository（铁律 10）；发现：$withSql",
        )
    }

    @Test
    fun the_module_never_writes_the_order_tables_or_the_received_amount() {
        val orderTableDml = Regex(
            """\b(?:FROM|INTO|UPDATE|JOIN)\s+order_(?:header|line)\b""",
            RegexOption.IGNORE_CASE,
        )
        val receivedWrite = Regex("""received_amount\s*=""")

        mainSources().forEach { source ->
            val text = source.readText()
            assertFalse(
                orderTableDml.containsMatchIn(text),
                "${source.name} 不得读写订单表：订单只经 OrderPort 读取（铁律 3 / 10）",
            )
            assertFalse(
                receivedWrite.containsMatchIn(text),
                "${source.name} 不得改写订单已收：已收永远由 S11 的审核记录派生",
            )
            assertFalse(
                text.contains("DELETE FROM"),
                "${source.name} 不得删除记录：结算只追加 / 审核，不做物理删除",
            )
        }

        val service = File("src/main/kotlin/com/jiancai/settlement/SettleService.kt").readText()
        assertTrue(service.contains("orders.getRecent("), "订单快照只经 OrderPort.getRecent 读取")
        assertTrue(service.contains("orders.getReceivable("), "应收必须在提交 / 审核时从 S08 重新读取")
        listOf("orders.create(", "orders.update(", "orders.today(").forEach { writeCall ->
            assertFalse(service.contains(writeCall), "结算不得调用订单写方法：$writeCall")
        }
    }

    @Test
    fun the_module_never_composes_a_serial_number_itself() {
        val sources = mainSources()
        val prefixLiteral = Regex(""""JS"|"PS"|"PT"|"ZS"|"ZT"|"BZ"""")
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

        val service = File("src/main/kotlin/com/jiancai/settlement/SettleService.kt").readText()
        assertTrue(
            service.contains("SerialType.of("),
            "结算单号必须经 SerialType.of(docType) 取号，而不是自拼前缀",
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
    fun the_module_owns_exactly_one_business_table() {
        settlementOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("settlement"), tables, "S11 只允许拥有 settlement 一张表（铁律 3）")
        }
    }

    private fun mainSources(): List<File> =
        File("src/main/kotlin/com/jiancai/settlement")
            .listFiles { file -> file.extension == "kt" }
            ?.toList()
            .orEmpty()
}
