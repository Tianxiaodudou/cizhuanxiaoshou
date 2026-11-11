/*
 * @file shared/src/main/kotlin/com/jiancai/shared/dto/CustomerDto.kt
 * @role 客户（唯一出参 DTO，S04 customer 产出）。
 * @exports CustomerDto, CreateCustomerCmd
 * @depends （无）
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayRoutes.kt, fpk-server/customer/src/main/kotlin/com/jiancai/customer/CustomerPort.kt, fpk-server/customer/src/main/kotlin/com/jiancai/customer/CustomerRepository.kt
 * @sideEffects 无（纯计算 / 转发）
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
