/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/RouteTableTest.kt
 * @role 路由表的静态守卫（硬边界「禁止实现业务逻辑」，只转发 / 拦截 / 映射）
 * @exports RouteTableTest
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 文件 IO
 */

package com.jiancai.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 路由表的静态守卫（硬边界「禁止实现业务逻辑」，只转发 / 拦截 / 映射）：
 * 1. 路由清单与任务卡一一对应（S02–S12 的入口都在，且没有多余的取号 / 打印 / SQL 入口）；
 * 2. 幂等只开在会产生单据的三个写接口上；
 * 3. 管理员接口清单固定（账号、型号库写、改价、结算审核）；
 * 4. 生产源码里没有 SQL、没有 repository、没有金额构造、没有 println；
 * 5. `E403` 只能从 [AuthGate] 抛出（权限只有一个判断点）。
 */
class RouteTableTest {

    private val expectedRoutes = setOf(
        "GET /contract",
        "POST /auth/login",
        "GET /me",
        "GET /accounts",
        "GET /accounts/{id}",
        "POST /accounts",
        "PUT /accounts/{id}",
        "DELETE /accounts/{id}",
        "GET /customers",
        "POST /customers",
        "GET /categories",
        "GET /categories/{id}",
        "POST /categories",
        "PUT /categories/{id}",
        "DELETE /categories/{id}",
        "GET /prices",
        "POST /prices/change",
        "GET /orders/today",
        "GET /orders",
        "GET /orders/{orderNo}/receivable",
        "POST /orders",
        "PUT /orders/{orderNo}",
        "GET /orders/{orderNo}/returnable-batches",
        "POST /orders/{orderNo}/return-check",
        "POST /notes",
        "POST /notes/{noteNo}/void",
        "POST /settlements",
        "POST /settlements/{settleNo}/audit",
        "GET /customers/{customerId}/balance",
        "GET /audit",
        "GET /print/{docNo}",
    )

    @Test
    fun the_route_table_matches_the_contract() {
        FakeGateway().use { fixture ->
            val labels = fixture.gateway.routes().map { it.label }.toSet()
            assertEquals(expectedRoutes, labels)
        }
    }

    @Test
    fun only_document_creating_writes_require_an_idempotency_key() {
        FakeGateway().use { fixture ->
            val idempotent = fixture.gateway.routes().filter { it.idempotent }.map { it.label }.toSet()
            assertEquals(setOf("POST /orders", "POST /notes", "POST /settlements"), idempotent)
        }
    }

    @Test
    fun the_admin_only_endpoints_are_exactly_the_expected_ones() {
        FakeGateway().use { fixture ->
            val admin = fixture.gateway.routes().filter { it.access == RouteAccess.ADMIN }.map { it.label }.toSet()
            assertEquals(
                setOf(
                    "GET /accounts",
                    "GET /accounts/{id}",
                    "POST /accounts",
                    "PUT /accounts/{id}",
                    "DELETE /accounts/{id}",
                    "POST /categories",
                    "PUT /categories/{id}",
                    "DELETE /categories/{id}",
                    "POST /prices/change",
                    "POST /settlements/{settleNo}/audit",
                    "GET /audit",
                ),
                admin,
            )
        }
    }

    @Test
    fun there_is_no_serial_number_endpoint() {
        FakeGateway().use { fixture ->
            assertTrue(
                fixture.gateway.routes().none { it.template.contains("serial") },
                "网关不得暴露取号接口（铁律 8：单号只能由业务模块内部经 S07 产出）",
            )
        }
    }

    @Test
    fun there_is_no_client_printing_endpoint() {
        FakeGateway().use { fixture ->
            val printRoutes = fixture.gateway.routes().filter { it.template.contains("print") }
            assertEquals(listOf("GET /print/{docNo}"), printRoutes.map { it.label }, "打印只在服务端渲染，客户端只拿文本")
        }
    }

    @Test
    fun the_gateway_sources_only_touch_its_own_idempotency_table() {
        val sources = mainSources()
        assertTrue(sources.isNotEmpty(), "找不到网关生产源码（测试工作目录应为模块目录）")

        val dml = Regex("""\b(?:SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET|DELETE\s+FROM\s)""", RegexOption.IGNORE_CASE)
        val withSql = sources.filter { source ->
            source.readLines().any { line ->
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                !isComment && dml.containsMatchIn(trimmed)
            }
        }.map { it.name }.toSet()

        // 网关只拥有幂等台账一张表：DML 只允许出现在它的 repository 里（铁律 10）。
        assertEquals(
            setOf("SqliteIdempotencyStore.kt"),
            withSql,
            "网关的 DML 只允许落在幂等台账上；发现：$withSql",
        )
        val migrations = File("src/main/kotlin/com/jiancai/gateway/GatewayMigrations.kt").readText()
        assertTrue(
            migrations.contains("CREATE TABLE gateway_idempotency"),
            "网关唯一的表是幂等台账",
        )
        assertEquals(
            listOf("gateway_idempotency"),
            Regex("""CREATE\s+TABLE\s+(\w+)""").findAll(migrations).map { it.groupValues[1] }.toList(),
            "网关只允许建幂等台账一张表",
        )

        val all = sources.joinToString("\n") { it.readText() }
        listOf("order_header", "order_line", "diff_note", "audit_log", "auth_account", "print_job", "price_change_log")
            .forEach { businessTable ->
                assertFalse(all.contains(businessTable), "网关不得出现业务表名：$businessTable")
            }
        assertTrue(all.contains("gateway_idempotency"), "网关自己的台账表名必须出现")
        assertFalse(all.contains("jdbc:"), "网关不得自带数据库连接")
    }

    @Test
    fun the_gateway_never_computes_money_or_numbers() {
        val sources = mainSources()
        sources.forEach { source ->
            val text = source.readText()
            assertFalse(text.contains("Money.ofPlain"), "${source.name} 不得构造金额（铁律 5：金额只由服务端业务模块产出）")
            assertFalse(text.contains("BigDecimal"), "${source.name} 不得做金额运算")
            assertFalse(
                Regex("""\btotalAmount\s*=""").containsMatchIn(text),
                "${source.name} 不得派生总额",
            )
            assertFalse(
                Regex("""["'](?:PS|PT|ZS|ZT|BZ|JS)\$?\{?""").containsMatchIn(text),
                "${source.name} 不得拼接单号前缀（铁律 8）",
            )
        }
    }

    @Test
    fun the_gateway_does_not_log_with_stdout() {
        mainSources().forEach { source ->
            val text = source.readText()
            assertFalse(text.contains("println("), "${source.name} 不得用 println 记日志")
            assertFalse(text.contains("System.out"), "${source.name} 不得直接写标准输出")
        }
    }

    @Test
    fun e403_is_only_raised_by_the_auth_gate() {
        val throwers = mainSources().filter { source ->
            source.readText().contains("ErrorCode.E403")
        }.map { it.name }.toSet()

        assertEquals(setOf("AuthGate.kt"), throwers, "权限判断只能有一个点（铁律 7）")
    }

    @Test
    fun every_path_parameter_in_a_template_is_actually_extracted() {
        FakeGateway().use { fixture ->
            fixture.gateway.routes().forEach { route ->
                val names = Regex("""\{(\w+)}""").findAll(route.template).map { it.groupValues[1] }.toSet()
                val source = File("src/main/kotlin/com/jiancai/gateway/GatewayRoutes.kt").readText()
                names.forEach { name ->
                    assertTrue(
                        source.contains("path(\"$name\")"),
                        "路由 ${route.label} 声明了路径参数 {$name}，但 handler 没有读取它",
                    )
                }
            }
        }
    }
}

/** 网关生产源码（测试工作目录 = 模块目录）。 */
internal fun mainSources(): List<File> {
    val root = File("src/main/kotlin/com/jiancai/gateway")
    return root.listFiles { file -> file.isFile && file.extension == "kt" }?.sortedBy { it.name } ?: emptyList()
}
