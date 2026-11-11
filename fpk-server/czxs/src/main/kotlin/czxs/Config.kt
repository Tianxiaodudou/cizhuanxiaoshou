package czxs

import java.io.File
import java.util.Properties

/**
 * 运行配置：端口号、数据库路径、网关前缀只在配置里定义一次（规格 50/54）。
 *
 * 优先级：环境变量 > etc/czxs.properties > 默认值。
 */
data class Config(
    val appName: String,
    val version: String,
    val dbPath: String,
    val apiPort: Int,
    val adminSocket: String,
    val gatewayPrefix: String,
    val dataDir: String,
    val printDir: String,
) {
    companion object {
        fun load(appHome: String): Config {
            val etc = File(appHome, "etc")
            val props = Properties()
            val file = File(etc, "czxs.properties")
            if (file.isFile) file.reader(Charsets.UTF_8).use { props.load(it) }

            fun str(key: String, def: String): String =
                System.getenv("CZ_XS_" + key.uppercase()) ?: props.getProperty(key) ?: def

            val dataDir = str("data_dir", File(appHome, "data").path)
            File(dataDir).mkdirs()
            val printDir = str("print_dir", File(dataDir, "print").path)
            File(printDir).mkdirs()

            return Config(
                appName = str("app_name", "czxs"),
                version = str("version", "1.1.0"),
                dbPath = str("db_path", File(dataDir, "orders.sq3").path),
                apiPort = str("api_port", "8080").toIntOrNull() ?: 8080,
                adminSocket = str("admin_socket", File(appHome, "target/app.sock").path),
                gatewayPrefix = str("gateway_prefix", "/app/czxs").trimEnd('/'),
                dataDir = dataDir,
                printDir = printDir,
            )
        }
    }
}
