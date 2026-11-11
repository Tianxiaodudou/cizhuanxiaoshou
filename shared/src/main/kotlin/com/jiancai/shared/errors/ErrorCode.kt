/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   37 行  类 ErrorCode
 * 第  101 行  对象 ?
 * 第  103 行  函数 fromWire
 */

package com.jiancai.shared.errors

import kotlinx.serialization.Serializable

/**
 * 全量错误码（唯一真源）。
 *
 * 线值即枚举名（E + HTTP 语义三位数 / 业务四位段号），序列化为字符串，如 `"E401"`。
 * 编码段位约定：
 * - E4xx / E5xx：HTTP 通用错误；
 * - E1xxx：pricing（S06）；
 * - E2xxx：order（S08）；
 * - E3xxx：return-check（S09）；
 * - E4xxx：note（S10）；
 * - E5xxx：settlement（S11）；
 * - E6xxx：account / auth（S02 / S03）；
 * - E7xxx：print（S12）；
 * - E9xxx：serial 取号兜底（S07）。
 *
 * 只增不改：新增错误码追加在对应段位末尾，禁止改动已有名称 / 语义；
 * HTTP 状态、分类、默认文案集中在此，两端与 S13 禁止另写映射表。
 *
 * @property httpStatus 网关应返回的 HTTP 状态码
 * @property category 错误分类（决定日志与客户端动作）
 * @property message 默认用户文案（可按接口覆盖为更具体文案，但仍在服务端产出）
 * @property retryable 客户端是否可自动重试（网络/并发兜底类为 true）
 */
@Serializable
enum class ErrorCode(
    val httpStatus: Int,
    val category: ErrorCategory,
    val message: String,
    val retryable: Boolean = false,
) {
    // —— 通用（HTTP 语义）——
    E400(400, ErrorCategory.VALIDATION, "请求格式错误"),
    E401(401, ErrorCategory.AUTH, "未登录或登录已失效"),
    E403(403, ErrorCategory.PERMISSION, "无权限"),
    E404(404, ErrorCategory.NOT_FOUND, "资源不存在"),
    E409(409, ErrorCategory.CONFLICT, "请求重复，已处理过"),
    E413(413, ErrorCategory.VALIDATION, "数据量超过允许上限"),
    E422(422, ErrorCategory.VALIDATION, "参数校验失败"),
    E429(429, ErrorCategory.INTERNAL, "请求过于频繁，请稍后重试", retryable = true),
    E500(500, ErrorCategory.INTERNAL, "服务器内部错误"),
    E503(503, ErrorCategory.INTERNAL, "服务暂不可用", retryable = true),

    // —— S06 pricing ——
    E1001(409, ErrorCategory.BUSINESS, "价格已跨日锁定，不可修改"),
    E1002(404, ErrorCategory.NOT_FOUND, "未配置价格，请先定价"),
    E1003(422, ErrorCategory.VALIDATION, "改价原因必填"),

    // —— S08 order ——
    E2001(404, ErrorCategory.NOT_FOUND, "订单不存在"),
    E2002(409, ErrorCategory.CONFLICT, "订单已被修改，请刷新后重试"),
    E2003(409, ErrorCategory.BUSINESS, "仅当日订单可修改"),
    E2004(409, ErrorCategory.BUSINESS, "订单已结清，不可修改"),
    E2005(422, ErrorCategory.VALIDATION, "订单明细不能为空"),
    E2006(422, ErrorCategory.VALIDATION, "数量必须大于 0"),
    E2007(422, ErrorCategory.VALIDATION, "批号与型号不匹配"),

    // —— S09 return-check ——
    E3001(404, ErrorCategory.NOT_FOUND, "原订单不存在"),
    E3002(422, ErrorCategory.BUSINESS, "该批次不可退"),
    E3003(422, ErrorCategory.BUSINESS, "可退数量不足"),
    E3004(409, ErrorCategory.BUSINESS, "原单已作废，不可退货"),
    E3005(422, ErrorCategory.BUSINESS, "退货批次与原单不符"),

    // —— S10 note ——
    E4001(404, ErrorCategory.NOT_FOUND, "备注单不存在"),
    E4002(409, ErrorCategory.BUSINESS, "备注单已作废"),
    E4003(404, ErrorCategory.NOT_FOUND, "原订单不存在，无法创建备注单"),

    // —— S11 settlement ——
    E5001(404, ErrorCategory.NOT_FOUND, "结算单不存在"),
    E5002(422, ErrorCategory.BUSINESS, "结算金额超过未收金额"),
    E5003(409, ErrorCategory.BUSINESS, "结算单已审核，不可重复审核"),
    E5004(409, ErrorCategory.CONFLICT, "结算单重复提交"),

    // —— S02 auth / S03 account ——
    E6001(409, ErrorCategory.CONFLICT, "账号已存在"),
    E6002(401, ErrorCategory.AUTH, "用户名或密码错误"),
    E6003(409, ErrorCategory.BUSINESS, "不能删除最后一个管理员"),
    E6004(403, ErrorCategory.PERMISSION, "账号已禁用"),
    E6005(404, ErrorCategory.NOT_FOUND, "账号不存在"),

    // —— S12 print ——
    E7001(404, ErrorCategory.NOT_FOUND, "打印数据不存在"),
    E7002(409, ErrorCategory.BUSINESS, "打印次数已达上限"),

    // —— S07 serial ——
    E9001(409, ErrorCategory.CONFLICT, "单号生成冲突，请重试", retryable = true);

    companion object {
        /** 客户端统一解析入口：未知错误码返回 null，由 C00 用通用文案兜底（前向兼容）。 */
        fun fromWire(code: String): ErrorCode? = values().firstOrNull { it.name == code }
    }
}
