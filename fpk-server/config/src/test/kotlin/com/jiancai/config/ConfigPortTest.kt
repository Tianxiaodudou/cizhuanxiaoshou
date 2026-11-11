/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   29 行  类 ConfigPortTest
 * 第   32 行  函数 merchant_name_comes_from_the_server_side_deployment_config
 * 第   45 行  函数 merchant_name_is_persisted_and_reused_when_the_deployment_config_is_absent
 * 第   56 行  函数 deployment_config_overrides_the_persisted_name_on_restart
 * 第   73 行  函数 restarting_with_the_same_deployment_config_does_not_rewrite_the_row
 * 第   99 行  函数 missing_deployment_config_and_empty_database_fails_fast
 * 第  113 行  函数 config_table_holds_one_deployment_row_and_rejects_a_second
 * 第  130 行  函数 blank_merchant_name_is_rejected
 * 第  141 行  函数 deployment_config_comes_only_from_server_side_process_settings
 */

package com.jiancai.config

import com.jiancai.db.DbCore
import com.jiancai.shared.dto.ConfigDto
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 验收：商家名称由服务端部署配置生效，客户端无法传入 / 改写；配置持久化且可重启复用。
 */
class ConfigPortTest {

    @Test
    fun merchant_name_comes_from_the_server_side_deployment_config() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            val config = ConfigService(db.txRunner, DeploymentConfig.of("宁波建材"))
            assertEquals("宁波建材", config.getMerchantName(), "商家名称必须来自部署配置，不能写死")
            assertEquals(
                ConfigDto(merchantName = "宁波建材"),
                config.getConfig(),
                "ConfigDto 必须与 Port 读取的名称同源",
            )
        }
    }

    @Test
    fun merchant_name_is_persisted_and_reused_when_the_deployment_config_is_absent() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            ConfigService(db.txRunner, DeploymentConfig.of("宁波建材"))

            // 模拟重启：不再传部署参数，仍应从库里读到同一个名称。
            val restarted = ConfigService(db.txRunner, deploymentConfig = null)
            assertEquals("宁波建材", restarted.getMerchantName())
        }
    }

    @Test
    fun deployment_config_overrides_the_persisted_name_on_restart() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            ConfigService(db.txRunner, DeploymentConfig.of("旧名称"))
            val restarted = ConfigService(db.txRunner, DeploymentConfig.of("新名称"))

            assertEquals("新名称", restarted.getMerchantName(), "部署参数是权威来源，启动时应覆盖旧值")
            assertEquals(
                1L,
                db.txRunner.run { tx ->
                    tx.queryLong("SELECT COUNT(*) FROM deployment_config WHERE merchant_name = '新名称'")
                },
                "覆盖后仍必须只有一行部署配置",
            )
        }
    }

    @Test
    fun restarting_with_the_same_deployment_config_does_not_rewrite_the_row() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            ConfigService(
                db.txRunner,
                DeploymentConfig.of("宁波建材"),
                clockAt("2026-11-11T00:00:00Z"),
            )
            val first = db.txRunner.run { tx ->
                tx.queryString("SELECT updated_at FROM deployment_config WHERE id = 1")
            }

            // 同一名称再次启动：不应写库（updated_at 保持首次写入的时间）。
            ConfigService(
                db.txRunner,
                DeploymentConfig.of("宁波建材"),
                clockAt("2026-11-12T00:00:00Z"),
            )
            val second = db.txRunner.run { tx ->
                tx.queryString("SELECT updated_at FROM deployment_config WHERE id = 1")
            }

            assertEquals(first, second)
        }
    }

    @Test
    fun missing_deployment_config_and_empty_database_fails_fast() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            assertFailsWith<ConfigException> {
                ConfigService(db.txRunner, deploymentConfig = null)
            }
            assertEquals(
                0L,
                db.txRunner.run { tx -> tx.queryLong("SELECT COUNT(*) FROM deployment_config") },
                "启动失败时不能写入占位名称",
            )
        }
    }

    @Test
    fun config_table_holds_one_deployment_row_and_rejects_a_second() {
        DbCore.testDatabase(ConfigMigrations.all).use { db ->
            ConfigService(db.txRunner, DeploymentConfig.of("宁波建材"))

            db.txRunner.run { tx ->
                assertEquals(1L, tx.queryLong("SELECT COUNT(*) FROM deployment_config"))
                assertFailsWith<SQLException> {
                    tx.exec(
                        "INSERT INTO deployment_config (id, merchant_name, updated_at) " +
                            "VALUES (2, '其它商家', '2026-11-11T00:00:00Z')",
                    )
                }
            }
        }
    }

    @Test
    fun blank_merchant_name_is_rejected() {
        assertFailsWith<IllegalArgumentException> { DeploymentConfig.of("   ") }
        assertFailsWith<ConfigException> {
            DeploymentConfig.fromEnvironment(
                env = mapOf(DeploymentConfig.ENV_MERCHANT_NAME to "   "),
                properties = Properties(),
            )
        }
    }

    @Test
    fun deployment_config_comes_only_from_server_side_process_settings() {
        val properties = Properties()
        properties.setProperty(DeploymentConfig.PROPERTY_MERCHANT_NAME, " 系统属性商家 ")
        assertEquals(
            "系统属性商家",
            DeploymentConfig.fromEnvironment(
                env = mapOf(DeploymentConfig.ENV_MERCHANT_NAME to "环境变量商家"),
                properties = properties,
            )?.merchantName,
            "JVM 参数优先于环境变量",
        )

        assertEquals(
            "环境变量商家",
            DeploymentConfig.fromEnvironment(
                env = mapOf(DeploymentConfig.ENV_MERCHANT_NAME to " 环境变量商家 "),
                properties = Properties(),
            )?.merchantName,
        )

        assertNull(
            DeploymentConfig.fromEnvironment(env = emptyMap(), properties = Properties()),
            "未配置时应返回 null，由 ConfigService 决定回退或启动失败",
        )
    }
}
