/*
 * @file fpk-server/category/src/main/kotlin/com/jiancai/category/CategoryService.kt
 * @role [CategoryPort] 的唯一实现：型号库的用例层。
 * @exports CategoryService
 * @depends db-core, shared
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.category

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [CategoryPort] 的唯一实现：型号库的用例层。
 *
 * 职责边界：
 * - **不判权限**：本类拿不到调用者身份（构造参数里没有 token / 操作者 / 角色），
 *   权限判断只在 S13 api-gateway（铁律 7）；
 * - **服务端产出**：`created_at` / `updated_at` 由服务端时钟生成，客户端只展示；
 *   批号类型由服务端裁剪 / 去重 / 保序，客户端零加工（铁律 5）；
 * - **不跨表**：只经 [CategoryRepository] 读写本模块的两张表（铁律 3 / 10）。
 *
 * 校验与规范化（统一在服务端做，避免客户端各写一套）：
 * - 品类名 / 型号：裁剪首尾空白，非空且不超过 [MAX_NAME_LENGTH]；
 * - 批号类型：逐项裁剪、区分大小写去重（保留首次出现的写法与顺序）、
 *   每项不超过 [MAX_TAG_LENGTH]、最多 [MAX_TAG_COUNT] 项；
 *   `batchTagRequired = true` 时至少配置一项（否则录入页无值可选）；
 * - 装箱规格：`baseUnit` 必须与计价单位一致；包装单位与「每包装数量」必须同时提供，
 *   数量 > 0；重量 / 体积必须是十进制字符串且非负，空白视为未配置；
 *   规格文本裁剪后空白视为未配置；
 * - 唯一性：「品类 + 型号」大小写不敏感唯一（先查重，数据库唯一约束兜底并发）。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：写操作经它包裹）
 * @param clock 服务端时钟；测试注入固定时钟以断言时间戳
 */
class CategoryService(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : CategoryPort {

    private val repository = CategoryRepository()

    /** 时间戳按共享契约 [WireFormat.TIMESTAMP] 产出（含时区偏移）。 */
    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)

    // —— 读 ——

    override fun list(enabledOnly: Boolean): List<CategoryDto> =
        txRunner.run { tx -> list(tx, enabledOnly) }

    override fun list(tx: TxContext, enabledOnly: Boolean): List<CategoryDto> =
        repository.findAll(tx, enabledOnly).map { it.toDto() }

    override fun get(id: Long): CategoryDto = txRunner.run { tx -> get(tx, id) }

    override fun get(tx: TxContext, id: Long): CategoryDto =
        repository.findById(tx, id)?.toDto()
            ?: throw CategoryException(ErrorCode.E404, "型号不存在：id=$id")

    // —— 写 ——

    override fun create(cmd: CategoryCmd): CategoryDto = txRunner.run { tx -> create(tx, cmd) }

    override fun create(tx: TxContext, cmd: CategoryCmd): CategoryDto {
        if (cmd.id != null) {
            throw CategoryException(ErrorCode.E422, "新建型号时 id 必须为空（带 id 表示修改）")
        }
        val model = normalize(cmd)
        ensureUnique(tx, model, excludeId = null)

        val id = try {
            repository.insert(tx, model, createdAt = now())
        } catch (conflict: SQLException) {
            throw translate(conflict)
        }
        repository.replaceTags(tx, id, model.batchTags)
        // 回读一次，保证出参与库中完全一致（时间戳、批号类型顺序均由服务端产出）。
        return get(tx, id)
    }

    override fun update(cmd: CategoryCmd): CategoryDto = txRunner.run { tx -> update(tx, cmd) }

    override fun update(tx: TxContext, cmd: CategoryCmd): CategoryDto {
        val id = cmd.id ?: throw CategoryException(ErrorCode.E422, "修改型号时 id 不能为空")
        if (repository.findById(tx, id) == null) {
            throw CategoryException(ErrorCode.E404, "型号不存在：id=$id")
        }

        val model = normalize(cmd)
        ensureUnique(tx, model, excludeId = id)

        val updated = try {
            repository.update(tx, id, model, updatedAt = now())
        } catch (conflict: SQLException) {
            throw translate(conflict)
        }
        if (!updated) throw CategoryException(ErrorCode.E404, "型号不存在：id=$id")

        repository.replaceTags(tx, id, model.batchTags)
        return get(tx, id)
    }

    override fun delete(id: Long) {
        txRunner.run { tx -> delete(tx, id) }
    }

    override fun delete(tx: TxContext, id: Long) {
        if (repository.findById(tx, id) == null) {
            throw CategoryException(ErrorCode.E404, "型号不存在：id=$id")
        }
        // 先删批号类型再删主表：外键级联是第二道保险，不依赖 PRAGMA foreign_keys 的开关。
        repository.deleteTags(tx, id)
        if (!repository.deleteById(tx, id)) {
            throw CategoryException(ErrorCode.E404, "型号不存在：id=$id")
        }
    }

    // —— 校验与规范化 ——

    /**
     * 把入参裁剪 / 校验成可落库的 [CategoryWriteModel]；任何不合规都抛 `E422`，不写库。
     */
    private fun normalize(cmd: CategoryCmd): CategoryWriteModel {
        val categoryName = cmd.categoryName.trim()
        if (categoryName.isEmpty()) throw CategoryException(ErrorCode.E422, "品类名称不能为空")
        if (categoryName.length > MAX_NAME_LENGTH) {
            throw CategoryException(ErrorCode.E422, "品类名称不能超过 $MAX_NAME_LENGTH 个字符")
        }

        val model = cmd.model.trim()
        if (model.isEmpty()) throw CategoryException(ErrorCode.E422, "型号不能为空")
        if (model.length > MAX_NAME_LENGTH) {
            throw CategoryException(ErrorCode.E422, "型号不能超过 $MAX_NAME_LENGTH 个字符")
        }

        val batchTags = normalizeBatchTags(cmd.batchTags)
        if (cmd.batchTagRequired && batchTags.isEmpty()) {
            throw CategoryException(ErrorCode.E422, "批号类型必填时，至少需要配置一个批号类型")
        }

        return CategoryWriteModel(
            categoryName = categoryName,
            model = model,
            unit = cmd.unit,
            batchTagRequired = cmd.batchTagRequired,
            batchTags = batchTags,
            packingSpec = normalizePackingSpec(cmd.unit, cmd.packingSpec),
            enabled = cmd.enabled,
        )
    }

    /**
     * 批号类型规范化：裁剪、按大小写不敏感去重（保留首次写法与顺序）、限制长度与数量。
     *
     * 去重口径与 `category_batch_tag.tag COLLATE NOCASE` 保持一致，避免同一型号出现
     * 仅大小写不同的两个「同义」批号类型。
     */
    private fun normalizeBatchTags(rawTags: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>(rawTags.size)
        rawTags.forEach { raw ->
            val tag = raw.trim()
            if (tag.isEmpty()) throw CategoryException(ErrorCode.E422, "批号类型不能为空白")
            if (tag.length > MAX_TAG_LENGTH) {
                throw CategoryException(ErrorCode.E422, "批号类型不能超过 $MAX_TAG_LENGTH 个字符")
            }
            if (tag.any { it.isISOControl() }) {
                throw CategoryException(ErrorCode.E422, "批号类型不能包含控制字符")
            }
            if (seen.add(tag.lowercase())) result += tag
        }
        if (result.size > MAX_TAG_COUNT) {
            throw CategoryException(ErrorCode.E422, "批号类型最多 $MAX_TAG_COUNT 个")
        }
        return result
    }

    /** 装箱规格校验；`packingSpec = null` 表示该型号未配置装箱规格。 */
    private fun normalizePackingSpec(unit: PricingUnit, spec: PackingSpec?): PackingSpec? {
        if (spec == null) return null

        if (spec.baseUnit != unit) {
            throw CategoryException(
                ErrorCode.E422,
                "装箱规格的基准单位（${spec.baseUnit.label}）必须与计价单位（${unit.label}）一致",
            )
        }
        if ((spec.packageUnit == null) != (spec.unitsPerPackage == null)) {
            throw CategoryException(ErrorCode.E422, "包装单位与每包装数量必须同时提供")
        }
        val unitsPerPackage = spec.unitsPerPackage
        if (unitsPerPackage != null && unitsPerPackage <= 0) {
            throw CategoryException(ErrorCode.E422, "每包装数量必须大于 0")
        }

        val specText = spec.specText?.trim()?.takeIf { it.isNotEmpty() }
        if (specText != null && specText.length > MAX_SPEC_TEXT_LENGTH) {
            throw CategoryException(ErrorCode.E422, "规格描述不能超过 $MAX_SPEC_TEXT_LENGTH 个字符")
        }

        return PackingSpec(
            baseUnit = unit,
            packageUnit = spec.packageUnit,
            unitsPerPackage = unitsPerPackage,
            weightPerPackageKg = normalizeDecimal(spec.weightPerPackageKg, "每包装重量"),
            volumePerPackageM3 = normalizeDecimal(spec.volumePerPackageM3, "每包装体积"),
            specText = specText,
        )
    }

    /** 十进制字符串校验：裁剪、空白视为未配置、必须是合法且非负的十进制数。 */
    private fun normalizeDecimal(raw: String?, label: String): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length > MAX_DECIMAL_LENGTH) {
            throw CategoryException(ErrorCode.E422, "${label}不能超过 $MAX_DECIMAL_LENGTH 个字符")
        }
        val value = try {
            BigDecimal(text)
        } catch (invalid: NumberFormatException) {
            throw CategoryException(ErrorCode.E422, "${label}必须是十进制数字：$text", invalid)
        }
        if (value.signum() < 0) throw CategoryException(ErrorCode.E422, "${label}不能为负数")
        return text
    }

    /** 「品类 + 型号」查重；命中即 `E409`（数据库唯一约束在并发下做最终裁决）。 */
    private fun ensureUnique(tx: TxContext, model: CategoryWriteModel, excludeId: Long?) {
        if (repository.existsByNameAndModel(tx, model.categoryName, model.model, excludeId)) {
            throw CategoryException(
                ErrorCode.E409,
                "型号已存在：${model.categoryName} / ${model.model}",
            )
        }
    }

    /** 唯一约束冲突 → `E409`；其它数据库故障原样上抛，由 S13 兜底为 5xx。 */
    private fun translate(failure: SQLException): Exception =
        if (failure.isUniqueViolation()) {
            CategoryException(ErrorCode.E409, "型号已存在（品类 + 型号必须唯一）", failure)
        } else {
            failure
        }

    private fun now(): String = OffsetDateTime.now(clock).format(timestampFormat)

    /**
     * 唯一约束冲突判定：不同 SQLite JDBC 版本可能上报基础码（19）、
     * 主键扩展码（1555）或唯一索引扩展码（2067），三者都按「重复」处理。
     *
     * 注意：基础码 19 也覆盖 CHECK 约束，因此这里同时要求扩展码或错误文案命中 UNIQUE，
     * 避免把「装箱规格 CHECK 失败」误报成 E409。
     */
    private fun SQLException.isUniqueViolation(): Boolean =
        errorCode == SQLITE_CONSTRAINT_PRIMARY_KEY ||
            errorCode == SQLITE_CONSTRAINT_UNIQUE ||
            sqlState == SQL_STATE_UNIQUE_VIOLATION ||
            message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true)

    private companion object {
        /** 品类名 / 型号的最大长度。 */
        const val MAX_NAME_LENGTH = 64

        /** 单个批号类型最大长度。 */
        const val MAX_TAG_LENGTH = 32

        /** 单个型号最多配置的批号类型数量。 */
        const val MAX_TAG_COUNT = 20

        /** 规格描述最大长度。 */
        const val MAX_SPEC_TEXT_LENGTH = 120

        /** 重量 / 体积十进制字符串最大长度。 */
        const val MAX_DECIMAL_LENGTH = 32

        // SQLite 约束错误码：基础码与扩展码（见 https://sqlite.org/rescode.html）。
        const val SQLITE_CONSTRAINT_PRIMARY_KEY = 1555
        const val SQLITE_CONSTRAINT_UNIQUE = 2067

        /** 标准 SQLState：唯一键冲突（部分驱动/版本用它替代 vendor code）。 */
        const val SQL_STATE_UNIQUE_VIOLATION = "23505"
    }
}
