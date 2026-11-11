# S14 · observability

> contractVersion = **1.1.0**（只消费 `shared` 契约；审计 DTO 是服务端内部读模型，不跨端）

## 职责

日志与审计留痕：**改价、结算审核**各留一条不可篡改的台账行。

- **只追加**：`audit_log` 没有 UPDATE / DELETE，也没有删除类 Port；整改靠追加新条目；
- **并回业务事务**：带 `TxContext` 的重载让「业务写入 + 留痕」原子提交（铁律 4）；
- **不参与业务决策**：从不回答「能不能改价 / 能不能审核」；不 import 任何业务模块（铁律 3）；
- **出参 DTO**：`AuditRecordDto`，不暴露持久化结构（铁律 2）。

写入范围：`fpk-server/observability/**`（独占 `audit_log` 一张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `AuditPort.kt` | **Port**：`record` / `priceChanged` / `settleAudited` / `query`，各带 tx 与便捷重载 |
| `AuditService.kt` | 实现：入参规范化与校验、留痕组装、`details` 编解码、事务包裹 |
| `AuditRepository.kt` | 内部：`audit_log` 的唯一 SQL 出口（只有 INSERT / SELECT） |
| `AuditModels.kt` | `AuditEvent` / `AuditQuery` / `AuditRecordDto` |
| `AuditAction.kt` | 动作枚举（`PRICE_CHANGE` / `SETTLE_AUDIT`）与目标类型 |
| `ObservabilityMigrations.kt` | 迁移 700：建 `audit_log` + 三个查询索引 |
| `AuditException.kt` | 失败异常（只用通用错误码 `E422` / `E500` / `E503`） |

测试 4 个文件、共 **33 例**：`AuditPortTest`（13）、`AuditTransactionTest`（7）、
`AuditMigrationsTest`（7）、`AuditBoundaryTest`（6）。

## 数据模型（一张表，S14 独占）

```sql
audit_log(
    id INTEGER PK AUTOINCREMENT, action, target_type, target_no, operator_id,
    operator_label, summary, details_json, created_at, trace_id
)
```

| 列 | 口径 |
| --- | --- |
| `action` | `PRICE_CHANGE` / `SETTLE_AUDIT`（库层 CHECK 白名单） |
| `target_type` | 由 `AuditAction.targetType` 决定（`price` / `settlement`） |
| `target_no` | 改价 = `客户id/型号id/批号`（无批号用 `-`）；审核 = 结算单号 |
| `operator_id` | 已认证操作者 id（> 0，库层 CHECK）；来自 S13，只用于留痕 |
| `operator_label` | 操作者显示名快照（账号改名不回填历史） |
| `summary` | 一行摘要（≤ 200 字符） |
| `details_json` | 字符串键值对象（≤ 32 项、单项 ≤ 200 字符），服务端产出 |
| `created_at` | 服务端时钟，`WireFormat.TIMESTAMP` |
| `trace_id` | 网关追踪 id（与响应头 `X-Trace-Id` 同值，便于对齐） |

## 留痕口径（验收「改价 / 审核有留痕」）

| 场景 | 入口 | 关键字段 |
| --- | --- | --- |
| 改价 | `priceChanged(log: PriceChangeLog)` | 旧价 / 新价 / 原因 / 批号 / 管理员 / 改价时间 |
| 审核 | `settleAudited(settle: SettleDto, approved)` | 结算单号 / 订单号 / 方向 / 金额 / 结论 / 审核人 |

- 输入直接用 `shared` 的 `PriceChangeLog` / `SettleDto`，**不重算金额**（铁律 5）；
- 审核留痕会复核 `approved` 与 `SettleDto.status` 一致（不一致 = 调用方 bug，`E422`，不写库）；
- 未审核的结算单不能留审核痕（`auditedBy` 为空 → `E422`）。

## 错误码口径

| 场景 | 错误码 | HTTP |
| --- | --- | --- |
| 入参非法（主键空白 / 操作者 ≤ 0 / 摘要或明细超限 / 查询条数越界 / 结论与状态不一致） | `E422` | 422 |
| `details_json` 损坏 | `E500` | 500 |
| 库不可用 | `E503` | 503 |

`E403` 不会从本模块抛出：权限只在 S13（铁律 7）。

## 事务与并发

- 所有写入走 `TxRunner.run {}`；带 tx 重载复用调用方事务，业务回滚时留痕一起回滚；
- 只追加 + 自增主键 → 同一目标的多条留痕天然可排序（`id` 严格递增）；
- 单连接 + `TxRunner` 锁串行化，读事务不会看到中间态。

## 迁移（版本 700）

```kotlin
DbCore.open(config, ObservabilityMigrations.all + otherModules)
```

段位：100 S07、200 S01、210 S02、300 S04、310 S05、320 S06、400/401 S08、500 S10、510 S11、600 S12，
**700 S14**；S13 网关不建表。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:observability:test`：**33 个用例全部通过（0 失败 / 0 跳过）**。

- [x] 改价有留痕：`a_price_change_is_traced_with_the_old_and_the_new_price`、
      `audit_ids_are_strictly_increasing_and_rows_are_never_rewritten`。
- [x] 审核有留痕：`a_settlement_audit_is_traced_with_the_audited_amount`、
      `a_rejected_settlement_is_traced_as_rejected`、`settle_audit_rejects_a_conclusion_that_contradicts_the_settlement_status`。
- [x] 只追加：`the_ledger_is_append_only`（源码无 UPDATE / DELETE）、`the_module_owns_exactly_one_table`。
- [x] 事务（铁律 4）：`a_caller_rollback_discards_the_audit_row`、`nested_writes_reuse_the_outer_transaction`、
      `the_convenience_overloads_open_their_own_transaction`、`a_closed_database_fails_instead_of_silently_dropping_the_trace`。
- [x] 不参与业务决策：`the_module_never_makes_a_permission_decision`、`the_module_does_not_import_any_business_module`、
      `the_audit_port_never_receives_a_role_token_or_account_dto`。
- [x] 迁移与库层约束：`AuditMigrationsTest` 7 例（版本推进、幂等、动作白名单、操作者、目标非空、明细非空、只拥有一张表）。

## 装配（组合根）

```kotlin
val audit: AuditPort = AuditService(db.txRunner)
val deps = GatewayDeps(..., audit = audit)      // S13 在改价 / 审核成功后同事务留痕
```

## 待确认（不在本模块写入范围内）
1. ~~**迁移段位表要加一行**~~（已补：`db-core/module.md` 段位表新增 `700–799 = S14 observability`、`800–899 = S13`）。
2. ~~**审计查询没有路由**~~（已解决：契约 1.2.0 新增 `AuditEntryDto`，本模块新增 `entries()`，S13 新增 `GET /audit`）。
3. **`AuditAction` 目前只有改价 / 审核**：其它动作（建单、作废、打印）未留痕。追加动作 = 追加枚举项 + 追加库层
   CHECK 白名单（需要一条新迁移，不能改 700）。
4. **没有独立的日志落盘 / 级别配置**：本模块的「日志」以审计台账形式落地（可查询、可追溯）；
   运行日志仍由部署侧的 stdout 收集，不在业务模块里写 `println`。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 S14：`AuditPort` / 迁移 700 / 改价与审核留痕 + 33 例单测 | 1.1.0 |
| 2026-11-11 | 追加跨端读模型：`AuditPort.entries()` 返回 `shared` 的 `AuditEntryDto`（1.2.0），S13 `GET /audit` 消费；原有 `query()`（内部 `AuditRecordDto`）保持只增不改。 | 1.2.0 |
