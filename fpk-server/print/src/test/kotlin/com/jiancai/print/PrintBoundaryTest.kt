/*
 * @file fpk-server/print/src/test/kotlin/com/jiancai/print/PrintBoundaryTest.kt
 * @role 硬边界（S12）的结构化守卫
 * @exports PrintBoundaryTest
 * @depends db-core
 * @calledBy （无）
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.print

import com.jiancai.db.TxContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S12）的结构化守卫：
 * 1. **不判权限**：[PrintPort] 里没有角色 / token / 账号 DTO；
 * 2. **返回 DTO**：出参只能是 [PrintDocumentDto]；
 * 3. **SQL 只在 repository**：生产源码里除 [PrintRepository] 外不得出现 DML；
 * 4. **不碰订单 / 备注表**：订单只经 S08 `OrderPort` 读，备注单只经 [NoteReadPort] 读；
 * 5. **不生成单号**：不得出现单号前缀字面量 / 自行拼接；
 * 6. **表归属**：本模块只拥有 `print_job` 一张表；
 * 7. **客户端 deny 的 S12 侧**：本模块不依赖 Android / HTTP 客户端，打印只发生在服务端。
 */
class PrintBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
    )

    private val allowedReturns = setOf(PrintDocumentDto::class.java)

    @Test
    fun the_print_port_never_receives_a_role_token_or_account_dto() {
        printPortMethods().forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "PrintPort.${method.name} 不得接收角色 / token / 账号 DTO（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "PrintPort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_print_port_only_returns_print_documents() {
        printPortMethods().forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "PrintPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun sql_only_lives_in_the_print_repository() {
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
            setOf("PrintRepository.kt"),
            withSql,
            "DML 只允许出现在 PrintRepository（铁律 10）；发现：$withSql",
        )
    }

    @Test
    fun the_module_never_touches_order_or_note_tables() {
        val forbidden = listOf("order_header", "order_line", "diff_note", "DELETE FROM", "UPDATE print_job")

        mainSources().forEach { source ->
            val text = source.readText()
            forbidden.forEach { keyword ->
                assertFalse(
                    text.contains(keyword),
                    "${source.name} 不得出现 $keyword：订单只经 OrderPort 读，备注单只经 NoteReadPort 读，" +
                        "打印台账只增不删",
                )
            }
        }
    }

    @Test
    fun the_module_never_composes_a_serial_number_itself() {
        val prefixLiteral = Regex(""""PS"|"PT"|"ZS"|"ZT"|"JS"|"BZ"""")
        val manualConcat = Regex("""\bprefix\s*\+""")

        mainSources().forEach { source ->
            val text = source.readText()
            assertFalse(
                prefixLiteral.containsMatchIn(text),
                "${source.name} 不得写单号前缀字面量（铁律 8：单号只来自 S07 / S08）",
            )
            assertFalse(
                manualConcat.containsMatchIn(text),
                "${source.name} 不得自行拼接单号（铁律 8）",
            )
            assertFalse(
                text.contains("SerialType"),
                "${source.name} 不得调用取号 / 前缀表（铁律 8：打印只接收单号）",
            )
        }
    }

    @Test
    fun the_module_owns_exactly_one_business_table() {
        printOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("print_job"), tables, "S12 只允许拥有 print_job 一张表（铁律 3）")
        }
    }

    @Test
    fun the_module_has_no_client_side_print_dependency() {
        val build = File("build.gradle.kts").readText()
        listOf("android", "okhttp", "retrofit").forEach { keyword ->
            assertFalse(
                build.contains(keyword, ignoreCase = true),
                "S12 不得依赖客户端打印 / 网络栈（命中 $keyword）；客户端只展示打印状态",
            )
        }

        val forbidden = listOf("android.", "androidx.", "okhttp", "HttpURLConnection", "java.net.Socket")
        mainSources().forEach { source ->
            val text = source.readText()
            forbidden.forEach { keyword ->
                assertFalse(
                    text.contains(keyword),
                    "${source.name} 不得包含客户端打印 / 网络代码（命中 $keyword）；打印只在服务端（铁律 6）",
                )
            }
        }
    }

    /** Kotlin 默认参数的 `render$default` 是合成方法，参数里带 int / Object，需排除。 */
    private fun printPortMethods() =
        PrintPort::class.java.declaredMethods.filterNot { method ->
            method.isSynthetic || method.name.contains('$')
        }

    private fun mainSources(): List<File> =
        File("src/main/kotlin/com/jiancai/print")
            .listFiles { file -> file.extension == "kt" }
            ?.toList()
            .orEmpty()
}
