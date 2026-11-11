# APP · 组合根（不属于任何业务模块）

> 阶段一装配点：把 S00 db-core / S01 config / S02 auth / S03 account / S07 serial 唯一装配一次。

## 为什么需要它

S03 只声明 `AccountStore`，编译期**不依赖** `:fpk-server:auth`（依赖倒置，避免环）；
S02 提供结构相同的 `AccountStorePort`。两者签名靠一个薄适配器在编译期钉死——
谁先改签名，适配器立刻编译失败。这个适配器不属于任何业务模块，只能放在组合根。

在此之前「S03 → S02」只有文档约束，两边改动不会互相报警；本模块补上了这道编译期闸门。

## 目录

| 文件 | 内容 |
| --- | --- |
| `App.kt` | 组合根：合并迁移（`App.migrations`）、`open`（打开+迁移+装配）、`create`（在已有库上装配）、`bootstrapFirstAdmin` |
| `AccountStoreAdapter.kt` | S03 `AccountStore` ← S02 `AccountStorePort`：只转调 + `AuthException → AccountAdminException`，透传调用方事务 |
| `Main.kt` | 启动自检 / 一次性引导命令（**不是** HTTP 服务；网关 S13 落地后复用 `App`） |

测试：`AppTest`（`fpk-server/app/src/test/kotlin/com/jiancai/app/AppTest.kt`）用真实内存 SQLite 跑通：

- 迁移合并（版本 100/200/210）后，serial / config / auth 三个 Port 都可用；
- S03 建档 → S02 登录成功，库里 `password_hash` 是 `pbkdf2-` 开头（不是明文）；
- 重名 `E6001`、短密码 `E422` 被适配器翻译成 `AccountAdminException`（`cause` 保留原始 `AuthException`）；
- 最后管理员守卫 `E6003`、不存在 `E6005` 照常生效；
- 适配器透传 `TxContext`，调用方回滚后账号不落库。

## 装配顺序

```kotlin
val app = App.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), DeploymentConfig.fromEnvironment(), secret)
//   = DbCore.open(...) + migrator.migrate() + App.create(...)
//      ConfigService → AuthService → AccountStoreAdapter → AccountAdminService / SerialService
app.use {
    it.bootstrapFirstAdmin("admin", System.getenv("JIANCAI_ADMIN_PASSWORD"), "管理员")  // 仅空库
    val auth: AuthPort = it.auth
    val accountAdmin: AccountAdminPort = it.accountAdmin
}
```

`secret` ≥32 字节，由部署方提供（`JIANCAI_AUTH_SECRET`），禁止硬编码。

## 边界

- **不含业务规则**：不判权限、不写 SQL、不做 DTO 转换；权限判断只在 S13（`verify → ADMIN`）。
- **不拥有表**：不新增迁移，只合并各模块的 `Migrations.all`；阶段二模块在 S13 装配时追加。
- **S03/S02 不需要为它改动**：`AccountStore` / `AccountStorePort` 的签名就是契约，改契约才需要动适配器。
- 启动自检退出即关库；常驻 HTTP 服务与路由由 S13 api-gateway 提供。

## 待确认

- 模块名 `app`（也可叫 `bootstrap`）；S13 落地后可把 `Main.kt` 替换为网关的 `main`，`App` 原样复用。

## 常驻 HTTP 服务入口（GatewayMain，2026-11-11 追加）

`GatewayMain.kt` 把 S00–S14 装配成常驻服务（真机 / 客户端联调入口），与一次性自检 `Main.kt` 并存：

```bash
JIANCAI_DB_PATH=/var/lib/jiancai/jiancai.db \
JIANCAI_AUTH_SECRET=<32 字节以上> \
JIANCAI_MERCHANT_NAME=<商家名称> \
JIANCAI_HTTP_HOST=0.0.0.0 JIANCAI_HTTP_PORT=8080 \
./gradlew :fpk-server:app:runGateway
```

- 迁移合并：`AllMigrations.list`（S07/S01/S02 + S04/S05/S06 + S08 + S10/S11 + S12 + S14(700) + S13 幂等台账(800)）；
- 端口装配：S04 → S05 → S06 → S08 → S09/S10/S11/S12 → S14 → S13；
- 网关幂等存储用 `SqliteIdempotencyStore`（重启后仍可重放）；
- 关闭：进程退出时 shutdown hook 关闭 HTTP 服务与数据库连接。
