/*
 * @file fpk-server/customer/src/test/kotlin/com/jiancai/customer/CustomerBoundaryTest.kt
 * @role 硬边界（S04）的结构化守卫
 * @exports CustomerBoundaryTest
 * @depends db-core, shared
 * @calledBy （无）
 * @sideEffects 读 sqlite_master（SELECT）
 */

package com.jiancai.customer

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CustomerDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 硬边界（S04）的结构化守卫：
 * 1. **不判权限**：Port 签名里没有调用者身份（没有 token / 操作者 / 角色），权限只能在 S13；
 * 2. **返回 DTO**：出参只能是 [CustomerDto] / Unit，不出现持久化实体；
 * 3. **SQL 只在 repository**：生产源码里除 [CustomerRepository] 外不得出现 DML；
 * 4. **表归属**：本模块的库只拥有 `customer` 一张业务表，不建也不读其它模块的表。
 */
class CustomerBoundaryTest {

    private val allowedParameters = setOf(
        TxContext::class.java,
        String::class.java,
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
        CreateCustomerCmd::class.java,
    )

    private val allowedReturns = setOf(
        CustomerDto::class.java,
        java.lang.Void.TYPE,
    )

    @Test
    fun the_customer_port_never_receives_the_callers_identity_or_role() {
        CustomerPort::class.java.declaredMethods.forEach { method ->
            method.parameterTypes.forEach { type ->
                assertTrue(
                    type in allowedParameters,
                    "CustomerPort.${method.name} 不得接收调用者身份 / 角色 / token 参数（发现 ${type.name}）；" +
                        "权限只能在 S13 api-gateway 拦截",
                )
            }
        }
    }

    @Test
    fun the_customer_port_only_returns_dtos() {
        CustomerPort::class.java.declaredMethods.forEach { method ->
            assertTrue(
                method.returnType in allowedReturns,
                "CustomerPort.${method.name} 必须返回 DTO（发现 ${method.returnType.name}；铁律 2 禁止返回 Entity）",
            )
        }
    }

    @Test
    fun the_customer_dto_does_not_leak_persistence_only_columns() {
        val leaked = CustomerDto::class.java.declaredFields
            .map { it.name }
            .filter { it.contains("phone_key", ignoreCase = true) || it == "phoneKey" }

        assertTrue(leaked.isEmpty(), "CustomerDto 不得暴露持久化专用列：$leaked")
    }

    @Test
    fun sql_only_lives_in_the_customer_repository() {
        val sources = File("src/main/kotlin/com/jiancai/customer")
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
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
            setOf("CustomerRepository.kt"),
            withSql,
            "DML 只允许出现在 CustomerRepository（铁律 10）；发现：$withSql",
        )
    }

    @Test
    fun the_module_owns_exactly_one_business_table() {
        testDb().use { db ->
            val tables = db.txRunner.run { tx ->
                tx.connection.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name NOT IN ('schema_migration', 'sqlite_sequence') ORDER BY name",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }

            assertEquals(listOf("customer"), tables, "S04 只允许拥有 customer 一张表（铁律 3）")
        }
    }
}
