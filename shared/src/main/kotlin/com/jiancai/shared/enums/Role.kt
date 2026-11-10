package com.jiancai.shared.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 账号角色（唯一真源）。
 *
 * 本枚举只描述角色身份，具体权限判断统一在 S13 api-gateway 拦截，
 * 业务模块与客户端不得各自写角色字符串。
 */
@Serializable
enum class Role(val label: String) {
    @SerialName("admin")
    ADMIN("管理员"),

    @SerialName("staff")
    STAFF("普通账号"),
}
