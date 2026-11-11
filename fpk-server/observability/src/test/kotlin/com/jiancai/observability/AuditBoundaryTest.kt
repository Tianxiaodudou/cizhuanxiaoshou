/*
 * @file fpk-server/observability/src/test/kotlin/com/jiancai/observability/AuditBoundaryTest.kt
 * @role 硬边界的结构化守卫（S14）
 * @exports AuditBoundaryTest
 * @depends db-core, shared
 * @calledBy （无）
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.observability

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.SettleDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界的结构化守卫（S14）：
 * 1. **不判权限**：[AuditPort] 里没有角色 / token / 账号 DTO；
 * 2. **返回 DTO**：出参只能是 [AuditRecordDto]；
 * 3. **SQL 只在 repository**，且台账**只追加**（无 UPDATE / DELETE）；
 * 4. **不依赖业务模块**：源码不得 import pricing / settlement / order / note / print / returncheck；
 * 5. **不参与业务决策**：不 import 任何业务 Port，不出现 `E403` 之类的越权判断；
 * 6. **表归属**：本模块只拥有 `audit_log` 一张表。
 */
class AuditBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        AuditEvent::class.java,
        AuditQuery::class.java,
        PriceChangeLog::class.java,
        SettleDto::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        Boolean::class.javaPrimitiveType!!,
        String::class.java,
    )

    private val allowedReturns = setOf(
        AuditRecordDto::class.java,
        List::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_audit_port_never_receives_a_role_token_or_account_dto() {
        AuditPort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "AuditPort.${method.name} 不得接收 ${type.name}：权限只在 S13 拦截（铁律 7）",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "AuditPort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_audit_port_only_returns_dtos() {
        AuditPort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "AuditPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2）",
            )
        }
    }

    @Test
    fun sql_only_lives_in_the_audit_repository() {
        val sources = mainSources()
        assertTrue(sources.isNotEmpty(), "找不到本模块生产源码目录（测试工作目录应为模块目录）")

        val dml = Regex("""\b(?:SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET|DELETE\s+FROM\s)""", RegexOption.IGNORE_CASE)
        val withSql = sources.filter { source ->
            source.readLines().any { line ->
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                !isComment && dml.containsMatchIn(trimmed)
            }
        }.map { it.name }.toSet()

        assertEquals(setOf("AuditRepository.kt"), withSql, "DML 只允许出现在 AuditRepository（铁律 10）；发现：$withSql")
    }

    @Test
    fun the_ledger_is_append_only() {
        val ledgerSources = mainSources().filter { it.name != "AuditRepository.kt" }
        ledgerSources.forEach { source ->
            val text = source.readText()
            assertFalse(
                Regex("""\bUPDATE\s+\w+\s+SET\b""", RegexOption.IGNORE_CASE).containsMatchIn(text),
                "${source.name} 不得 UPDATE 审计台账：留痕只追加",
            )
            assertFalse(
                Regex("""\bDELETE\s+FROM\s+audit_log\b""", RegexOption.IGNORE_CASE).containsMatchIn(text),
                "${source.name} 不得删除审计台账",
            )
        }
        val repository = File("src/main/kotlin/com/jiancai/observability/AuditRepository.kt").readText()
        assertFalse(
            Regex("""\b(?:UPDATE\s+\w+\s+SET|DELETE\s+FROM)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(repository),
            "AuditRepository 只允许 INSERT / SELECT",
        )
    }

    @Test
    fun the_module_does_not_import_any_business_module() {
        val forbiddenImports = listOf(
            "com.jiancai.pricing",
            "com.jiancai.settlement",
            "com.jiancai.order",
            "com.jiancai.note",
            "com.jiancai.print",
            "com.jiancai.returncheck",
            "com.jiancai.customer",
            "com.jiancai.category",
            "com.jiancai.serial",
            "com.jiancai.auth",
            "com.jiancai.account",
            "com.jiancai.config",
            "com.jiancai.gateway",
        )
        mainSources().forEach { source ->
            val text = source.readText()
            forbiddenImports.forEach { prefix ->
                assertFalse(
                    text.contains("import $prefix"),
                    "${source.name} 不得依赖业务模块 $prefix：S14 只依赖 S00 db-core + shared",
                )
            }
        }
    }

    @Test
    fun the_module_never_makes_a_permission_decision() {
        val code = mainSources()
            .filter { it.name != "AuditException.kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(code.contains("E403"), "S14 不判权限：E403 只能由 S13 抛出")
        assertFalse(code.contains("Role."), "S14 不得引用角色枚举")
        assertFalse(
            Regex("""\bfun\s+\w*(?:canAudit|canChangePrice|allow|permit)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(code),
            "S14 不得实现任何『能不能做』的判定",
        )
    }
}

/** 本模块生产源码（测试工作目录 = 模块目录）。 */
internal fun mainSources(): List<File> {
    val root = File("src/main/kotlin/com/jiancai/observability")
    return root.listFiles { file -> file.isFile && file.extension == "kt" }?.sortedBy { it.name } ?: emptyList()
}
