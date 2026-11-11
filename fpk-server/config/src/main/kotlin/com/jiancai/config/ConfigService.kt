/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   28 行  类 ConfigService
 * 第   39 行  函数 getMerchantName
 * 第   41 行  函数 getConfig
 * 第   43 行  函数 resolve
 */

package com.jiancai.config

import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.ConfigDto
import java.time.Clock
import java.time.Instant

/**
 * [ConfigPort] 的唯一实现：启动时把服务端部署配置落到库中，之后只读。
 *
 * 构造即完成配置生效（组合根在迁移之后创建一次）：
 * - 提供了部署配置且与库中不同 → 以部署参数为准写入（`TxRunner.run {}` 包裹）；
 * - 未提供但库中已有 → 沿用库中值（允许部署方重启时不再重复传参）；
 * - 两者都没有 → 抛 [ConfigException]，服务启动失败（不写占位值、不用写死的默认商家名）。
 *
 * 名称在实例生命周期内是不可变快照：部署配置只能由服务端启动参数或库中已存值决定，
 * 客户端没有任何写入通道，因此无需每次读库；变更部署配置后重启服务生效。
 */
class ConfigService(
    private val txRunner: TxRunner,
    deploymentConfig: DeploymentConfig? = DeploymentConfig.fromEnvironment(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : ConfigPort {

    private val repository = ConfigRepository()

    /** 启动时确定的不可变快照。 */
    private val merchantName: String = resolve(deploymentConfig)

    override fun getMerchantName(): String = merchantName

    override fun getConfig(): ConfigDto = ConfigDto(merchantName)

    private fun resolve(deploymentConfig: DeploymentConfig?): String = txRunner.run { tx ->
        val persisted = repository.findMerchantName(tx)
        val configured = deploymentConfig?.merchantName
        when {
            configured != null && configured != persisted -> {
                repository.save(tx, configured, Instant.now(clock).toString())
                configured
            }
            persisted != null -> persisted
            else -> throw ConfigException(
                "未配置商家名称：请设置环境变量 ${DeploymentConfig.ENV_MERCHANT_NAME} " +
                    "或 JVM 参数 -D${DeploymentConfig.PROPERTY_MERCHANT_NAME}=<商家名称>",
            )
        }
    }
}
