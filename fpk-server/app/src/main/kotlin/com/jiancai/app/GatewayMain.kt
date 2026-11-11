/*
 * @file fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @role 常驻 HTTP 服务入口（组合根）：打开库 → 迁移 → 装配 S00–S14 → 启动 S13 网关。
 * @exports main, AllMigrations
 * @depends account, auth, category, config, customer, db-core, api-gateway, note, observability, order, pricing, print, return-check, serial, settlement, shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.app

import com.jiancai.account.AccountAdminPort
import com.jiancai.auth.AuthException
import com.jiancai.auth.AuthPort
import com.jiancai.category.CategoryPort
import com.jiancai.category.CategoryService
import com.jiancai.config.ConfigPort
import com.jiancai.config.DeploymentConfig
import com.jiancai.customer.CustomerPort
import com.jiancai.customer.CustomerService
import com.jiancai.db.DbConfig
import com.jiancai.db.DbCore
import com.jiancai.db.Migration
import com.jiancai.gateway.ApiGateway
import com.jiancai.gateway.GatewayDeps
import com.jiancai.gateway.GatewayMigrations
import com.jiancai.gateway.HttpServerAdapter
import com.jiancai.gateway.SqliteIdempotencyStore
import com.jiancai.note.NotePort
import com.jiancai.note.NoteService
import com.jiancai.observability.AuditPort
import com.jiancai.observability.AuditService
import com.jiancai.observability.ObservabilityMigrations
import com.jiancai.order.OrderPort
import com.jiancai.order.OrderService
import com.jiancai.pricing.PricePort
import com.jiancai.pricing.PriceService
import com.jiancai.print.PrintPort
import com.jiancai.print.PrintService
import com.jiancai.returncheck.ReturnCheckPort
import com.jiancai.returncheck.ReturnCheckService
import com.jiancai.serial.SerialPort
import com.jiancai.settlement.SettlePort
import com.jiancai.settlement.SettleService
import com.jiancai.shared.errors.ErrorCode
import java.time.Clock

/**
 * 常驻 HTTP 服务入口（组合根）：打开库 → 迁移 → 装配 S00–S14 → 启动 S13 网关。
 *
 * 与 [main]（一次性自检 / 引导）的区别：本入口**不退出**，一直监听端口，供客户端接入。
 *
 * 必需的环境变量：
 * - `JIANCAI_DB_PATH`      SQLite 文件路径（父目录自动创建）；
 * - `JIANCAI_AUTH_SECRET`  token 签名密钥（≥32 字节，禁止硬编码）；
 * - 商家名称：`JIANCAI_MERCHANT_NAME` 或 `-Djiancai.merchantName`（新建库时必需）。
 *
 * 可选：
 * - `JIANCAI_HTTP_HOST` 监听地址，默认 `0.0.0.0`（门店内网 / 真机联调）；
 * - `JIANCAI_HTTP_PORT` 监听端口，默认 `8080`；
 * - `JIANCAI_ADMIN_PASSWORD` 库里没有账号时创建初始管理员 `admin`。
 *
 * 装配顺序与各模块 `module.md` 一致：S04/S05 → S06 → S08 → S09/S10/S11/S12 → S14 → S13。
 */
fun main() {
    val dbPath = requireEnv("JIANCAI_DB_PATH")
    val secret = requireEnv("JIANCAI_AUTH_SECRET").toByteArray(Charsets.UTF_8)
    val host = System.getenv("JIANCAI_HTTP_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val port = System.getenv("JIANCAI_HTTP_PORT")?.toIntOrNull() ?: 8080
    val clock = Clock.systemDefaultZone()

    val db = DbCore.open(DbConfig.file(dbPath), AllMigrations.list)
    try {
        db.migrator.migrate()
    } catch (failure: Throwable) {
        runCatching { db.close() }
        throw failure
    }

    App.create(
        db = db,
        deploymentConfig = DeploymentConfig.fromEnvironment(),
        secret = secret,
    ).use { app ->
        System.getenv("JIANCAI_ADMIN_PASSWORD")?.takeIf { it.isNotBlank() }?.let { password ->
            try {
                app.bootstrapFirstAdmin(username = "admin", password = password, displayName = "管理员")
                println("已创建初始管理员：admin")
            } catch (failure: AuthException) {
                if (failure.code != ErrorCode.E6001) throw failure
                println("库中已有账号，跳过初始管理员创建")
            }
        }

        val deps = wire(db, app.config, app.auth, app.accountAdmin, app.serial, clock)
        val server = HttpServerAdapter(ApiGateway(deps))
        val bound = server.start(port = port, host = host)
        println(
            "网关已启动：http://$host:$bound（迁移版本=${db.migrator.currentVersion()}，" +
                "路由数=${ApiGateway(deps).routes().size}）",
        )

        // HTTP 服务器使用非守护线程，主线程退出后进程仍然存活；这里只等待进程被中断。
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })
        Thread.currentThread().join()
    }
}

/**
 * 全部迁移（S00–S14 + 网关幂等台账），组合根唯一合并点。
 *
 * 与 [App.migrations] 的关系：[App.migrations] 是「阶段一地基」子集，本列表是完整集合；
 * 两者都只增不改，新增模块只需要在这里追加一行。
 */
object AllMigrations {
    val list: List<Migration> = App.migrations + listOf(
        com.jiancai.customer.CustomerMigrations.all,
        com.jiancai.category.CategoryMigrations.all,
        com.jiancai.pricing.PricingMigrations.all,
        com.jiancai.order.OrderMigrations.all,
        com.jiancai.note.NoteMigrations.all,
        com.jiancai.settlement.SettlementMigrations.all,
        com.jiancai.print.PrintMigrations.all,
        ObservabilityMigrations.all,
        GatewayMigrations.all,
    ).flatten().sortedBy { it.version }
}

/** 把 S04–S12 的 Port 装配出来（S00–S07 已由 [App] 建好，这里只补业务链接线）。 */
private fun wire(
    db: DbCore,
    config: ConfigPort,
    auth: AuthPort,
    accountAdmin: AccountAdminPort,
    serial: SerialPort,
    clock: Clock,
): GatewayDeps {
    val customers: CustomerPort = CustomerService(db.txRunner, clock)
    val categories: CategoryPort = CategoryService(db.txRunner, clock)
    val prices: PricePort = PriceService(db.txRunner, customers, categories, clock)
    val orders: OrderPort = OrderService(db.txRunner, serial, prices, customers, categories, clock)
    val returns: ReturnCheckPort = ReturnCheckService(db.txRunner, orders)
    val notes: NotePort = NoteService(db.txRunner, orders, serial, clock)
    val settlements: SettlePort = SettleService(db.txRunner, orders, accountAdmin, serial, clock)
    val printing: PrintPort = PrintService(db.txRunner, orders, config, notes = null, clock = clock)
    val audit: AuditPort = AuditService(db.txRunner, clock)

    return GatewayDeps(
        txRunner = db.txRunner,
        auth = auth,
        accounts = accountAdmin,
        customers = customers,
        categories = categories,
        prices = prices,
        orders = orders,
        returns = returns,
        notes = notes,
        settlements = settlements,
        printing = printing,
        audit = audit,
        config = config,
        clock = clock,
        idempotency = SqliteIdempotencyStore(db.txRunner, clock),
    )
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("缺少环境变量 $name（见 fpk-server/app/module.md 的启动说明）")
