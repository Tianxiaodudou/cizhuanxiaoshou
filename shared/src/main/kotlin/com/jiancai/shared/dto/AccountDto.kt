/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   20 行  类 AccountDto
 * 第   31 行  类 LoginCmd
 * 第   43 行  类 LoginResultDto
 * 第   52 行  类 CreateAccountCmd
 * 第   61 行  类 UpdateAccountCmd
 */

package com.jiancai.shared.dto

import com.jiancai.shared.CONTRACT_VERSION
import com.jiancai.shared.enums.Role
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 账号（唯一出参 DTO，S03 account 产出；S02 auth / C11 消费）。密码永不下发。 */
@Serializable
data class AccountDto(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("role") val role: Role,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("created_at") val createdAt: String,
)

/** 登录入参（S02）。 */
@Serializable
data class LoginCmd(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
)

/**
 * 登录结果（S02 签发；C01 是唯一 token 判断点）。
 *
 * [contractVersion] 让客户端在登录时即可核对契约版本，不一致时提示升级。
 * token 只存放于客户端会话层，不进入业务 DTO。
 */
@Serializable
data class LoginResultDto(
    @SerialName("token") val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("account") val account: AccountDto,
    @SerialName("contract_version") val contractVersion: String = CONTRACT_VERSION,
)

/** 新增账号入参（S03，仅管理员）。 */
@Serializable
data class CreateAccountCmd(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("role") val role: Role,
)

/** 修改账号入参（S03，仅管理员；[password] 为空表示不改密码）。 */
@Serializable
data class UpdateAccountCmd(
    @SerialName("id") val id: Long,
    @SerialName("display_name") val displayName: String,
    @SerialName("role") val role: Role,
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("password") val password: String? = null,
)
