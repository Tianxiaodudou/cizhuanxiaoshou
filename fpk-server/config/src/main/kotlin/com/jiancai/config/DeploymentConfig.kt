/*
 * @file fpk-server/config/src/main/kotlin/com/jiancai/config/DeploymentConfig.kt
 * @role 服务端部署配置（当前只有商家名称）。
 * @exports DeploymentConfig
 * @depends （无）
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/App.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/Main.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.config

import java.util.Properties

/**
 * 服务端部署配置（当前只有商家名称）。
 *
 * **客户端不可传**：只允许来自服务端进程的启动参数。
 * 解析优先级（前者覆盖后者）：
 * 1. JVM 系统属性 `-Djiancai.merchantName=...`；
 * 2. 环境变量 `JIANCAI_MERCHANT_NAME`。
 *
 * 两者都未设置时 [fromEnvironment] 返回 null，由 [ConfigService] 决定回退到库中已保存的值，
 * 或启动失败——**绝不**使用写死的默认商家名（铁律：禁止写死商家名）。
 */
class DeploymentConfig private constructor(
    /** 已去除首尾空白的非空商家名称。 */
    val merchantName: String,
) {

    companion object {
        /** 环境变量名（部署方设置）。 */
        const val ENV_MERCHANT_NAME: String = "JIANCAI_MERCHANT_NAME"

        /** JVM 系统属性名（部署方可选覆盖）。 */
        const val PROPERTY_MERCHANT_NAME: String = "jiancai.merchantName"

        /**
         * 规范化构造：去除首尾空白，空白值直接拒绝。
         *
         * @throws IllegalArgumentException 商家名称为空或只有空白
         */
        fun of(raw: String): DeploymentConfig {
            val normalized = raw.trim()
            require(normalized.isNotEmpty()) { "商家名称不能为空" }
            return DeploymentConfig(normalized)
        }

        /**
         * 从服务端启动参数读取部署配置。
         *
         * @param env 进程环境变量（测试可注入，生产用默认值）
         * @param properties JVM 系统属性（测试可注入，生产用默认值）
         * @return 未配置时返回 null；配置了但为空白时抛 [ConfigException]（部署错误，不能静默忽略）
         */
        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            properties: Properties = System.getProperties(),
        ): DeploymentConfig? {
            val raw = properties.getProperty(PROPERTY_MERCHANT_NAME)
                ?: env[ENV_MERCHANT_NAME]
                ?: return null
            if (raw.isBlank()) {
                throw ConfigException(
                    "部署配置 $PROPERTY_MERCHANT_NAME / $ENV_MERCHANT_NAME 已设置但为空；" +
                        "商家名称必须由部署方提供",
                )
            }
            return of(raw)
        }
    }
}
