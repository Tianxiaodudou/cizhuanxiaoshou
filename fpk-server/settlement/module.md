# S11 · settlement

> contractVersion = **1.1.0**（只消费 `shared` 契约，本模块不新增跨端线格式）

## 职责

结算记录、提交即「当场判定」、管理员审核、已收 / 未收读模型。

- **应收只来自 S08**：提交与审核都调用 `OrderPort.getReceivable` 重新读取，读模型每次翻页
  调 `OrderPort.getRecent` 重算，从不缓存、不落库、不读订单表（铁律 3 / 10）；
- **已收只来自本模块**：`已收 = Σ APPROVED 收款 − Σ APPROVED 退款`；**永不改写订单的
  `received_amount` / 状态 / 版本**，订单侧金额保持 S08 的原始事实；
- **单号只来自 S07**：调用 `SerialPort.next(tx, SerialType.of(DocType.SETTLEMENT))`
  （前缀 `JS` 由 shared 的 `SerialType` 决定），本模块没有任何前缀字面量、不拼号（铁律 8）；
- **操作者只经 S03 校验存在性**：`AccountAdminPort.get` 确认提交人 / 审核人账号存在；
  角色与权限不在本模块判断（铁律 7，S13 拦截）；
- 金额、时间戳、状态、客户快照全部服务端产出，客户端只展示（铁律 5）。

写入范围：`fpk-server/settlement/**`（本模块独占 `settlement` 一张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `SettlePort.kt` | **Port**（唯一对外入口）：`submit` / `audit` / `getBalance`，各带 tx 与便捷重载 |
| `SettleService.kt` | 实现：幂等、订单快照、应收重读、待结判定、审核重判、读模型、事务包裹 |
| `SettleRepository.kt` | 内部：`settlement` 表的**唯一** SQL 出口 |
| `SettleMath.kt` | 内部：已收 / 待结 / 求和的金额纯函数（不解析单号、不读写表） |
| `SettlementMigrations.kt` | 迁移 510（建表 + 索引 + 审核留痕一致性 CHECK） |
| `SettleException.kt` | 失败异常，携带共享错误码 `E5xxx` / `E2001` / `E6005` / `E422` / `E9001` |

测试：`SettlePortTest`（22 例）、`SettlementMigrationsTest`（8 例）、`SettleBoundaryTest`（7 例）、
`TestSupport`（固定时钟 + 内存库 + 真实 S03 / S04 / S05 / S06 / S07 / S08 夹具）。

## 本模块实现的 Port

```kotlin
interface SettlePort {
    fun submit(tx: TxContext, cmd: SubmitSettleCmd, submittedBy: Long): SettleDto
    fun submit(cmd: SubmitSettleCmd, submittedBy: Long): SettleDto

    fun audit(tx: TxContext, cmd: AuditSettleCmd, auditedBy: Long): SettleDto
    fun audit(cmd: AuditSettleCmd, auditedBy: Long): SettleDto

    fun getBalance(tx: TxContext, customerId: Long): ReceivableDto
    fun getBalance(customerId: Long): ReceivableDto
}
```

- 出参一律 `shared` 的 `SettleDto` / `ReceivableDto`，不暴露持久化结构（铁律 2）；
- 每个方法都有带 `TxContext` 的重载：S13 可把「结算 + 其它写入」放进同一个 db-core 事务；
  便捷重载内部走 `TxRunner.run {}`，不存在非事务写入（铁律 4）；
- 方法签名里没有 token / 角色 / 账号 DTO：`submittedBy` / `auditedBy` 是已认证的操作者 id，
  只用于留痕与 S03 账号存在性校验，不是权限判断（铁律 7）。

## 数据模型（`settlement`，S11 独占）

```sql
settlement(
    settle_no PK, client_request_id UNIQUE, order_no, customer_id, customer_name,
    direction, amount, status,
    submitted_at, submitted_by, audited_at, audited_by,
    remark, audit_remark
)
```

| 列 | 口径 |
| --- | --- |
| `settle_no` | S07 产出（`JS` + `yyyyMMdd` + 4 位序号），本模块只保存 |
| `client_request_id` | 提交幂等键，`UNIQUE`；同一请求 id 同载荷只对应一张结算单 |
| `order_no` / `customer_id` / `customer_name` | 提交时的订单快照；**不建跨模块外键**（订单表归 S08） |
| `direction` | `RECEIPT` 收款 / `REFUND` 退款（枚举名持久化，线值由 shared 决定） |
| `amount` | `Money` 十进制字符串（两位小数），**始终为正**，方向由 `direction` 表达 |
| `status` | `SUBMITTED` / `APPROVED` / `REJECTED` / `VOID`；只有 `APPROVED` 计入已收 |
| `remark` | 提交备注（提交事实，审核不覆盖） |
| `audit_remark` | 审核备注（通过 / 驳回原因）；DTO 的 `remark` 按「审核备注优先」合并展示 |
| 审核留痕 CHECK | `SUBMITTED` 不得带 `audited_at/by`；`APPROVED/REJECTED/VOID` 必须带齐 |

金额列不用浮点类型；`direction` / `status` 用库层 CHECK 限制取值；索引覆盖
`(order_no, status)`、`(customer_id, status, submitted_at)`、`(status, submitted_at)`。

## 金额与待结算判定（验收「审核流通过」）

```
已收 = Σ APPROVED 收款 − Σ APPROVED 退款
待结 = 应收（提交 / 审核时从 S08 重新读取） − 已收
```

| 方向 | 允许条件 | 违反 |
| --- | --- | --- |
| `RECEIPT` 收款 | `待结 > 0` 且 `金额 ≤ 待结` | `E5002` |
| `REFUND` 退款 | `待结 < 0` 且 `金额 ≤ −待结` | `E5002` |

- 提交时立刻判定（当场判定），失败不写库、不消耗 S07 的结算单号；
- 审核通过时用**当时**的应收与已收再判一次：多笔待审结算叠加后不会超收；
- 退货单应收为负（S08 口径），因此退款落在退货单上；对正向订单退款会因 `待结 > 0` 被拒；
- 已收 / 未收是纯派生值，**订单侧 `received_amount` 永远不动**。

## 审核状态机

```
提交 → SUBMITTED
         ├─ 管理员通过 → APPROVED（计入已收）
         └─ 管理员驳回 → REJECTED（不计入已收，余额释放）
```

- 只有 `SUBMITTED` 能被审核，重复审核 / 并发审核失败抛 `E5003`；
- 审核更新是条件更新：`UPDATE ... WHERE settle_no = ? AND status = 'SUBMITTED'`，
  0 行即视为已被别人审核（单连接 + TxRunner 锁下的最后一道兜底）；
- 审核通过才改变「已收」；驳回只改状态与审核留痕，不动金额；
- `VOID` 预留给未来的作废流程（当前无 Port 可达），枚举与库层 CHECK 已兼容。

## 幂等与并发

1. 提交先按 `client_request_id` 查已有结算：**同订单 / 同方向 / 同金额**直接返回首单，
   不重复取号、不重复判定；
2. 同一请求 id 换了载荷（金额 / 方向 / 订单）抛 `E5004`，避免客户端复用请求 id 产生歧义；
3. 数据库 `client_request_id UNIQUE` 是并发兜底：唯一冲突后回查并返回首单；
4. 取号、结算写入在同一个事务内：失败回滚时 S07 的号段账本一起回滚，重试不留下空号。

## 已收 / 未收读模型

- `getBalance(customerId)` 返回 `ReceivableDto`：
  - `receivableAmount`：经 `OrderPort.getRecent(customerId)` 分页读取该客户全部订单后求和，
    作废订单计 `0.00`；每次调用都重新读 S08（改价 / 补单后自然更新）；
  - `receivedAmount`：本模块 `APPROVED` 收款 − `APPROVED` 退款；
  - `unreceivedAmount`：应收 − 已收；客户净应退（应收为负）时为负值，客户端按符号展示；
  - `customerName`：优先订单快照，其次最近一笔结算快照；两者都没有（新客户、无单）时为空串，
    本模块不越界读客户表（铁律 3）。
- 分页大小 200（与 S08 `getRecent` 单页上限一致），按 `total` 判断是否继续翻页。

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 结算单不存在（audit） | `E5001` | 404 | 不写库 |
| 收款超过未收 / 退款超过应退 | `E5002` | 422 | 提交与审核通过都会判 |
| 已审核 / 并发审核失败 | `E5003` | 409 | 不重复改状态 |
| 同一幂等键换了载荷 | `E5004` | 409 | 客户端应更换请求 id |
| 订单不存在 | `E2001` | 404 | 保留 S08 错误码 |
| 操作者账号不存在 | `E6005` | 404 | 保留 S03 错误码 |
| 单号 / 客户 id / 金额 / 长度 / 控制字符非法 | `E422` | 422 | 不写库 |
| 取号冲突 / 序号用尽 | `E9001` | 409 | 保留 S07 错误码，可重试 |

`E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。

## 事务与并发

- 全部写操作经 `TxRunner.run {}`（含便捷重载）；带 tx 重载直接复用调用方事务，
  外层回滚会同时丢弃结算记录与 S07 的取号账本；
- 单连接 + `TxRunner` 锁串行化写入；`client_request_id UNIQUE` 与审核条件更新是并发兜底；
- 提交顺序固定为「幂等查重 → 账号校验 → 订单快照 → 应收重读 → 余额判定 → 取号 → 写库」，
  失败不会留下半条数据或空号。

## 依赖（只调 Port，不碰表）

| 依赖 | 用途 | 禁止 |
| --- | --- | --- |
| S08 `OrderPort.getRecent` | 订单存在性 + 客户快照 + 读模型应收 | 不读写 `order_header` / `order_line` |
| S08 `OrderPort.getReceivable` | 提交 / 审核时重新读取应收 | 不使用客户端上传的金额 |
| S03 `AccountAdminPort.get` | 校验提交人 / 审核人账号存在 | 不判断角色 / 权限（S13） |
| S07 `SerialPort.next` | 结算单号 `JS` | 不自拼前缀 / 序号 |

## 迁移（版本 510）

```kotlin
DbCore.open(
    config,
    SettlementMigrations.all + otherModules,
)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 510 | `create_settlement_table` | `settlement`（单号 / 幂等键唯一、方向与状态 CHECK、审核留痕一致性 CHECK）+ 3 个索引 |

S10 用 500、S11 用 510–519 子段（db-core 的 500–599 段位）。迁移只增不改；
改表继续追加 511、512…（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(
    DbConfig.file("/var/lib/jiancai/jiancai.db"),
    SerialMigrations.all + CustomerMigrations.all + CategoryMigrations.all +
        PricingMigrations.all + OrderMigrations.all + SettlementMigrations.all + otherModules,
)
db.migrator.migrate()

val settlement: SettlePort = SettleService(
    txRunner = db.txRunner,
    orders = order,          // S08
    accounts = accountAdmin, // S03
    serials = serial,        // S07
    clock = Clock.systemDefaultZone(),
)
```

S13 路由（示意，权限在网关）：

```
POST /settlements                  → settlement.submit(cmd, 已认证 userId)
POST /settlements/{settleNo}/audit → settlement.audit(cmd, 已认证管理员 id)
GET  /customers/{id}/balance       → settlement.getBalance(customerId)
```

## 验收对照

本机用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:settlement:test`：
**37 个用例全部通过（0 失败 / 0 跳过）**
（`SettlePortTest` 22 + `SettlementMigrationsTest` 8 + `SettleBoundaryTest` 7）。

- [x] 审核流通过：`approving_a_submitted_settlement_records_the_auditor_and_moves_the_balance`、
      `a_settlement_can_only_be_audited_once`（`E5003`）、
      `a_rejected_settlement_is_not_counted_as_received_and_releases_the_balance`、
      `approval_rechecks_the_balance_and_blocks_overlapping_receipts`（审核时重判 `E5002`）。
- [x] 已收金额不被改动：`submitting_never_touches_the_orders_received_amount_or_status`
      （订单 `received_amount` 仍 `0.00`、状态仍 `CREATED`、版本不变）、
      `a_partial_receipt_can_be_followed_by_the_remaining_balance`、
      `a_refund_on_a_return_order_reduces_the_customer_balance`、`SettleBoundaryTest`
      禁止 `received_amount =` 与订单写方法。
- [x] 应收从 S08 重新读取：`the_balance_rereads_the_receivable_from_s08_every_time`、
      `SettleBoundaryTest` 要求 `orders.getReceivable(` 且禁止订单写方法。
- [x] 当场判定：`a_receipt_over_the_outstanding_balance_is_rejected_with_e5002_and_writes_nothing`、
      `a_refund_cannot_exceed_the_amount_owed_to_the_customer`、
      `a_refund_on_a_positive_order_is_rejected_with_e5002`。
- [x] 幂等：`the_same_request_id_returns_the_same_settlement_and_consumes_one_serial`、
      `reusing_a_request_id_for_a_different_payload_is_rejected_with_e5004`、
      迁移层 `client_request_id UNIQUE`。
- [x] 事务（铁律 4）：`the_callers_transaction_rolls_back_the_settlement_and_the_serial`、
      `convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction`。
- [x] 硬边界（铁律 2 / 3 / 7 / 8 / 10）：`SettleBoundaryTest` 7 例——Port 无角色 / token、
      只返回 DTO、DML 只在 `SettleRepository`、无订单表 DML / 无 `received_amount` 赋值 /
      无 `DELETE`、无单号前缀字面量、无打印逻辑、只拥有 `settlement` 一张表。

## 集成前置

1. 根 `settings.gradle.kts` 需追加 `include(":fpk-server:settlement")`。本模块写入范围只有
   `fpk-server/settlement/**`，根工程由组合根统一维护；验证时用模块内临时根工程
   （`fpk-server/settlement/build/verify`，构建产物、不提交）跑通 `:fpk-server:settlement:test`。
2. 根工程需已有 `:shared`、`:fpk-server:db-core`、`:fpk-server:serial`、`:fpk-server:customer`、
   `:fpk-server:category`、`:fpk-server:pricing`、`:fpk-server:order`、`:fpk-server:account`；
   本模块 `build.gradle.kts` 已按 `api(project(...))` 声明。
3. S13 必须在进入 `submit` / `audit` 前完成登录校验与管理员拦截，并把已认证用户 id 作为
   `submittedBy` / `auditedBy` 传入；本模块不做权限判断。
4. 审核列表（C11）目前只能按 `settleNo` 审核；若要「待审核列表」，按「接口只增不改」给
   `SettlePort` 追加只读方法（例如 `listPending`），不在本次范围内私自扩展。

## 待确认的落地假设

1. **包名 / 表名**：`com.jiancai.settlement`、`settlement`；单号 `JS` 由 `SerialType.SETTLEMENT` 决定。
2. **取号路径**：任务卡的依赖只列了 S08 / S03，但 S08 的 `OrderPort` 没有「代取结算单号」方法，
   且接口只增不改、本模块不得改 S08。为让服务端产出 `settle_no`，本模块直接依赖 S07
   `SerialPort`（与 S10 note 取 `BZ` 的做法一致），仍满足「不自行生成单号」；
   若后续要求所有号都经 S08 收口，应在 S08 追加 `nextSettleNo` 后替换本模块的取号调用。
3. **退款口径**：`REFUND` 只能对「待结为负」的单（退货单）发起，且不超过 `−待结`；
   正向订单上的退款被拒。若业务需要「先收款后退款」的现金退回，需要新增业务规则再放开。
4. **已收为负是合法中间态**：客户未付原单就退退货、并已退款时，客户级已收可能为负，
   `未收 = 应收 − 已收` 仍然自洽（客户欠款包含已退现金）。
5. **`E5004` 的语义**：同一幂等键换了载荷时抛 `E5004`（而非静默返回首单），
   避免客户端复用请求 id 造成「以为改成功了」的错觉；同载荷重放仍是幂等返回。
6. **提交备注与审核备注分列保存**：DTO 只有一个 `remark`，按「审核备注优先」展示；
   提交事实保留在 `remark` 列，不被审核覆盖。
7. **`customerName` 兜底**：读模型不依赖 S04，客户无订单且无结算时返回空串；
   若要显示客户名，应由 S13 组合 S04 的客户查询结果，或后续给 `SettlePort` 追加客户名参数。
8. **`VOID` 未开放**：枚举与库层 CHECK 已兼容，但当前没有作废 Port；需要时按「接口只增不改」追加。
