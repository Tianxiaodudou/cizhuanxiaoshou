package czxs

import java.io.File
import kotlin.system.exitProcess

/**
 * 启动入口：
 * - TCP 端口只起 /api 前缀（JSON），绝不返回 HTML（规格 49）；
 * - Unix Socket 属于飞牛统一网关（app/czxs），只起 /admin 前缀（HTML）。
 */
fun main(args: Array<String>) {
    val appHome = System.getenv("TRIM_APPDEST")
        ?: args.firstOrNull()
        ?: File(".").absolutePath
    val cfg = Config.load(appHome)
    val db = Db(cfg.dbPath)
    db.migrate()
    val repo = Repo(db)
    val svc = Services(db, repo, cfg)
    val api = Api(svc)
    val admin = Admin(svc, cfg)

    val server = HttpServer { req ->
        api.handle(req)
    }
    server.startTcp(cfg.apiPort)

    // 网关：剥掉配置里的前缀后再分发（前缀只在 FPK 配置出现一次，规格 54）
    val gateway = HttpServer { req ->
        val path = if (req.path.startsWith(cfg.gatewayPrefix)) req.path.removePrefix(cfg.gatewayPrefix).ifBlank { "/" } else req.path
        val stripped = req.copy(path = path)
        when {
            path.startsWith("/admin") -> admin.handle(stripped)
            path == "/health" -> Json.okRaw("""{"app":"${cfg.appName}","version":"${cfg.version}"}""")
            else -> Resp(404, "text/html; charset=utf-8", "<h1>404</h1>")
        }
    }
    gateway.startUnix(cfg.adminSocket)

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { File(cfg.adminSocket).delete() }
    })

    println("czxs 已启动：api=0.0.0.0:${cfg.apiPort} gateway=${cfg.gatewayPrefix} socket=${cfg.adminSocket} db=${cfg.dbPath}")
    if (System.getenv("CZ_XS_DAEMON") == "1") {
        Thread.currentThread().join()
    }
}

@Suppress("unused")
private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
