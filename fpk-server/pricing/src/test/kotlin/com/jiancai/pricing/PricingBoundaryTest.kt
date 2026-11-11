/*
 * @file fpk-server/pricing/src/test/kotlin/com/jiancai/pricing/PricingBoundaryTest.kt
 * @role 硬边界（S06）的结构化守卫
 * @exports PricingBoundaryTest
 * @depends db-core, shared
 * @calledBy （无）
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.pricing

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.PriceDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 硬边界（S06）的结构化守卫：
 * 1. **不判权限**：[PricePort] 里没有角色 / token / 账号 DTO；`changePrice` 的 `adminId`
 *    只是留痕主体（S13 已认证），不是权限判断；
 * 2. **返回 DTO**：出参只能是 [PriceDto] / [PriceChangeLog] / `List<...>` / Unit；
 * 3. **SQL 只在 repository**：生产源码里除 [PriceRepository] 外不得出现 DML；
 * 4. **留痕不可删改**：Repository 没有删除 / 修改留痕的方法，库层触发器再兜底；
 * 5. **表归属**：本模块只拥有 `price` / `price_change_log` 两张表。
 */
class PricingBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        String::class.java,
        ChangePriceCmd::class.java,
    )

    private val allowedReturns = setOf(
        PriceDto::class.java,
        java.util.List::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_price_port_never_receives_a_role_token_or_account_dto() {
        PricePort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "PricePort.${method.name} 不得接收角色 / token / 账号 DTO（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
                val name = type.simpleName
                assertFalse(
                    name.contains("Role", ignoreCase = true) ||
                        name.contains("Token", ignoreCase = true) ||
                        name.contains("Account", ignoreCase = true) ||
                        name.contains("Principal", ignoreCase = true),
                    "PricePort.${method.name} 出现身份 / 权限类型：${type.name}",
                )
            }
        }
    }

    @Test
    fun the_price_port_only_returns_dtos() {
        PricePort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "PricePort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun sql_only_lives_in_the_pricing_repository() {
        val sources = File("src/main/kotlin/com/jiancai/pricing")
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
        assertTrue(sources.isNotEmpty(), "找不到本模块生产源码目录（测试工作目录应为模块目录）")

        val dml = Regex(
            """\b(?:SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET|DELETE\s+FROM\s)""",
            RegexOption.IGNORE_CASE,
        )
        // 迁移里的 `SELECT RAISE(...)` 是触发器保护语句，不是数据 DML；DDL 允许留在迁移文件。
        val triggerRaise = Regex("""^SELECT\s+RAISE\s*\(""", RegexOption.IGNORE_CASE)
        val withSql = sources.filter { source ->
            source.readLines().any { line ->
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") ||
                    trimmed.startsWith("/*")
                !isComment && !triggerRaise.containsMatchIn(trimmed) && dml.containsMatchIn(trimmed)
            }
        }.map { it.name }.toSet()

        assertEquals(
            setOf("PriceRepository.kt"),
            withSql,
            "DML 只允许出现在 PriceRepository（铁律 10）；发现：$withSql",
        )
    }

    @Test
    fun the_change_log_has_no_delete_or_update_path() {
        val repository = File("src/main/kotlin/com/jiancai/pricing/PriceRepository.kt").readText()

        assertFalse(
            repository.contains("DELETE FROM price_change_log", ignoreCase = true),
            "Repository 不得出现删除留痕的 SQL",
        )
        assertFalse(
            repository.contains("UPDATE price_change_log", ignoreCase = true),
            "Repository 不得出现修改留痕的 SQL",
        )
        assertFalse(
            repository.contains("DELETE FROM price", ignoreCase = true),
            "价格行也不提供删除入口（否则会破坏留痕的外键）",
        )

        val repositoryMethods = PriceRepository::class.java.declaredMethods
            .map { it.name.lowercase() }
        assertTrue(
            repositoryMethods.none { it.contains("delete") || it.contains("remove") },
            "PriceRepository 不得暴露删除方法：$repositoryMethods",
        )
    }

    @Test
    fun the_module_owns_exactly_two_business_tables() {
        pricingOnlyDb().use { db ->
            val tables = db.txRunner.run { tx -> tx.tableNames() }

            assertEquals(
                listOf("price", "price_change_log"),
                tables,
                "S06 只允许拥有 price / price_change_log 两张表（铁律 3）",
            )
        }
    }
}
