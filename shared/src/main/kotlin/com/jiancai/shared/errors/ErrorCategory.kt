package com.jiancai.shared.errors

/**
 * 错误分类（唯一真源）：S13 api-gateway 按分类统一映射 HTTP 状态、日志级别与客户端动作。
 *
 * 分类只描述「错误性质」，不描述具体业务；具体错误码见 [ErrorCode]。
 */
enum class ErrorCategory(val label: String) {
    /** 未登录 / token 失效：客户端应回登录页（C01 统一处理）。 */
    AUTH("登录态"),

    /** 已登录但无权限：由 S13 拦截后返回。 */
    PERMISSION("权限"),

    /** 入参校验失败：客户端提示用户修改输入。 */
    VALIDATION("参数校验"),

    /** 资源不存在。 */
    NOT_FOUND("资源不存在"),

    /** 并发 / 幂等冲突：客户端应刷新后重试。 */
    CONFLICT("冲突"),

    /** 业务规则不允许：文案直接展示给用户。 */
    BUSINESS("业务规则"),

    /** 服务内部错误：客户端展示通用文案，可重试项见 [ErrorCode.retryable]。 */
    INTERNAL("服务内部"),
}
