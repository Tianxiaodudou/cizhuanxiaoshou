# S01 · config

> contractVersion = **1.0.0**（复用 `shared` 已有的 `ConfigDto`；本模块不新增跨端线格式）

## 职责

部署级商家名称与部署配置：**只由服务端启动参数提供，客户端不可传、不可改，只能读**。
本模块把部署方给的配置落到自己的 `deployment_config` 表，并通过 `ConfigPort` 对外提供读取。

写入范围：`fpk-server/config/**`（本模块独占 `deployment_config` 表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `ConfigPort.kt` | **Port**：`getMerchantName()` / `getConfig()`，只读，无 setter |
| `ConfigService.kt` | 实现：启动时应用部署配置 / 回退库中值 / 缺失即失败，之后返回不可变快照 |
| `ConfigRepository.kt` | 内部：`deployment_config` 的**唯一** SQL 出口（读 + UPSERT） |
| `ConfigMigrations.kt` | 迁移：版本 200 建单行配置表（交给 db-core 的 `Migrator`） |
| `DeploymentConfig.kt` | 服务端配置来源：JVM 参数 / 环境变量，规范化与校验 |
| `ConfigException.kt` | 启动期配置错误（缺失 / 空白），不映射 `ErrorCode` |

## 本模块实现的 Port

```kotlin
interface ConfigPort {
    fun getMerchantName(): String        // 标量，读名称
    fun getConfig(): ConfigDto           // 共享 DTO，供 S13 组装握手 / 配置响应
}
```

- 返回标量 / DTO，不返回 Entity；
- 读端口不带 `TxContext`：名称在服务生命周期内是不可变快照，不参与跨模块写事务；
  唯一的写路径在 `ConfigService` 构造阶段，已经由 db-core 的 `TxRunner.run {}` 包裹（铁律 4 约束的是写路径）；
- 部署配置变更后重启服务生效（v1.0.0 不做热更新）。

## 部署配置来源（客户端不可传）

优先级从高到低：

| 来源 | 示例 |
| --- | --- |
| JVM 系统属性 | `-Djiancai.merchantName=宁波建材` |
| 环境变量 | `JIANCAI_MERCHANT_NAME=宁波建材` |

规则：

- 只读取**服务端进程**的启动参数；HTTP 请求体 / 查询参数 / 请求头不参与，本模块也没有写 Port；
- 首尾空白自动去除；设置了但为空白 → `ConfigException`，启动失败（不静默回退）；
- 两者都未设置：
  - 库中已有名称 → 沿用（允许部署方只在首次启动时传参）；
  - 库中也没有 → `ConfigException`，启动失败。**不会**写占位名，也**不会**使用写死的默认商家名。

## 持久化与事务

`deployment_config` 是单行表（`id = 1`），库层约束兜底：

```sql
CREATE TABLE deployment_config (
    id            INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),                 -- 部署级唯一一份
    merchant_name TEXT    NOT NULL CHECK (length(trim(merchant_name)) > 0),     -- 不允许空名称
    updated_at    TEXT    NOT NULL                                              -- 服务端 UTC ISO-8601
)
```

`ConfigService` 构造时在一个 `TxRunner.run {}` 事务内完成「读 → 按需 UPSERT」：

- 提供了部署配置且与库中不同 → 写入（部署参数是权威来源，覆盖旧值）；
- 与库中相同 → 不写（重启不刷新 `updated_at`）；
- 未提供但库中已有 → 只读；
- 都没有 → 抛异常，事务回滚，不留占位数据。

## 迁移（版本号段 200–299）

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 200 | `create_deployment_config` | 单行部署配置表（`id = 1`，非空商家名称） |

段位内再分子段，避免 S02 / S03 抢号：**S01 = 200–209、S02 = 210–219、S03 = 220–229**。
迁移只增不改；需要加部署配置项时追加 201…（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 组合根接入

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), ConfigMigrations.all + otherModules)
db.migrator.migrate()                                   // 必须先迁移，再构造 ConfigService

// 部署方在启动前设置 JIANCAI_MERCHANT_NAME（或 -Djiancai.merchantName）
val config: ConfigPort = ConfigService(db.txRunner)     // 构造即应用配置；缺失即启动失败

// S13 组装握手信息（商家名称只读展示）
ContractInfoDto(serverTime = ..., merchantName = config.getMerchantName())
```

## 依赖与边界

- 依赖：`shared`（复用 `ConfigDto`，不另写一份字段名）、db-core（`TxRunner` / `TxContext` / `Migration` / `Migrator`）。
- 禁止：
  - 客户端传部署配置（没有写 Port、没有 HTTP 写入口）；
  - 写死商家名称或提供默认名称；
  - 其它模块直接读写 `deployment_config`（要读就调 `ConfigPort`）；
  - 在 `ConfigRepository` 之外出现该表的 SQL；
  - 本模块判断权限（权限在 S13；本 Port 是部署级只读信息，不含账号数据）。

## 验收对照

本机已用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:config:test`：**10 个用例全部通过（0 失败 / 0 跳过）**。

- [x] 商家名称由服务端配置生效：
      `ConfigPortTest.merchant_name_comes_from_the_server_side_deployment_config`
      （名称来自 `DeploymentConfig`，不是常量）、
      `ConfigPortTest.deployment_config_comes_only_from_server_side_process_settings`
      （只认 JVM 参数 / 环境变量，JVM 参数优先，未配置返回 null）。
- [x] 客户端不可传 / 不可改：`ConfigPort` 只有两个读方法（无 setter）；
      `ConfigPortTest.config_table_holds_one_deployment_row_and_rejects_a_second`
      （库层拒绝第二行）。
- [x] 配置持久化、可重启复用：`ConfigPortTest.merchant_name_is_persisted_and_reused_when_the_deployment_config_is_absent`、
      `ConfigPortTest.deployment_config_overrides_the_persisted_name_on_restart`、
      `ConfigPortTest.restarting_with_the_same_deployment_config_does_not_rewrite_the_row`。
- [x] 缺失配置时不写死、不带病启动：`ConfigPortTest.missing_deployment_config_and_empty_database_fails_fast`、
      `ConfigPortTest.blank_merchant_name_is_rejected`。
- [x] 迁移：`ConfigMigrationsTest.deployment_config_table_is_created_by_the_config_migration`、
      `ConfigMigrationsTest.migration_version_is_in_the_config_segment_and_idempotent`。

## 集成前置（仓库仍缺根工程）

本仓库还没有根工程（`settings.gradle.kts` / 根 `build.gradle.kts`），也还没有 `fpk-server` 聚合工程。
本机已装便携 JDK / Gradle（`.tools/`，已 gitignore），并用 `.tools/verify-all` 临时根工程跑通
`:fpk-server:config:test`（10/10）。正式装配时补：

```kotlin
// 根 settings.gradle.kts
include(":shared", ":fpk-server", ":fpk-server:db-core", ":fpk-server:config")
```

`jvmToolchain(17)` 与 `shared` / db-core 一致，需要 JDK 17（或根工程配 foojay-resolver）。
根工程补上后运行 `:fpk-server:config:test` 即可复跑上表全部用例。

## 待确认的落地假设（v1.0.0 基线，如有异议按契约流程调整）

1. 包名 `com.jiancai.config`（沿用 `com.jiancai` 前缀）。
2. 部署配置来源为「JVM 系统属性 > 环境变量」；未引入配置文件解析，若部署包需要 `config.properties`，
   只需扩展 `DeploymentConfig.fromEnvironment`，不影响 Port / 表结构。
3. 商家名称在进程内缓存为不可变快照，部署配置变更后**重启生效**；若要求热更新，需新增刷新入口
   （仍只允许服务端触发），不属于 v1.0.0 验收范围。
4. 缺配置时选择「启动失败」而不是回退到空字符串 / 默认名：商家名称会出现在小票与握手中，
   错误名称比启动失败更难发现。
5. 配置表采用单行 + 固定列（而非 key-value）：当前只有商家名称，强类型列更简单；
   后续新增部署配置项时追加迁移加列即可。
