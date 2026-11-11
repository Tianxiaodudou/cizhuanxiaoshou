/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   37 行  类 NoteBoundaryTest
 * 第   54 行  函数 the_note_port_never_receives_a_role_token_or_account_dto
 * 第   75 行  函数 the_note_port_only_returns_dtos
 * 第   85 行  函数 sql_only_lives_in_the_note_repository
 * 第  110 行  函数 the_module_never_composes_a_serial_number_itself
 * 第  135 行  函数 the_module_contains_no_print_rendering
 * 第  149 行  函数 the_module_never_touches_the_order_tables_or_deletes_notes
 * 第  163 行  函数 the_module_owns_exactly_one_business_table
 * 第  171 行  函数 mainSources
 */

package com.jiancai.note

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.VoidNoteCmd
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S10）的结构化守卫：
 * 1. **不判权限**：[NotePort] 里没有角色 / token / 账号 DTO；
 * 2. **返回 DTO**：出参只能是 [NoteDto]；
 * 3. **SQL 只在 repository**：生产源码里除 [NoteRepository] 外不得出现 DML；
 * 4. **单号不自行拼接**：源码里不得出现备注单前缀字面量，只能走 `SerialType.of`；
 * 5. **打印禁止**：本模块不做任何打印渲染（S12 独占）；
 * 6. **不碰原单**：生产源码不得出现任何订单表名，也不得有 DELETE（备注单只作废不删除）；
 * 7. **表归属**：本模块只拥有 `diff_note` 一张表。
 */
class NoteBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        String::class.java,
        CreateNoteCmd::class.java,
        VoidNoteCmd::class.java,
    )

    private val allowedReturns = setOf(
        NoteDto::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_note_port_never_receives_a_role_token_or_account_dto() {
        NotePort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "NotePort.${method.name} 不得接收角色 / token / 账号 DTO（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "NotePort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_note_port_only_returns_dtos() {
        NotePort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "NotePort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun sql_only_lives_in_the_note_repository() {
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
            setOf("NoteRepository.kt"),
            withSql,
            "DML 只允许出现在 NoteRepository（铁律 10）；发现：$withSql",
        )
    }

    @Test
    fun the_module_never_composes_a_serial_number_itself() {
        val sources = mainSources()
        val prefixLiteral = Regex(""""BZ"|"PS"|"PT"|"ZS"|"ZT"|"JS"""")
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

        val service = File("src/main/kotlin/com/jiancai/note/NoteService.kt").readText()
        assertTrue(
            service.contains("SerialType.of("),
            "备注单号必须经 SerialType.of(docType) 取号，而不是自拼前缀",
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
    fun the_module_never_touches_the_order_tables_or_deletes_notes() {
        val forbidden = listOf("order_header", "order_line", "DELETE FROM")
        mainSources().forEach { source ->
            val text = source.readText()
            forbidden.forEach { keyword ->
                assertFalse(
                    text.contains(keyword),
                    "${source.name} 不得出现 $keyword：原单只经 OrderPort 读取，备注单只作废不删除",
                )
            }
        }
    }

    @Test
    fun the_module_owns_exactly_one_business_table() {
        noteOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(listOf("diff_note"), tables, "S10 只允许拥有 diff_note 一张表（铁律 3）")
        }
    }

    private fun mainSources(): List<File> =
        File("src/main/kotlin/com/jiancai/note")
            .listFiles { file -> file.extension == "kt" }
            ?.toList()
            .orEmpty()
}
