/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   18 行  类 Role
 */

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
