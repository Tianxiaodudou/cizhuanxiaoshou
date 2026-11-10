package com.jiancai.shared.dto

import com.jiancai.shared.CONTRACT_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 列表接口默认分页（两端统一，避免各自写 1 / 50 这类字面量）。 */
const val DEFAULT_PAGE: Int = 1

/** 列表接口默认每页条数。 */
const val DEFAULT_PAGE_SIZE: Int = 50

/**
 * 幂等键字段名：`CreateOrderCmd` 与 `SubmitSettleCmd` 共用（与请求头
 * [com.jiancai.shared.HEADER_IDEMPOTENCY_KEY] 成对），避免同一线值在多处手写。
 */
const val FIELD_CLIENT_REQUEST_ID: String = "client_request_id"

/**
 * 契约 / 服务握手信息（C00 启动校验，S13 提供）。
 *
 * @property contractVersion 服务端契约版本，与 [com.jiancai.shared.CONTRACT_VERSION] 同源
 * @property serverTime 服务端当前时间（[WireFormat.TIMESTAMP]），客户端不得用本地时间替代服务端业务日期
 * @property merchantName 部署级商家名称（S01 config 产出，只读展示）
 */
@Serializable
data class ContractInfoDto(
    @SerialName("contract_version") val contractVersion: String = CONTRACT_VERSION,
    @SerialName("server_time") val serverTime: String,
    @SerialName("merchant_name") val merchantName: String? = null,
)

/** 部署级配置（S01 config 产出，客户端不可上传，只能读取展示）。 */
@Serializable
data class ConfigDto(
    @SerialName("merchant_name") val merchantName: String,
)

/** 统一分页结果（列表接口出参，缺失分页语义的接口直接返回 List）。 */
@Serializable
data class PageDto<T>(
    @SerialName("items") val items: List<T>,
    @SerialName("total") val total: Long,
    @SerialName("page") val page: Int = DEFAULT_PAGE,
    @SerialName("page_size") val pageSize: Int = DEFAULT_PAGE_SIZE,
)

/**
 * 线格式日期 / 时间常量（唯一真源）：两端与网关统一使用，禁止各自写格式串。
 *
 * 服务端产出规范字符串，客户端只解析展示；业务日期以服务端本地日期为准。
 */
object WireFormat {
    /** 业务日期，如 `2026-11-10`。 */
    const val DATE: String = "yyyy-MM-dd"

    /** 时间戳（含时区偏移），如 `2026-11-10T15:30:00+08:00`。 */
    const val TIMESTAMP: String = "yyyy-MM-dd'T'HH:mm:ssXXX"
}
