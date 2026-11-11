/*
 * @file fpk-server/config/src/main/kotlin/com/jiancai/config/ConfigException.kt
 * @role 部署配置错误（S01）：缺少商家名称、配置为空白、配置表不可用等。
 * @exports ConfigException
 * @depends （无）
 * @calledBy fpk-server/config/src/main/kotlin/com/jiancai/config/ConfigService.kt, fpk-server/config/src/main/kotlin/com/jiancai/config/DeploymentConfig.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.config

/**
 * 部署配置错误（S01）：缺少商家名称、配置为空白、配置表不可用等。
 *
 * 这是**启动期**错误：服务应立即失败，不允许带病启动；它不属于客户端可见的业务错误，
 * 因此不映射 shared 的 `ErrorCode`——客户端无法通过请求修复部署配置（铁律：部署配置不可由客户端传）。
 */
class ConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
