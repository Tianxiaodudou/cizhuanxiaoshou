/*
 * @file fpk-server/category/src/test/kotlin/com/jiancai/category/CategoryPortTest.kt
 * @role S05 用例验收：管理员 CRUD、服务端规范化、唯一性、批号类型、装箱规格校验、事务口径。
 * @exports CategoryPortTest
 * @depends shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.category

import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S05 用例验收：管理员 CRUD、服务端规范化、唯一性、批号类型、装箱规格校验、事务口径。
 *
 * 非管理员请求的 403 属于 S13 api-gateway（本模块不判权限，见 [CategoryBoundaryTest]）。
 */
class CategoryPortTest {

    // —— 管理员 CRUD ——

    @Test
    fun admin_can_create_read_list_update_and_delete_a_category() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            val created = port.create(
                cmd(
                    categoryName = " 瓷砖 ",
                    model = " 800×800 ",
                    unit = PricingUnit.SQUARE_METER,
                    batchTagRequired = true,
                    batchTags = listOf(" 20261110 ", "20261111"),
                    packingSpec = PackingSpec(
                        baseUnit = PricingUnit.SQUARE_METER,
                        packageUnit = PricingUnit.BOX,
                        unitsPerPackage = 3,
                        weightPerPackageKg = " 24.5 ",
                        volumePerPackageM3 = "0.032",
                        specText = " 800×800 ",
                    ),
                ),
            )

            assertEquals(1L, created.id)
            assertEquals("瓷砖", created.categoryName)
            assertEquals("800×800", created.model)
            assertEquals(PricingUnit.SQUARE_METER, created.unit)
            assertTrue(created.batchTagRequired)
            assertEquals(listOf("20261110", "20261111"), created.batchTags)
            assertEquals(PricingUnit.BOX, created.packingSpec?.packageUnit)
            assertEquals(3, created.packingSpec?.unitsPerPackage)
            assertEquals("24.5", created.packingSpec?.weightPerPackageKg)
            assertEquals("0.032", created.packingSpec?.volumePerPackageM3)
            assertEquals("800×800", created.packingSpec?.specText)
            assertTrue(created.enabled)
            assertEquals(CREATED_AT, created.createdAt)
            assertNull(created.updatedAt)

            assertEquals(created, port.get(created.id))
            assertEquals(listOf(created), port.list())

            val updated = port.update(
                cmd(
                    id = created.id,
                    categoryName = "瓷砖",
                    model = "800×800",
                    unit = PricingUnit.SQUARE_METER,
                    batchTags = listOf("20261112"),
                    packingSpec = PackingSpec(
                        baseUnit = PricingUnit.SQUARE_METER,
                        specText = "800×800 优等品",
                    ),
                    enabled = false,
                ),
            )
            assertEquals("800×800 优等品", updated.packingSpec?.specText)
            assertNull(updated.packingSpec?.unitsPerPackage, "未提供包装信息时应清空旧值")
            assertEquals(listOf("20261112"), updated.batchTags)
            assertFalse(updated.enabled)
            assertEquals(CREATED_AT, updated.updatedAt)
            assertEquals(CREATED_AT, updated.createdAt, "修改不得改动创建时间")

            port.delete(created.id)
            val missing = assertFailsWith<CategoryException> { port.get(created.id) }
            assertEquals(ErrorCode.E404, missing.code)
            assertTrue(port.list().isEmpty())
        }
    }

    @Test
    fun list_is_ordered_by_category_name_and_model() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)
            port.create(cmd("瓷砖", "800×800", unit = PricingUnit.SQUARE_METER))
            port.create(cmd("水泥", "PC32.5", unit = PricingUnit.TON))
            port.create(cmd("瓷砖", "600×600", unit = PricingUnit.SQUARE_METER))

            assertEquals(
                listOf("水泥/PC32.5", "瓷砖/600×600", "瓷砖/800×800"),
                port.list().map { "${it.categoryName}/${it.model}" },
            )
        }
    }

    @Test
    fun list_can_filter_to_enabled_models_for_the_entry_screen() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)
            val enabled = port.create(cmd("瓷砖", "800×800", unit = PricingUnit.SQUARE_METER))
            port.create(cmd("瓷砖", "600×600", unit = PricingUnit.SQUARE_METER, enabled = false))

            assertEquals(2, port.list().size)
            assertEquals(listOf(enabled.id), port.list(enabledOnly = true).map { it.id })
        }
    }

    // —— 唯一性 ——

    @Test
    fun duplicate_category_and_model_is_rejected_with_e409_and_writes_nothing() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)
            port.create(cmd("瓷砖", "ABC"))

            val duplicate = assertFailsWith<CategoryException> { port.create(cmd(" 瓷砖 ", "abc")) }
            assertEquals(ErrorCode.E409, duplicate.code, "品类 + 型号大小写不敏感唯一")
            assertEquals(1, port.list().size)

            val other = port.create(cmd("瓷砖", "800×800", unit = PricingUnit.SQUARE_METER))
            val clash = assertFailsWith<CategoryException> {
                port.update(cmd(id = other.id, categoryName = "瓷砖", model = "abc"))
            }
            assertEquals(ErrorCode.E409, clash.code)
            assertEquals("800×800", port.get(other.id).model, "冲突时不得改动原行")
        }
    }

    // —— 入参口径 ——

    @Test
    fun blank_names_are_rejected_with_e422_and_write_nothing() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> { port.create(cmd("   ", "800×800")) }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> { port.create(cmd("瓷砖", " ")) }.code,
            )
            assertTrue(port.list().isEmpty())
        }
    }

    @Test
    fun create_requires_an_empty_id_and_update_requires_an_id() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> {
                    port.create(cmd("瓷砖", "800×800", id = 1L))
                }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> { port.update(cmd("瓷砖", "800×800")) }.code,
            )
            assertTrue(port.list().isEmpty())
        }
    }

    @Test
    fun unknown_category_is_rejected_with_e404() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            assertEquals(ErrorCode.E404, assertFailsWith<CategoryException> { port.get(99) }.code)
            assertEquals(
                ErrorCode.E404,
                assertFailsWith<CategoryException> { port.update(cmd("瓷砖", "800×800", id = 99)) }.code,
            )
            assertEquals(ErrorCode.E404, assertFailsWith<CategoryException> { port.delete(99) }.code)
        }
    }

    // —— 批号类型 ——

    @Test
    fun batch_tags_are_trimmed_deduped_and_fully_replaced_on_update() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            val created = port.create(
                cmd("瓷砖", "800×800", batchTags = listOf("A", " B ", "a", "b", "c")),
            )
            assertEquals(listOf("A", "B", "c"), created.batchTags, "去重保留首次写法与顺序")
            assertEquals(3L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM category_batch_tag") })

            val updated = port.update(cmd(id = created.id, categoryName = "瓷砖", model = "800×800", batchTags = listOf("d")))
            assertEquals(listOf("d"), updated.batchTags)
            assertEquals(1L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM category_batch_tag") })
        }
    }

    @Test
    fun required_batch_tags_need_at_least_one_non_blank_value() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> {
                    port.create(cmd("瓷砖", "800×800", batchTagRequired = true))
                }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> {
                    port.create(cmd("瓷砖", "800×800", batchTagRequired = true, batchTags = listOf("  ")))
                }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> {
                    port.create(cmd("瓷砖", "800×800", batchTags = listOf("a\nb")))
                }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> {
                    port.create(cmd("瓷砖", "800×800", batchTags = listOf("x".repeat(33))))
                }.code,
            )
            assertEquals(
                ErrorCode.E422,
                assertFailsWith<CategoryException> {
                    port.create(cmd("瓷砖", "800×800", batchTags = (1..21).map { "T$it" }))
                }.code,
            )
            assertTrue(port.list().isEmpty())
        }
    }

    // —— 装箱规格 ——

    @Test
    fun invalid_packing_spec_is_rejected_with_e422_before_writing() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)
            val unit = PricingUnit.SQUARE_METER

            fun spec(
                packageUnit: PricingUnit? = null,
                unitsPerPackage: Int? = null,
                weight: String? = null,
                baseUnit: PricingUnit = unit,
            ) = PackingSpec(
                baseUnit = baseUnit,
                packageUnit = packageUnit,
                unitsPerPackage = unitsPerPackage,
                weightPerPackageKg = weight,
            )

            val cases = listOf(
                "包装单位缺数量" to spec(packageUnit = PricingUnit.BOX, unitsPerPackage = null),
                "有数量缺包装单位" to spec(packageUnit = null, unitsPerPackage = 2),
                "数量为零" to spec(packageUnit = PricingUnit.BOX, unitsPerPackage = 0),
                "基准单位不一致" to spec(baseUnit = PricingUnit.BOX),
                "重量为负" to spec(weight = "-1.5"),
                "重量非数字" to spec(weight = "abc"),
            )
            cases.forEach { (label, packingSpec) ->
                val failure = assertFailsWith<CategoryException>(label) {
                    port.create(cmd("瓷砖", "800×800", unit = unit, packingSpec = packingSpec))
                }
                assertEquals(ErrorCode.E422, failure.code, label)
            }
            assertTrue(port.list().isEmpty())
        }
    }

    @Test
    fun blank_optional_packing_values_become_null_instead_of_empty_strings() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)
            val created = port.create(
                cmd(
                    "瓷砖",
                    "800×800",
                    unit = PricingUnit.SQUARE_METER,
                    packingSpec = PackingSpec(
                        baseUnit = PricingUnit.SQUARE_METER,
                        weightPerPackageKg = "   ",
                        volumePerPackageM3 = " ",
                        specText = "  ",
                    ),
                ),
            )
            assertNull(created.packingSpec?.weightPerPackageKg)
            assertNull(created.packingSpec?.volumePerPackageM3)
            assertNull(created.packingSpec?.specText)
        }
    }

    // —— 删除 ——

    @Test
    fun deleting_a_category_also_removes_its_batch_tags() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)
            val created = port.create(cmd("瓷砖", "800×800", batchTags = listOf("A", "B")))

            port.delete(created.id)

            assertEquals(0L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM category") })
            assertEquals(0L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM category_batch_tag") })
        }
    }

    // —— 事务口径（铁律 4）——

    @Test
    fun every_convenience_overload_opens_exactly_one_transaction() {
        testDb().use { db ->
            val counting = CountingTxRunner(db.txRunner)
            val port = CategoryService(counting, TEST_CLOCK)

            val created = port.create(cmd("瓷砖", "800×800"))
            assertEquals(1, counting.runs)

            port.get(created.id)
            assertEquals(2, counting.runs)

            port.list()
            assertEquals(3, counting.runs)

            port.update(cmd(id = created.id, categoryName = "瓷砖", model = "800×800", unit = PricingUnit.SQUARE_METER))
            assertEquals(4, counting.runs)

            port.delete(created.id)
            assertEquals(5, counting.runs)
        }
    }

    @Test
    fun tx_overloads_reuse_the_callers_transaction_and_never_open_their_own() {
        testDb().use { db ->
            val counting = CountingTxRunner(db.txRunner)
            val port = CategoryService(counting, TEST_CLOCK)

            db.txRunner.run { tx ->
                val created = port.create(tx, cmd("瓷砖", "800×800", batchTags = listOf("A")))
                assertEquals(created, port.get(tx, created.id))
                assertEquals(listOf(created), port.list(tx))
                port.update(tx, cmd(id = created.id, categoryName = "瓷砖", model = "800×800", unit = PricingUnit.SQUARE_METER))
                port.delete(tx, created.id)
            }

            assertEquals(0, counting.runs, "带 tx 的重载不得自开事务")
        }
    }

    @Test
    fun a_failed_caller_transaction_rolls_back_the_category_write() {
        testDb().use { db ->
            val port = CategoryService(db.txRunner, TEST_CLOCK)

            assertFailsWith<IllegalStateException> {
                db.txRunner.run { tx ->
                    port.create(tx, cmd("瓷砖", "800×800", batchTags = listOf("A")))
                    error("后续业务失败，整体回滚")
                }
            }

            assertTrue(port.list().isEmpty(), "外层事务回滚必须一并撤销型号与批号类型")
            assertEquals(0L, db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM category_batch_tag") })
        }
    }
}
