# S10 · note

> contractVersion = **1.0.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

差异备注单：关联原单、记录差异、只作废不删除。

- **关联原单、不改原单**：创建时只经 S08 `OrderPort` 读取原单（存在性 / 客户 id 快照），
  本模块没有任何订单表的 SQL，也不调用 S08 的写方法（铁律 3 / 10）；
- **单号只由 S07 产出**：备注单号调用 `SerialPort.next(tx, SerialType.of(DocType.NOTE))`，
  本模块没有前缀字面量，也不拼接 / 截取单号（铁律 8）；
- **只作废不删除**：作废只更新 `diff_note` 的作废留痕列，差异内容与单号永久保留；
- **不判权限**：Port 参数里没有角色 / token；`createdBy` / `voidedBy` 只用于留痕，
  权限统一由 S13 api-gateway 拦截（铁律 7）。

写入范围：`fpk-server/note/**`（本模块独占 `diff_note` 一张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `NotePort.kt` | **Port**（唯一对外入口）：`create` / `void`，各带 tx 与便捷重载 |
| `NoteService.kt` | 实现：入参校验、原单只读校验、取号、落库、条件作废、事务包裹 |
| `NoteRepository.kt` | 内部：`diff_note` 的唯一 SQL 出口（按单号读、插入、条件作废；没有 DELETE） |
| `NoteMigrations.kt` | 迁移 500：建 `diff_note`、作废留痕 CHECK、查询索引 |
| `NoteException.kt` | 失败异常，携带共享错误码 `E4001` / `E4002` / `E4003` / `E422` / `E9001` |

测试：`NotePortTest`（9 例）、`NoteVoidTest`（7 例）、`NoteMigrationsTest`（6 例）、
`NoteBoundaryTest`（7 例），共 **29 例**；`TestSupport` 使用固定时钟 + 内存库 + 真实
S04 / S05 / S06 / S07 / S08 夹具。

## 本模块实现的 Port

```kotlin
interface NotePort {
    fun create(tx: TxContext, cmd: CreateNoteCmd, createdBy: Long): NoteDto
    fun create(cmd: CreateNoteCmd, createdBy: Long): NoteDto

    fun void(tx: TxContext, cmd: VoidNoteCmd, voidedBy: Long): NoteDto
    fun void(cmd: VoidNoteCmd, voidedBy: Long): NoteDto
}
```

- 出参一律 `shared` 的 `NoteDto`，不暴露持久化结构（铁律 2）；
- 每个方法都有带 `TxContext` 的重载：S13 可以把「备注单 + 其它写入」放进同一个 db-core 事务
  （铁律 4）；便捷重载内部走 `TxRunner.run {}`，不存在非事务写入；
- `createdBy` / `voidedBy` 是已由 S13 认证的操作者 id，只用于留痕，不是权限判断。

## 数据模型（一张表，S10 独占）

```sql
diff_note(
    note_no PK, source_order_no, customer_id, diff_type, diff_amount, remark,
    voided, created_at, created_by, voided_at, voided_by, void_reason
)
```

| 列 | 口径 |
| --- | --- |
| `note_no` | S07 产出（`BZ` + `yyyyMMdd` + 4 位序号），本模块只保存 |
| `source_order_no` | 原单号快照；**不建外键**：订单表归 S08 独占，本模块只经 Port 校验原单（铁律 3 / 10） |
| `customer_id` | 创建时从原单 DTO 复制的客户 id 快照，供列表 / 打印使用 |
| `diff_type` | `shared` 的 `DiffType` 枚举名，持久化内部值；线值由 `@SerialName` 决定 |
| `diff_amount` | `Money` 十进制字符串（两位小数），可正可负 |
| `remark` | 差异说明，必填，≤ 500 字符 |
| `voided` | 0 / 1；作废后置 1，且四列作废留痕必须同时存在（库层 CHECK 兜底） |
| `voided_at` / `voided_by` / `void_reason` | 作废时间（服务端时间戳）、操作者与原因；未作废时为 NULL |

## 创建（验收「原单不被改动」）

```
校验操作者 id / 原单号 / 备注 → 规范化差异金额
  → 只读查找原单（OrderPort）：
      当天单 → today(tx)
      历史单 → getReceivable(tx, orderNo) 先判存在
               → 存在则按页 getRecent(tx, OrderQuery(pageSize = 200)) 取客户快照
  → 原单不存在 → E4003（此时尚未取号，不消耗 S07 序号）
  → SerialPort.next(tx, SerialType.of(DocType.NOTE)) 取备注单号
  → 插入 diff_note（voided = 0，created_at / created_by 由服务端产出）
```

- 原单只读：创建流程没有任何 `insert / update / delete` 订单数据的调用；
- 客户 id 是**创建时快照**，原客户资料后续变化不回改历史备注单；
- 原单不存在时先失败再取号，失败请求不会在 S07 留下空号；
- 同一原单允许多张备注单（不同差异分类 / 多次补录），各自独立取号。

## 作废（验收「作废用例通过」）

```
校验操作者 id / 备注单号 / 作废原因
  → 读取备注单（不存在 → E4001）
  → 已作废 → E4002
  → UPDATE diff_note SET voided = 1, voided_at / voided_by / void_reason
      WHERE note_no = ? AND voided = 0
  → 0 行（并发被抢先）→ E4002
```

- 作废是终态：重复作废返回 `E4002`，不覆盖首次作废留痕；
- 作废只改备注单自身，**原单完全不动**；不删除行，差异数据与单号仍可追溯；
- 条件更新 `WHERE voided = 0` 是并发兜底：两个请求同时作废只有一个成功。

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 备注单不存在（作废） | `E4001` | 404 | 不写库 |
| 备注单已作废 | `E4002` | 409 | 不写库 |
| 原订单不存在（创建） | `E4003` | 404 | 不取号、不写库；由 S08 `E2001` 归一化 |
| 单号 / 备注 / 原因 / 操作者 id 非法 | `E422` | 422 | 不写库 |
| 取号冲突 / 序号用尽 | `E9001` | 409 | 保留 S07 错误码，可重试 |

`E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。

## 事务与并发

- 全部写操作经 `TxRunner.run {}`（含便捷重载）；带 tx 重载直接复用调用方事务，
  外层回滚会同时丢弃备注单与 S07 的取号账本，重试不会留下空号；
- 单连接 + `TxRunner` 锁串行化写入；`note_no` 主键是取号并发的最终兜底；
- 作废使用条件更新，避免「两个请求都读到未作废，然后互相覆盖留痕」；
- 创建时对原单只做读操作，不参与订单事务的写锁，不会改变 S08 的版本 / 金额 / 状态。

## 迁移（版本 500）

```kotlin
DbCore.open(config, NoteMigrations.all + otherModules)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 500 | `create_diff_note_table` | `diff_note`（单号主键、客户 / 备注 CHECK、作废四列一致性 CHECK）、来源单 + 创建时间索引 |

S07 用 100、S04 用 300、S05 用 310、S06 用 320、S08 用 400、S09 预留 410–419；
S10 用 500（S11 预留 510–519）。迁移只增不改。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(
    DbConfig.file("/var/lib/jiancai/jiancai.db"),
    SerialMigrations.all + CustomerMigrations.all + CategoryMigrations.all +
        PricingMigrations.all + OrderMigrations.all + NoteMigrations.all + otherModules,
)
db.migrator.migrate()

val customer: CustomerPort = CustomerService(db.txRunner)
val category: CategoryPort = CategoryService(db.txRunner)
val serial: SerialPort = SerialService(db.txRunner)
val recalculator = OrderPriceRecalculator(db.txRunner)
val pricing: PricePort = PriceService(db.txRunner, customer, category, Clock.systemDefaultZone(), listOf(recalculator))
val order: OrderPort = OrderService(db.txRunner, serial, pricing, customer, category, Clock.systemDefaultZone())
val note: NotePort = NoteService(db.txRunner, order, serial, Clock.systemDefaultZone())
```

S13 路由（示意，权限在网关）：

```
POST /notes        → note.create(cmd, 已认证 userId)
POST /notes/{noteNo}/void → note.void(VoidNoteCmd(noteNo, reason), 已认证 userId)
```

## 验收对照

本机用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:note:test`：**29 个用例全部通过
（0 失败 / 0 跳过）**。

- [x] 原单不被改动：
      `create_never_changes_the_source_order`（`OrderDto` 全字段相等 + 应收不变）、
      `create_links_the_source_order_and_records_the_diff`（只读关联、客户快照正确）、
      `a_previous_day_order_can_still_be_linked`（历史单补录也不改原单）、
      `two_notes_may_reference_the_same_order_with_distinct_numbers`（多张备注单也不改原单）、
      `the_module_never_touches_the_order_tables_or_deletes_notes`（源码无订单表名 / 无 DELETE）。
- [x] 作废用例通过：
      `void_marks_the_note_without_touching_the_source_order`（作废留痕 + 原单 / 应收不变）、
      `void_keeps_the_original_diff_data_in_storage`（差异数据与单号保留）、
      `a_voided_note_cannot_be_voided_again_with_e4002`（重复作废被拒、首次留痕不被覆盖）、
      `void_rejects_an_unknown_note_with_e4001`、`void_rejects_a_blank_reason_or_operator_with_e422_and_writes_nothing`、
      `a_caller_rollback_keeps_the_note_active`（事务回滚撤销作废）。
- [x] 事务（铁律 4）：
      `a_caller_rollback_discards_the_note_and_the_note_serial`（备注单与取号一起回滚，重试不空号）、
      `convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction`
      （创建 / 作废各验证一次）。
- [x] 硬边界（铁律 2 / 3 / 7 / 8 / 10）：`NoteBoundaryTest` 7 例——Port 无角色 / token、
      只返回 DTO、DML 只在 `NoteRepository`、无单号前缀字面量 / 拼接、无打印逻辑、
      不出现订单表名 / DELETE、只拥有 `diff_note` 一张表。
- [x] 迁移与库层约束：`NoteMigrationsTest` 6 例——单号主键唯一、作废四列一致性、
      客户 / 备注 CHECK、版本号段与幂等。

## 集成前置

1. 根 `settings.gradle.kts` 需追加 `include(":fpk-server:note")`。本模块写入范围只有
   `fpk-server/note/**`，根工程由组合根统一维护；验证时用模块内临时根工程
   （`fpk-server/note/build/verify`，构建产物、不提交）跑通 `:fpk-server:note:test`。
2. 根工程需已有 `:shared`、`:fpk-server:db-core`、`:fpk-server:serial`、`:fpk-server:order`
   （及其依赖的 customer / category / pricing）；本模块 `build.gradle.kts` 已按
   `api(project(...))` 声明。
3. 组合根必须按上文顺序装配：先建 S08，再把同一个 `OrderPort` 与 `SerialPort` 注入 S10。
4. S13 必须在进入 `create` / `void` 前完成登录校验，并把已认证用户 id 作为
   `createdBy` / `voidedBy` 传入；本模块不做权限判断。

## 待确认的落地假设（v1.0.0 基线）

1. **备注单号走 S07，且本模块直接依赖 `SerialPort`**：任务卡的铁律 8 写作「单号仅 S08 通过
   SerialPort 获取」，但 `NotePort.create` 必须产出 `note_no`，而 S08 的 `OrderPort` 没有
   「取备注单号」能力，且本模块禁止修改 S08。`shared` 的 `SerialType.NOTE`（`BZ` 前缀）与
   S07 的通用取号接口正是为此准备的。本模块按「单号只能由 S07 产出、业务模块不得自行拼接」
   的实质执行：只调 `SerialPort.next(tx, SerialType.of(DocType.NOTE))`，没有任何前缀字面量。
   若必须严格保持「S10 不直接依赖 S07」，则需要 S08 按契约新增一个备注单号 Port 方法——
   这属于 S08 的接口扩展，不在本模块写入范围内。
2. **S08 尚未提供按单号读取订单的 Port 方法**：`OrderPort` 目前只有 `today` / `getRecent`
   （`OrderQuery` 没有 `orderNo` 条件）/ `getReceivable`。本模块用「当天单走 `today`，历史单先
   `getReceivable` 判存在，再按页 `getRecent` 找 DTO」的只读组合完成关联。这样不碰订单表、
   不改 S08，但历史单查找是 O(n) 翻页。建议 S08 后续按「接口只增不改」追加
   `get(tx, orderNo): OrderDto?`，S10 只替换查找实现即可。
3. **差异金额由客户端提交**：差异是「对账确认的业务事实」（例如实际到货与下单的差额），
   无法从原单的总额 / 应收派生。`shared` 的 `CreateNoteCmd` 因此包含 `diff_amount`。
   服务端仍负责：按 `Money` 规范化两位小数字符串、拒绝非法格式、原样存库；客户端不得做任何
   加总 / 换算（铁律 5 的展示口径不变）。
4. **备注单只作废、不删除、不改内容**：没有 `delete` / `update` 备注内容的 Port；作废是终态。
   作废原因必填（`E422`），保证留痕可追溯。
5. **不建到订单表的外键**：订单表归 S08 独占，跨模块外键会把两张表的生命周期绑在一起；
   原单存在性只在创建时经 `OrderPort` 校验，之后原单若发生业务变化（例如作废）不回写备注单。
6. **v1.0.0 只有 `create` / `void`**：S12 打印若要读备注单，需要按「接口只增不改」追加
   `NotePort.get(tx, noteNo): NoteDto`（或 `PrintPort` 自带读模型），本模块不擅自扩展。
7. **同一原单可有多张备注单**：不同差异分类 / 多次补录各自独立取号；没有幂等键，
   重复提交会产生多张备注单。若业务要求「同单同差异类型唯一」，需要契约层先新增幂等键或
   唯一约束，不在本模块擅自收紧。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 原单查找从「`today` + `getReceivable` + 按页 `getRecent`」改为 S08 新增的只读 `OrderPort.get(tx, orderNo)`（1.2.0）；单测仍全绿（29 例），行为不变、少一次翻页。 | 1.2.0 |
