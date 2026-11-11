/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   31 行  类 ReturnableBatchDto
 * 第   49 行  类 ReturnCheckResultDto
 * 第   60 行  类 ReturnCheckLineDto
 */

package com.jiancai.shared.dto

import com.jiancai.shared.units.PricingUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * S09 return-check 的跨端出参 DTO（1.1.0 起由 shared 持有唯一真源）。
 *
 * 与 S08 的 OrderDto 同一套约定：`@SerialName` snake_case、只出 DTO、
 * 数量全部是服务端产出的规范十进制字符串（去尾零、最多 3 位小数，如 "10"、"1.5"），
 * 客户端只展示，不得做任何加减比较（铁律 5）。
 */

/**
 * 可退批次（S09 产出）：原单上某个「型号 + 批号 + 单位」维度还能退多少。
 *
 * 一个批次可能对应原单上的多行明细，[orderedQuantity] 是这些行的数量之和；
 * [returnedQuantity] 是该客户已经开过的退货单里同一维度的数量之和；
 * [returnableQuantity] = 订购 − 已退（≤ 0 的批次不会出现在结果里）。
 */
@Serializable
data class ReturnableBatchDto(
    @SerialName("order_no") val orderNo: String,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("category_name") val categoryName: String,
    @SerialName("model") val model: String,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("unit") val unit: PricingUnit,
    @SerialName("ordered_quantity") val orderedQuantity: String,
    @SerialName("returned_quantity") val returnedQuantity: String,
    @SerialName("returnable_quantity") val returnableQuantity: String,
)

/**
 * 退单校验结果（S09 产出）：通过校验后，调用方（S13 → S08 `create`）可据此直接建退货单。
 *
 * 校验不通过时抛异常（错误码见 [com.jiancai.shared.errors.ErrorCode]），不会返回部分通过的结果。
 */
@Serializable
data class ReturnCheckResultDto(
    @SerialName("order_no") val orderNo: String,
    @SerialName("lines") val lines: List<ReturnCheckLineDto>,
)

/**
 * 校验通过的一行：回显服务端认定的批次写法与数量口径，供客户端只读展示。
 *
 * [remainingQuantity] 是本批次扣掉本次申请后的剩余可退量（同批次多行时按累加口径计算）。
 */
@Serializable
data class ReturnCheckLineDto(
    @SerialName("category_id") val categoryId: Long,
    @SerialName("category_name") val categoryName: String,
    @SerialName("model") val model: String,
    @SerialName("batch_tag") val batchTag: String,
    @SerialName("unit") val unit: PricingUnit,
    @SerialName("requested_quantity") val requestedQuantity: String,
    @SerialName("returnable_quantity") val returnableQuantity: String,
    @SerialName("remaining_quantity") val remainingQuantity: String,
)
