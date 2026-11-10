package com.jiancai.shared

import kotlinx.serialization.json.Json

/**
 * 契约层版本号（唯一真源）。
 *
 * 两端（fpk-server / android-app）、网关握手、客户端缓存键均以此为准。
 * `shared/contract.version` 是给构建脚本 / CI / 工作台读取的镜像文件，两者必须一致，
 * 由 `ContractTest.contract_version_matches_mirror_file` 强制校验。
 *
 * 变更规则见 [shared/module.md](../module.md)：
 * - 只追加（新可空字段、新枚举项、新错误码）→ 次版本号 +1；
 * - 删除字段、改字段名 / 枚举线值 / 金额格式、改已有语义 → 主版本号 +1；
 * - 已有字段签名只增不改，废弃用 `@Deprecated` + 新旧双通。
 */
const val CONTRACT_VERSION: String = "1.0.0"

/** 网关响应头：当前契约版本（C00 握手校验 / S13 输出共用，两端禁止另写字符串）。 */
const val HEADER_CONTRACT_VERSION: String = "X-Contract-Version"

/** 鉴权请求头（C00 注入 / S13 校验共用）。 */
const val HEADER_AUTHORIZATION: String = "Authorization"

/** 鉴权方案前缀（Bearer）。 */
const val AUTH_SCHEME_BEARER: String = "Bearer "

/** 幂等键请求头（S13 校验；与 S08 按 client_request_id 落库的口径一致）。 */
const val HEADER_IDEMPOTENCY_KEY: String = "Idempotency-Key"

/**
 * 双端共用的 JSON 配置。
 *
 * - `ignoreUnknownKeys = true`：服务端新增字段不破坏旧客户端（契约只增不改的运行时保障）；
 * - `encodeDefaults = true`：默认值字段也上线，避免两端对缺省字段理解不一致。
 *
 * 线格式（字段名、枚举值、金额字符串）以 dto / enums 中的 `@SerialName` 为唯一真源，
 * 两端禁止各自 `Json { ... }` 另配字段命名策略。
 */
object ContractJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
