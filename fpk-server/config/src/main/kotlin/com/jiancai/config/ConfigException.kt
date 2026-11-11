/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   14 行  类 ConfigException
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
