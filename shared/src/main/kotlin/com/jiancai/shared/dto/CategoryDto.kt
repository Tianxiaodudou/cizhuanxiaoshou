/*
 * @file shared/src/main/kotlin/com/jiancai/shared/dto/CategoryDto.kt
 * @role 品类 / 型号（唯一出参 DTO，S05 category 产出）。
 * @exports CategoryDto, CategoryCmd
 * @depends （无）
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayRoutes.kt, fpk-server/category/src/main/kotlin/com/jiancai/category/CategoryPort.kt, fpk-server/category/src/main/kotlin/com/jiancai/category/CategoryRepository.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.shared.dto

import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 品类 / 型号（唯一出参 DTO，S05 category 产出）。
 *
 * @property batchTags 批号类型可选值（服务端配置，客户端只展示选择，不得自行拼批号）
 * @property packingSpec 装箱规格（单位换算的唯一数据来源）
 */
@Serializable
data class CategoryDto(
    @SerialName("id") val id: Long,
    @SerialName("category_name") val categoryName: String,
    @SerialName("model") val model: String,
    @SerialName("unit") val unit: PricingUnit,
    @SerialName("batch_tag_required") val batchTagRequired: Boolean = false,
    @SerialName("batch_tags") val batchTags: List<String> = emptyList(),
    @SerialName("packing_spec") val packingSpec: PackingSpec? = null,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * 型号库新增 / 修改入参（S05，仅管理员；权限由 S13 拦截）。
 * `id` 为空表示新建，非空表示修改。
 */
@Serializable
data class CategoryCmd(
    @SerialName("id") val id: Long? = null,
    @SerialName("category_name") val categoryName: String,
    @SerialName("model") val model: String,
    @SerialName("unit") val unit: PricingUnit,
    @SerialName("batch_tag_required") val batchTagRequired: Boolean = false,
    @SerialName("batch_tags") val batchTags: List<String> = emptyList(),
    @SerialName("packing_spec") val packingSpec: PackingSpec? = null,
    @SerialName("enabled") val enabled: Boolean = true,
)
