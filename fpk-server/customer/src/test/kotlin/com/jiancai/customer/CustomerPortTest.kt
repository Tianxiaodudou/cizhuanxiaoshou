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
                    phone = " 138-0000-0000 ",
                    address = " 建材市场 1 号 ",
                ),
            )

            assertTrue(created.id > 0, "客户 id 必须由服务端产出")
            assertEquals("张三", created.name, "名称首尾空白必须被裁掉")
            assertEquals("138-0000-0000", created.phone, "展示电话只 trim，保留用户写法")
            assertEquals("建材市场 1 号", created.address, "地址首尾空白必须被裁掉")
            assertEquals(CREATED_AT, created.createdAt, "created_at 必须由服务端时钟产出（UTC ISO-8601）")
            assertNull(created.updatedAt, "v1.0.0 没有修改客户入口，建档时 updated_at 为 null")
            assertEquals(created, service.get(created.id), "get 必须返回同一条 DTO")
        }
    }

    @Test
    fun find_by_phone_matches_the_same_number_written_differently() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            val created = service.create(CreateCustomerCmd("李四", "0574-12345678"))

            listOf("0574-12345678", "057412345678", " 0574 12345678 ").forEach { written ->
                assertEquals(
                    created,
                    service.findByPhone(written),
                    "电话查询必须按规范化键匹配（输入：\"$written\"）",
                )
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
            assertEquals(second, service.findByPhone("139-0000-0000"))
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
    fun duplicate_phone_is_rejected_with_e409_and_writes_nothing() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            service.create(CreateCustomerCmd("张三", "13800000000"))

            // 不同写法、同一号码：必须判为重复，避免订单挂到两个「张三」上。
            val failure = assertFailsWith<CustomerException> {
                service.create(CreateCustomerCmd("张三（重复）", "138-0000-0000"))
            }
            assertEquals(ErrorCode.E409, failure.code)
            assertEquals(1L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM customer") })
        }
    }

    @Test
    fun phone_uniqueness_is_enforced_by_the_database_as_well() {
        testDb().use { db ->
            val service = CustomerService(db.txRunner, clockAt())
            service.create(CreateCustomerCmd("张三", "13800000000"))

            // 绕过 Port 直接写同 phone_key：库层唯一约束必须拒绝（应用层之外的第二道闸）。
            val failure = assertFailsWith<java.sql.SQLException> {
                db.txRunner.run { tx ->
                    tx.exec(
                        "INSERT INTO customer (name, phone, phone_key, address, created_at) " +
                            "VALUES ('李四', '13800000000', '13800000000', NULL, '$CREATED_AT')",
                    )
                }
            }
            assertTrue(
                failure.message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true),
                "应命中 phone_key 唯一约束（实际：${failure.message}）",
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
                assertEquals(created, service.findByPhone(tx, "138-0000-0000"))
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
