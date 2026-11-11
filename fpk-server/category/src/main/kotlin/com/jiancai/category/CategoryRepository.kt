/*
 * @file fpk-server/category/src/main/kotlin/com/jiancai/category/CategoryRepository.kt
 * @role `category` / `category_batch_tag` 的唯一 SQL 出口（铁律 10）。
 * @exports （无）
 * @depends db-core, shared
 * @calledBy fpk-server/category/src/main/kotlin/com/jiancai/category/CategoryService.kt
 * @sideEffects 写 category, category_batch_tag（INSERT / UPDATE / DELETE）
 */

package com.jiancai.category

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * 型号库写入形态（内部类型）：Service 完成裁剪 / 校验后交给 Repository 落库，
 * 不实现为对外 DTO，也不会离开本模块。
 */
internal data class CategoryWriteModel(
    val categoryName: String,
    val model: String,
    val unit: PricingUnit,
    val batchTagRequired: Boolean,
    val batchTags: List<String>,
    val packingSpec: PackingSpec?,
    val enabled: Boolean,
)

/**
 * 型号库持久化形态（内部类型）：`category` + `category_batch_tag` 的组合视图，
 * 只在本模块内流转，对外一律转成 [CategoryDto]（铁律 2）。
 */
internal data class StoredCategory(
    val id: Long,
    val categoryName: String,
    val model: String,
    val unit: PricingUnit,
    val batchTagRequired: Boolean,
    val batchTags: List<String>,
    val packingSpec: PackingSpec?,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String?,
) {
    fun toDto(): CategoryDto = CategoryDto(
        id = id,
        categoryName = categoryName,
        model = model,
        unit = unit,
        batchTagRequired = batchTagRequired,
        batchTags = batchTags,
        packingSpec = packingSpec,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * `category` / `category_batch_tag` 的唯一 SQL 出口（铁律 10）。
 *
 * 两张表都归 S05 所有，其它模块禁止读写；对外只暴露 [CategoryPort]。
 * 所有方法都要求调用方已在 `TxRunner.run {}` 事务内（Port 负责），
 * 因此「查重 → 写入 → 回读」不会与其它事务交错。
 */
internal class CategoryRepository {

    /** 全部型号（可按启用状态过滤），按「品类名 + 型号」升序；批号类型按 position 保序。 */
    fun findAll(tx: TxContext, enabledOnly: Boolean): List<StoredCategory> {
        val tagsByCategory = loadAllTags(tx)
        val sql = if (enabledOnly) SELECT_ENABLED else SELECT_ALL
        return tx.connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                val result = ArrayList<StoredCategory>()
                while (rows.next()) {
                    val id = rows.getLong("id")
                    result += rows.toStoredCategory(tagsByCategory[id].orEmpty())
                }
                result
            }
        }
    }

    /** 按主键取型号；不存在返回 null。先读主表关闭游标，再单独取批号类型（避免同连接上叠着两个游标）。 */
    fun findById(tx: TxContext, id: Long): StoredCategory? {
        val stored = tx.connection.prepareStatement(SELECT_BY_ID).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toStoredCategory(emptyList()) else null
            }
        } ?: return null
        return stored.copy(batchTags = loadTags(tx, id))
    }

    /**
     * 「品类 + 型号」是否已被占用（大小写不敏感，与唯一约束同口径）。
     *
     * @param excludeId 修改时排除自身；新建传 null
     */
    fun existsByNameAndModel(tx: TxContext, categoryName: String, model: String, excludeId: Long?): Boolean =
        tx.connection.prepareStatement(SELECT_EXISTS).use { statement ->
            statement.setString(1, categoryName)
            statement.setString(2, model)
            statement.setLong(3, excludeId ?: NO_EXCLUSION)
            statement.executeQuery().use { rows -> rows.next() }
        }

    /**
     * 插入型号并返回主键。
     *
     * 与 S02 同口径：不用 JDBC `getGeneratedKeys`（不同 SQLite 驱动版本行为不一致），
     * 插入后在同一事务内按唯一键回查 id。
     */
    fun insert(tx: TxContext, data: CategoryWriteModel, createdAt: String): Long {
        tx.connection.prepareStatement(INSERT_CATEGORY).use { statement ->
            bindCommon(statement, data)
            statement.setString(12, createdAt)
            statement.setString(13, null)
            statement.executeUpdate()
        }
        return tx.connection.prepareStatement(SELECT_ID_BY_KEY).use { statement ->
            statement.setString(1, data.categoryName)
            statement.setString(2, data.model)
            statement.executeQuery().use { rows ->
                check(rows.next()) {
                    "category 插入后未查到型号：${data.categoryName} / ${data.model}"
                }
                rows.getLong(1)
            }
        }
    }

    /** 全量覆盖型号字段；返回是否有行被更新（false = 并发下已被删除）。 */
    fun update(tx: TxContext, id: Long, data: CategoryWriteModel, updatedAt: String): Boolean =
        tx.connection.prepareStatement(UPDATE_CATEGORY).use { statement ->
            bindCommon(statement, data)
            statement.setString(12, updatedAt)
            statement.setLong(13, id)
            statement.executeUpdate() > 0
        }

    /** 删除型号；返回是否有行被删除。批号类型由调用方先删（外键级联是第二道保险）。 */
    fun deleteById(tx: TxContext, id: Long): Boolean =
        tx.connection.prepareStatement(DELETE_CATEGORY).use { statement ->
            statement.setLong(1, id)
            statement.executeUpdate() > 0
        }

    /** 批号类型全量替换：先清空再按入参顺序写入（position 从 0 开始）。 */
    fun replaceTags(tx: TxContext, categoryId: Long, tags: List<String>) {
        deleteTags(tx, categoryId)
        if (tags.isEmpty()) return
        tx.connection.prepareStatement(INSERT_TAG).use { statement ->
            tags.forEachIndexed { position, tag ->
                statement.setLong(1, categoryId)
                statement.setString(2, tag)
                statement.setInt(3, position)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** 删除某型号的全部批号类型。 */
    fun deleteTags(tx: TxContext, categoryId: Long) {
        tx.connection.prepareStatement(DELETE_TAGS).use { statement ->
            statement.setLong(1, categoryId)
            statement.executeUpdate()
        }
    }

    private fun loadAllTags(tx: TxContext): Map<Long, List<String>> {
        val result = HashMap<Long, MutableList<String>>()
        tx.connection.prepareStatement(SELECT_ALL_TAGS).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result.getOrPut(rows.getLong("category_id")) { ArrayList() } += rows.getString("tag")
                }
            }
        }
        return result
    }

    private fun loadTags(tx: TxContext, categoryId: Long): List<String> =
        tx.connection.prepareStatement(SELECT_TAGS_BY_CATEGORY).use { statement ->
            statement.setLong(1, categoryId)
            statement.executeQuery().use { rows ->
                val result = ArrayList<String>()
                while (rows.next()) result += rows.getString("tag")
                result
            }
        }

    /** 绑定 1–11 号参数（两处写入共用同一段顺序，避免插 / 改顺序漂移）。 */
    private fun bindCommon(statement: PreparedStatement, data: CategoryWriteModel) {
        val spec = data.packingSpec
        statement.setString(1, data.categoryName)
        statement.setString(2, data.model)
        statement.setString(3, data.unit.name)
        statement.setInt(4, if (data.batchTagRequired) FLAG_ON else FLAG_OFF)
        statement.setString(5, spec?.baseUnit?.name)
        statement.setString(6, spec?.packageUnit?.name)
        val unitsPerPackage = spec?.unitsPerPackage
        if (unitsPerPackage == null) statement.setNull(7, java.sql.Types.INTEGER)
        else statement.setInt(7, unitsPerPackage)
        statement.setString(8, spec?.weightPerPackageKg)
        statement.setString(9, spec?.volumePerPackageM3)
        statement.setString(10, spec?.specText)
        statement.setInt(11, if (data.enabled) FLAG_ON else FLAG_OFF)
    }

    private fun ResultSet.toStoredCategory(tags: List<String>): StoredCategory {
        val baseUnit = getString("pack_base_unit")?.let(::pricingUnitOf)
        val packingSpec = baseUnit?.let { base ->
            PackingSpec(
                baseUnit = base,
                packageUnit = getString("pack_package_unit")?.let(::pricingUnitOf),
                unitsPerPackage = (getObject("pack_units_per_package") as? Number)?.toInt(),
                weightPerPackageKg = getString("pack_weight_kg"),
                volumePerPackageM3 = getString("pack_volume_m3"),
                specText = getString("pack_spec_text"),
            )
        }
        return StoredCategory(
            id = getLong("id"),
            categoryName = getString("category_name"),
            model = getString("model"),
            unit = pricingUnitOf(getString("unit")),
            batchTagRequired = getInt("batch_tag_required") == FLAG_ON,
            batchTags = tags,
            packingSpec = packingSpec,
            enabled = getInt("enabled") == FLAG_ON,
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )
    }

    /** 库中存枚举名（`SQUARE_METER`），线值仍由 shared 的 `@SerialName` 决定。 */
    private fun pricingUnitOf(raw: String): PricingUnit =
        PricingUnit.values().firstOrNull { it.name == raw }
            ?: error("category.unit 非法：$raw")

    private companion object {
        const val FLAG_ON = 1
        const val FLAG_OFF = 0

        /** 新建时没有要排除的 id。 */
        const val NO_EXCLUSION = -1L

        const val COLUMNS =
            "id, category_name, model, unit, batch_tag_required, pack_base_unit, pack_package_unit, " +
                "pack_units_per_package, pack_weight_kg, pack_volume_m3, pack_spec_text, " +
                "enabled, created_at, updated_at"

        val SELECT_ALL = "SELECT $COLUMNS FROM category ORDER BY category_name, model"

        val SELECT_ENABLED =
            "SELECT $COLUMNS FROM category WHERE enabled = 1 ORDER BY category_name, model"

        val SELECT_BY_ID = "SELECT $COLUMNS FROM category WHERE id = ?"

        const val SELECT_EXISTS =
            "SELECT 1 FROM category WHERE category_name = ? AND model = ? AND id <> ?"

        const val SELECT_ID_BY_KEY = "SELECT id FROM category WHERE category_name = ? AND model = ?"

        const val INSERT_CATEGORY =
            "INSERT INTO category " +
                "(category_name, model, unit, batch_tag_required, pack_base_unit, pack_package_unit, " +
                "pack_units_per_package, pack_weight_kg, pack_volume_m3, pack_spec_text, enabled, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        const val UPDATE_CATEGORY =
            "UPDATE category SET category_name = ?, model = ?, unit = ?, batch_tag_required = ?, " +
                "pack_base_unit = ?, pack_package_unit = ?, pack_units_per_package = ?, " +
                "pack_weight_kg = ?, pack_volume_m3 = ?, pack_spec_text = ?, enabled = ?, " +
                "updated_at = ? WHERE id = ?"

        const val DELETE_CATEGORY = "DELETE FROM category WHERE id = ?"

        const val SELECT_ALL_TAGS =
            "SELECT category_id, tag FROM category_batch_tag ORDER BY category_id, position"

        const val SELECT_TAGS_BY_CATEGORY =
            "SELECT tag FROM category_batch_tag WHERE category_id = ? ORDER BY position"

        const val INSERT_TAG =
            "INSERT INTO category_batch_tag (category_id, tag, position) VALUES (?, ?, ?)"

        const val DELETE_TAGS = "DELETE FROM category_batch_tag WHERE category_id = ?"
    }
}
