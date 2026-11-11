/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   20 行  类 CustomerDto
 * 第   31 行  类 CreateCustomerCmd
 */

package com.jiancai.shared.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 客户（唯一出参 DTO，S04 customer 产出）。
 *
 * 订单里的地址为下单时的地址快照（见 [OrderDto.customerAddress]），
 * 客户资料后续修改不影响历史订单。
 */
@Serializable
data class CustomerDto(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("phone") val phone: String,
    @SerialName("address") val address: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** 客户建档入参（S04）。 */
@Serializable
data class CreateCustomerCmd(
    @SerialName("name") val name: String,
    @SerialName("phone") val phone: String,
    @SerialName("address") val address: String? = null,
)
