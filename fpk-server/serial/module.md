# S07 · serial

> contractVersion = **1.0.0**（只依赖 `shared` 契约；本模块不新增跨端线格式）

## 职责

单号生成：**前缀 + 年 + 月日 + 全局序号**，统一在事务内取号，唯一约束兜底。
单号只由服务端产出，客户端只展示；服务端只有 S08 通过 `SerialPort` 获取（铁律 8）。

写入范围：`fpk-server/serial/**`（本模块独占 `serial_counter` / `serial_number` 两张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `SerialPort.kt` | **Port**：取号入口（事务内 / 独立两种重载） |
| `SerialService.kt` | 实现：业务日期、序号分配、唯一约束重试，返回单号值 |
| `SerialRepository.kt` | 内部：两张表的**唯一** SQL 出口（UPSERT 计数器 + 写账本） |
| `SerialMigrations.kt` | 迁移：版本 100 建表（交给 db-core 的 `Migrator`） |
| `SerialException.kt` | 取号失败，携带共享错误码 `E9001`（409 + retryable） |

## 本模块实现的 Port

```kotlin
interface SerialPort {
    // 跨模块写：与业务写入同一个事务（S08 建单用这个）
    fun next(tx: TxContext, type: SerialType): String
    // 独立取号：内部仍走 TxRunner.run {}，不存在非事务路径
    fun next(type: SerialType): String
}
```

`type` 是共享契约的 `SerialType`（前缀唯一真源，`SerialType.of(docType)` 解析）；
调用方不得传入前缀字符串，也不得自己拼接 / 截取单号。

## 单号格式与序号口径

```
PS202611100001
│ │      │
│ │      └─ 4 位序号（0001–9999，定长）
│ └──────── 年月日（8 位，yyyyMMdd）
└────────── 2 位前缀（PS/PT/ZS/ZT/BZ/JS，只来自 SerialType）
```

- 业务日期：`LocalDate.now(clock)`，取服务端时区（默认系统时区；测试注入固定时钟）；
  跨年 / 跨月 / 跨日以服务端当地日期为准，客户端本地时间不参与。
- 序号：按「**前缀 + 业务日期**」各自独立维护，每天从 `0001` 开始，上限 `9999`；
  「全局」指服务端单一分配器（不按客户端 / 进程 / 线程分段），不同单据类型互不占号。
- 序号存于 `serial_counter.last_value`（已分配到的号）；单号账本 `serial_number` 记录已发出的号。

## 事务与并发（验收 1：并发取号不重号）

三层保证，缺一不可：

1. **单连接 + TxRunner 串行化**：db-core 的 `TxRunner.run {}` 用单连接 + 可重入锁，
   同一进程内的取号事务天然串行（不会读改写交错）。
2. **原子 UPSERT**：`serial_counter` 按 `(prefix, period)` 主键执行
   `INSERT ... ON CONFLICT DO UPDATE SET last_value = last_value + 1`，一条语句完成分配。
3. **唯一约束兜底**：`serial_number.serial_no` 主键 + `(prefix, period, seq)` 唯一索引；
   若仍撞号（跨进程、历史数据、计数器落后），捕获唯一冲突后**取下一个号重试**，
   最多 5 次；SQLite 默认 `ABORT` 只回滚失败语句，事务可继续。

回滚口径：S08 用 `next(tx, type)` 时，取号与订单写入同事务；订单失败回滚会一并回滚
计数器与账本，重试不会留下「号已用、单未建」的空号。序号用尽（> 9999）抛
`SerialException(E9001)`，不会产生超出格式的 5 位序号。

## 迁移（版本号段 100–199）

```kotlin
DbCore.open(config, SerialMigrations.all + otherModules)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 100 | `create_serial_tables` | `serial_counter`（主键 `prefix + period`）、`serial_number`（主键 `serial_no` + `(prefix, period, seq)` 唯一） |

迁移只增不改；需要改表时追加 101、102…（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 依赖与边界

- 依赖：`shared`（`SerialType`）、db-core（`TxRunner` / `TxContext` / `Migrator` / `Migration`）。
- 组合根（服务启动一次）：

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), SerialMigrations.all)
db.migrator.migrate()
val serial: SerialPort = SerialService(db.txRunner)   // 注入给 S08
```

- 禁止：业务模块自行拼单号；非事务取号；客户端生成单号；在 `SerialRepository` 之外写 SQL；
  其它模块读写 `serial_counter` / `serial_number`；本模块判断权限（权限在 S13）。

## 验收对照

- [x] 并发取号不重号：`SerialConcurrencyTest.concurrent_calls_never_duplicate_a_serial_number`
      （8 线程 × 50 次，总数 / 去重数 / `1..400` 连续集合三者一致）。
- [x] 前缀与年月日正确：`SerialPortTest.serial_is_prefix_plus_year_month_day_plus_four_digit_sequence`
      （6 种前缀逐一核对格式）、`business_date_follows_the_server_time_zone_across_the_year_boundary`
      （+08:00 跨年 23:30 → 00:30）、`sequences_increment_per_type_and_reset_on_a_new_day`。
- [x] 序号持久化：`sequence_is_persisted_in_the_database_not_in_the_service_instance`
      （新实例/重启后接着库里的序号继续，不回到 1）。
- [x] 事务取号：`caller_transaction_rollback_does_not_consume_the_number`（回滚后序号可复用）。
- [x] 唯一约束兜底：`unique_constraint_is_the_fallback_when_a_number_is_already_taken`（占号后自动跳号重试）。
- [x] 序号用尽：`exhausted_sequence_fails_with_a_retryable_serial_error`（`E9001`）。
- [x] 迁移：`SerialMigrationsTest.serial_tables_are_created_by_the_serial_migration`、
      `migration_version_is_in_the_serial_segment_and_idempotent`。

## 集成前置（当前仓库尚缺）

本仓库还没有根工程（`settings.gradle.kts` / 根 `build.gradle.kts`），也还没有 `fpk-server` 聚合工程；
当前环境也没有 JDK / Gradle，故 `:fpk-server:serial:test` **尚未在本机执行**。装配时补：

```kotlin
// 根 settings.gradle.kts
include(":shared", ":fpk-server", ":fpk-server:db-core", ":fpk-server:serial")
```

`jvmToolchain(17)` 与 `shared` / db-core 一致，需要 JDK 17（或根工程配 foojay-resolver）。
根工程补上后运行 `:fpk-server:serial:test` 即可验证上表全部用例。

## 待确认的落地假设（v1.0.0 基线，如有异议按契约流程调整）

1. 包名 `com.jiancai.serial`（沿用 `com.jiancai` 前缀）。
2. **「全局序号」按「前缀 + 业务日期」各一条序列**：同一前缀每天 0001 起，不同前缀互不占号；
   若业务要求所有单据类型共享一条当日序列，只需改 `serial_counter` 的主键口径（新增迁移）。
3. 业务日期取服务端时区（`Clock.systemDefaultZone()`，可注入）；客户端时间不参与取号。
4. 序号定长 4 位、上限 9999/天/前缀；超出抛 `E9001`。扩容需要改单号格式，属于契约变更。
5. `SerialPort` 同时保留 `next(tx, type)`（R4：Port 接收事务上下文）与 `next(type)`
   （任务卡签名的独立取号便捷重载）；后者内部仍是 `TxRunner.run {}`，不构成非事务路径。
6. 单号以 `String` 返回：它是契约层的标量值，不含实体字段；账本表只做唯一性兜底与审计，
   不对外提供读取 Port。
