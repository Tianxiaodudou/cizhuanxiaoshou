/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   35 行  类 CustomerPortTest
 * 第   38 行  函数 create_returns_a_server_generated_dto_and_trims_inputs
 * 第   61 行  函数 find_by_phone_is_exact_and_rejects_non_canonical_input
 * 第   77 行  函数 find_by_phone_returns_only_the_matching_customer
 * 第   89 行  函数 find_by_phone_returns_null_for_an_unknown_number
 * 第   99 行  函数 blank_phone_lookup_is_rejected_with_e422
 * 第  111 行  函数 blank_name_phone_or_address_handling
 * 第  135 行  函数 unknown_customer_id_is_rejected_with_e404
 * 第  144 行  函数 creating_an_existing_phone_returns_the_existing_customer_and_writes_nothing
 * 第  159 行  函数 phone_is_unique_and_digit_only_at_the_database_layer
 * 第  195 行  函数 every_convenience_overload_opens_exactly_one_transaction
 * 第  212 行  函数 tx_overloads_reuse_the_callers_transaction_and_never_open_their_own
 * 第  228 行  函数 creating_in_an_outer_transaction_rolls_back_together_with_the_caller
 */

package com.jiancai.customer

import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.errors.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收：客户建档、按电话查询、地址快照来源。
 *
 * 全部用例跑在真实 SQLite 内存库上（`DbCore.testDatabase(CustomerMigrations.all)`），
 * 因此唯一约束、CHECK、SQL 与事务都是真实执行，不是内存替身。
 */
class CustomerPortTest {

    @Test
    fun create_returns_a_server_generated_dto_and_trims_inputs() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())

            val created = service.create(
                CreateCustomerCmd(
                    name = " 张三 ",
                    phone = "13800000000",
                    address = " 建材市场 1 号 ",
                ),
            )

            assertTrue(created.id > 0, "客户 id 必须由服务端产出")
            assertEquals("张三", created.name, "名称首尾空白必须被裁掉")
            assertEquals("13800000000", created.phone, "电话按原值保存（唯一 id）")
            assertEquals("建材市场 1 号", created.address, "地址首尾空白必须被裁掉")
            assertEquals(CREATED_AT, created.createdAt, "created_at 必须按 WireFormat.TIMESTAMP 由服务端产出")
            assertNull(created.updatedAt, "v1.0.0 没有修改客户入口，建档时 updated_at 为 null")
            assertEquals(created, service.get(created.id), "get 必须返回同一条 DTO")
        }
    }

    @Test
    fun find_by_phone_is_exact_and_rejects_non_canonical_input() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            val created = service.create(CreateCustomerCmd("李四", "057412345678"))

            assertEquals(created, service.findByPhone("057412345678"), "电话按唯一 id 精确匹配")

            // 电话只有一种写法：带空格 / 划线的输入不是同一个号码，也不是合法电话，直接 E422。
            listOf("0574-12345678", "0574 12345678", " 057412345678 ").forEach { nonCanonical ->
                val failure = assertFailsWith<CustomerException> { service.findByPhone(nonCanonical) }
                assertEquals(ErrorCode.E422, failure.code, "非法电话写法应被拒绝：\"$nonCanonical\"")
            }
        }
    }

    @Test
    fun find_by_phone_returns_only_the_matching_customer() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            val first = service.create(CreateCustomerCmd("张三", "13800000000", "市场 1 号"))
            val second = service.create(CreateCustomerCmd("李四", "13900000000", "市场 2 号"))

            assertEquals(first, service.findByPhone("13800000000"))
            assertEquals(second, service.findByPhone("13900000000"))
        }
    }

    @Test
    fun find_by_phone_returns_null_for_an_unknown_number() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            service.create(CreateCustomerCmd("张三", "13800000000"))

            assertNull(service.findByPhone("13700000000"), "未命中的电话查询应返回 null，而不是抛错")
        }
    }

    @Test
    fun blank_phone_lookup_is_rejected_with_e422() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())

            listOf("", "   ", " - - ").forEach { blank ->
                val failure = assertFailsWith<CustomerException> { service.findByPhone(blank) }
                assertEquals(ErrorCode.E422, failure.code)
            }
        }
    }

    @Test
    fun blank_name_phone_or_address_handling() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())

            val blankName = assertFailsWith<CustomerException> {
                service.create(CreateCustomerCmd("   ", "13800000000"))
            }
            assertEquals(ErrorCode.E422, blankName.code)

            val blankPhone = assertFailsWith<CustomerException> {
                service.create(CreateCustomerCmd("张三", " - - "))
            }
            assertEquals(ErrorCode.E422, blankPhone.code)

            db.txRunner.run { tx ->
                assertEquals(0L, tx.queryLong("SELECT COUNT(*) FROM customer"), "校验失败不得写入任何客户")
            }

            val created = service.create(CreateCustomerCmd("张三", "13800000000", "   "))
            assertNull(created.address, "空白地址必须归一为 null，而不是空字符串")
        }
    }

    @Test
    fun unknown_customer_id_is_rejected_with_e404() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            val failure = assertFailsWith<CustomerException> { service.get(404L) }
            assertEquals(ErrorCode.E404, failure.code)
        }
    }

    @Test
    fun creating_an_existing_phone_returns_the_existing_customer_and_writes_nothing() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            val first = service.create(CreateCustomerCmd("张三", "13800000000", "市场 1 号"))

            // 电话就是客户唯一 id，同一号码再次建档必须幂等返回已有客户。
            val again = service.create(CreateCustomerCmd("张三（重复）", "13800000000", "市场 2 号"))

            assertEquals(first, again, "同号再次建档必须返回已有客户，而不是新建第二条")
            assertEquals(1L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM customer") })
            assertEquals(first, service.get(first.id), "幂等建档不得覆盖已有名称 / 地址")
        }
    }

    @Test
    fun phone_is_unique_and_digit_only_at_the_database_layer() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            service.create(CreateCustomerCmd("张三", "13800000000"))

            // 绕过 Port 直接写同电话：库层唯一约束必须拒绝（应用层之外的第二道闸）。
            val duplicate = assertFailsWith<java.sql.SQLException> {
                db.txRunner.run { tx ->
                    tx.exec(
                        "INSERT INTO customer (name, phone, address, created_at) " +
                            "VALUES ('李四', '13800000000', NULL, '$CREATED_AT')",
                    )
                }
            }
            assertTrue(
                duplicate.message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true),
                "应命中 phone 唯一约束（实际：${duplicate.message}）",
            )

            // 库层同样只接受纯数字：带划线的电话即使绕过 Port 也写不进去。
            val nonCanonical = assertFailsWith<java.sql.SQLException> {
                db.txRunner.run { tx ->
                    tx.exec(
                        "INSERT INTO customer (name, phone, address, created_at) " +
                            "VALUES ('王五', '138-0000-0000', NULL, '$CREATED_AT')",
                    )
                }
            }
            assertTrue(
                nonCanonical.message.orEmpty().contains("CHECK constraint failed", ignoreCase = true),
                "应命中 phone 纯数字 CHECK（实际：${nonCanonical.message}）",
            )
        }
    }

    @Test
    fun every_convenience_overload_opens_exactly_one_transaction() {
        testDb().use { db ->
            val counting = CountingTxRunner(db.txRunner)
            val service = CustomerService(counting, clockAt())

            val created = service.create(CreateCustomerCmd("张三", "13800000000"))
            assertEquals(1, counting.runs, "create 便捷重载应开 1 个事务")

            assertEquals(created, service.get(created.id))
            assertEquals(2, counting.runs, "get 便捷重载应再开 1 个事务")

            assertEquals(created, service.findByPhone("13800000000"))
            assertEquals(3, counting.runs, "findByPhone 便捷重载应再开 1 个事务")
        }
    }

    @Test
    fun tx_overloads_reuse_the_callers_transaction_and_never_open_their_own() {
        testDb().use { db ->
            val counting = CountingTxRunner(db.txRunner)
            val service = CustomerService(counting, clockAt())

            db.txRunner.run { tx ->
                val created = service.create(tx, CreateCustomerCmd("张三", "13800000000"))
                assertEquals(created, service.get(tx, created.id))
                assertEquals(created, service.findByPhone(tx, "13800000000"))
            }

            assertEquals(0, counting.runs, "带 tx 重载不得另开事务（铁律 4）")
        }
    }

    @Test
    fun creating_in_an_outer_transaction_rolls_back_together_with_the_caller() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())

            runCatching {
                db.txRunner.run { tx ->
                    service.create(tx, CreateCustomerCmd("张三", "13800000000"))
                    error("模拟订单写入失败")
                }
            }

            assertEquals(
                0L,
                db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM customer") },
                "外层回滚必须同时丢弃客户建档（跨模块同事务）",
            )
        }
    }
}
