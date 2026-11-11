# S06 · pricing

> contractVersion = **1.0.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

恒定价格：**客户 + 型号 + 批号类型** 一个维度一个单价；首次定价后当日可改、跨日锁定；
管理员改价必须留痕；价格变化通过 `PriceChanged` 事件触发 S08 重算当日订单。

写入范围：`fpk-server/pricing/**`。本模块自持 `price` / `price_change_log` 两张表，
SQL 只出现在 `PriceRepository`（铁律 10），客户 / 型号只经 S04 / S05 的 Port 获取（铁律 3）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `PricePort.kt` | **Port**（唯一对外入口）：`resolve` / `changePrice` / `history`，各带 tx 与便捷两种重载 |
| `PriceService.kt` | 实现：维度校验、批号规范化、跨日锁、改价留痕、事件派发、事务包裹 |
| `PriceRepository.kt` | 内部：两张表的**唯一** SQL 出口（价格查询 / 写入 / 留痕追加与历史查询） |
| `PriceChanged.kt` | `PriceChanged` 事件 + `PriceChangeListener` 出站 Port（S08 重算入口） |
| `PricingMigrations.kt` | 迁移 320：建两张表 + 留痕不可改 / 不可删触发器 |
| `PricingException.kt` | 失败异常，携带共享错误码 `E1001` / `E1002` / `E1003` / `E422` / `E404` |

测试：`PricingPortTest`（定价 / 当日改价 / 跨日锁 / 留痕 / 事件 / 事务）、
`PricingMigrationsTest`（建表 / 唯一约束 / 留痕不可删改 / 迁移幂等）、
`PricingBoundaryTest`（签名级硬边界 + SQL 位置 + 表归属）、
`PricingContractTest`（线格式）、`TestSupport`（固定时钟 + 内存库夹具）。

## 本模块实现的 Port

```kotlin
interface PricePort {
    fun resolve(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): PriceDto
    fun resolve(customerId: Long, categoryId: Long, batchTag: String): PriceDto

    fun changePrice(tx: TxContext, cmd: ChangePriceCmd, adminId: Long): PriceDto
    fun changePrice(cmd: ChangePriceCmd, adminId: Long): PriceDto

    // 额外只读入口，给 S14 审计 / 管理员查历史；不参与下单主链。
    fun history(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog>
    fun history(customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog>
}
```

- 出参一律 `shared` 的 `PriceDto` / `PriceChangeLog`，不暴露持久化结构（铁律 2）；
- 所有方法都有带 `TxContext` 的重载：S08 可以把「解析价格 + 写订单」放进同一个 db-core 事务；
  便捷重载内部仍走 `TxRunner.run {}`，不存在非事务写入（铁律 4）；
- 本模块**不判权限**（铁律 7）：参数里没有 token / 角色 / 账号 DTO；`changePrice` 的 `adminId`
  只是「这次改价记在谁头上」的留痕主体。S13 必须先 `verify → ADMIN`，通过后才传入已认证的管理员 id；
  非管理员在 S13 就被 403 拦下，不会进入本模块；
- 订单模块**禁止自行算价**：S08 只能调 `resolve` 取单价，再按数量算金额；本模块不依赖 S08。

## 数据模型（两张表，S06 独占）

```sql
price(
    id, customer_id, category_id, batch_tag, unit_price,
    effective_date, updated_at, updated_by,
    UNIQUE(customer_id, category_id, batch_tag)
)

price_change_log(
    id, price_id, customer_id, category_id, batch_tag,
    old_price, new_price, admin_id, reason, changed_at
)
```

| 列 | 口径 |
| --- | --- |
| `batch_tag` | 型号库中批号类型的**规范写法**；`COLLATE NOCASE`，大小写不敏感；空串表示该型号未使用批号 |
| `unit_price` / `old_price` / `new_price` | `Money.amount` 十进制字符串（两位小数），库层不使用浮点列 |
| `effective_date` | **首次定价日**（`WireFormat.DATE`，服务端时区），不是最后修改时间 |
| `updated_at` / `changed_at` | 服务端时间戳（`WireFormat.TIMESTAMP`，含时区偏移） |
| `updated_by` / `admin_id` | S13 已认证的管理员 id；仅留痕 / 事件，不用于权限判断 |
| `reason` | 改价原因，服务端裁剪首尾空白、非空、≤ 200 字符、无控制字符 |

留痕表**只增不改不删**：
- `PriceRepository` 没有删除 / 修改留痕的方法；
- 迁移 320 建了两个触发器，直接 `UPDATE` / `DELETE` 会被 `RAISE(ABORT)` 拒绝——
  即使绕过 Service 直接写 SQL 也删不掉（验收「禁止删除改价留痕」）。

## 锁口径（验收「跨日锁生效」）

1. `changePrice` 在维度不存在时插入 `price`，`effective_date = 今天`；
2. 同一自然日内再次 `changePrice`：只更新 `unit_price / updated_at / updated_by`，
   **不更新 `effective_date`**；
3. `locked = effective_date != 今天`；`resolve` 原样返回 `locked`，客户端只据此把输入框设为只读，
   不得自行比较日期；
4. 跨日后 `changePrice` 抛 `E1001`，价格与留痕都不写；
5. 锁是**按行**的：新客户 / 新型号 / 新批号在任何一天都可以首次定价，不受其它行锁定影响；
6. 日期由服务端 `Clock` 的时区判定（部署环境为 `Asia/Shanghai`），客户端本地时间不参与。

> 例：11-11 10:00 首次定价 12.30；11-11 18:00 可改成 13.00（仍是 11-11）；11-12 00:00 起锁定，
> `resolve.locked = true`，再改抛 `E1001`。

## 改价与留痕（验收「改价有留痕」）

`changePrice` 的顺序固定为：

```
校验 adminId / reason / newPrice
  → 经 CustomerPort.get / CategoryPort.get 校验维度、规范化批号
  → 查 price（不存在则首次定价，直接返回，不留痕）
  → 跨日检查（E1001）
  → 同价短路（幂等，不写库、不留痕、不派发事件）
  → UPDATE price
  → INSERT price_change_log（old_price / new_price / admin_id / reason / changed_at）
  → 回读 PriceDto
  → 同步派发 PriceChanged
```

- 价格更新与留痕写入在**同一个事务**内：任一步失败整体回滚，不会出现「改了价但没有留痕」；
- 首次定价不是「改价」：`PriceChangeLog.old_price` 非空，没有旧价可记；创建者记录在
  `PriceDto.updated_by`，不伪造一条 old = new 的留痕；
- 同价重复提交是幂等的：返回当前价格，不产生新留痕、不触发重算；
- 改价历史按 `changed_at DESC, id DESC` 返回，最新在前；`history` 不校验客户 / 型号是否仍存在，
  主数据删除后留痕仍然可查。

## PriceChanged 触发重算（验收「PriceChanged 触发重算」）

```kotlin
data class PriceChanged(
    val priceId: Long,
    val customerId: Long,
    val categoryId: Long,
    val batchTag: String,
    val oldPrice: Money,
    val newPrice: Money,
    val adminId: Long,
    val reason: String,
    val changedAt: String,
)

fun interface PriceChangeListener {
    fun onPriceChanged(event: PriceChanged)
}
```

- 这是**出站 Port**：S06 不依赖 S08，S08 实现监听器，组合根注入；S06 只负责在价格真的变化后调用；
- 事件在**同一事务内**、价格与留痕写入成功后同步派发；监听器（S08）收到后重算受影响的当日订单；
- 监听器抛异常 → 本次改价整体回滚（价格、留痕都不会留下），因此监听实现必须幂等，
  不得做不可回滚的外部副作用；
- 首次定价、同价重复提交**不派发**事件（没有旧价 / 没有变化，不需要重算）；
- `PriceChanged` 是服务端内部事件，不是跨端线格式；`shared` 契约中没有它。

## 批号口径

批号类型来自 `CategoryDto.batchTags`（S05 配置，大小写不敏感去重）。本模块：

- 入参批号先裁剪首尾空白、拒绝控制字符、≤ 32 字符；
- 在型号的 `batchTags` 中**大小写不敏感**匹配，并保留型号库里的规范写法；
  `"a"` 与 `"A"` 是同一个维度，库里只存一行；
- 型号配置了批号类型：
  - `batchTagRequired = true`：必须命中其中之一，空串 → `E422`；
  - `batchTagRequired = false`：允许空串（表示不带批号），传了值则仍必须命中；
- 型号未配置批号类型：只允许空串；传了值 → `E422`。

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 价格已跨日锁定仍尝试改价 | `E1001` | 409 | 不写价格、不写留痕 |
| `resolve` 未配置价格 | `E1002` | 404 | 先定价再下单 |
| 改价原因为空 / 全空白 | `E1003` | 422 | 不写库 |
| `customerId` / `categoryId` / `adminId` ≤ 0、价格 ≤ 0、原因超长、批号非法 | `E422` | 422 | 不写库 |
| 客户 / 型号不存在 | `E404` | 404 | 保留 S04 / S05 的错误码 |
| `price` 维度重复（并发兜底） | 不抛错 | 200 | 插入冲突后回查已有行，按「改价 / 锁定 / 同价」处理 |
| 非管理员改价 | `E403`（S13） | 403 | 本模块不抛；S13 在进入 Port 前拦截 |

## 事务与并发

- 全部写操作经 `TxRunner.run {}`（含便捷重载）；带 tx 重载直接复用调用方事务，
  外层回滚会同时丢弃价格与留痕；
- 单连接 + `TxRunner` 锁串行化写入；`UNIQUE (customer_id, category_id, batch_tag)` 是并发首次定价的
  最终兜底：插入撞唯一约束时回查已有行，按「跨日锁 / 同价 / 改价」继续，不会留下两行价格；
- 价格更新、留痕追加、事件派发在同一个事务内，不存在「价格改了、留痕丢了」的中间态。

## 迁移（版本 320）

```kotlin
DbCore.open(config, PricingMigrations.all + otherModules)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 320 | `create_pricing_tables` | `price`（维度唯一、金额非空、日期格式 CHECK）、`price_change_log`（外键引用 `price`、原因非空）、维度索引、留痕 `UPDATE` / `DELETE` 拒绝触发器 |

S04 用 300、S05 用 310、S06 用 320（300–399 段位内顺延）。迁移只增不改；
改表追加 321、322…（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"),
                     CustomerMigrations.all + CategoryMigrations.all + PricingMigrations.all + otherModules)
db.migrator.migrate()

val customer: CustomerPort = CustomerService(db.txRunner)
val category: CategoryPort = CategoryService(db.txRunner)
val pricing: PricePort = PriceService(
    txRunner = db.txRunner,
    customers = customer,
    categories = category,
    clock = Clock.systemDefaultZone(),
    listeners = listOf(orderPriceRecalculator),   // S08 实现 PriceChangeListener
)

// S13 路由（示意）：
//   POST /pricing/resolve        → pricing.resolve(customerId, categoryId, batchTag)
//   POST /admin/pricing/change   → verify → ADMIN，否则 E403；通过后 pricing.changePrice(cmd, adminId)
//   GET  /admin/pricing/history  → verify → ADMIN；pricing.history(...)
// 权限只在 S13 判断，本模块不接收角色 / token。
```

## 验收对照

本机用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:pricing:test`：**31 个用例全部通过（0 失败 / 0 跳过）**
（`PricingPortTest` 19 + `PricingMigrationsTest` 4 + `PricingBoundaryTest` 5 + `PricingContractTest` 3）。

- [x] 跨日锁生效：`cross_day_change_is_rejected_with_e1001_and_writes_nothing`
      （次日改价 `E1001`、价格不变、留痕 0 条）、
      `resolve_reports_a_price_locked_after_the_pricing_day`（当日 `locked=false`，次日 `locked=true`）、
      `a_new_dimension_can_be_priced_on_a_later_day`（锁按行，不影响新维度）。
- [x] 改价有留痕：`same_day_change_updates_the_price_and_appends_an_audit_log`
      （old/new/admin/reason/changed_at 全部落库）、
      `second_change_appends_another_log_without_losing_history`（两次改价两条留痕，不覆盖）、
      `price_change_log_rejects_update_and_delete_at_the_database_layer`（库层触发器拒绝改 / 删）、
      `history_is_readable_after_master_data_is_deleted`（主数据删除后留痕仍可查）。
- [x] PriceChanged 触发重算：`price_changed_is_published_once_with_old_and_new_price`
      （改价派发一次、字段完整；首次定价 / 同价不派发）、
      `a_failing_listener_rolls_back_the_price_change_and_the_log`（重算失败整体回滚）、
      `same_price_is_an_idempotent_no_op_without_log_or_event`。
- [x] 当日可改：`same_day_change_updates_the_price_and_appends_an_audit_log`（`effective_date` 不变）。
- [x] 恒定值 / 维度唯一：`first_pricing_creates_an_unlocked_price_and_resolve_returns_it`、
      `price_dimension_is_unique_and_rejects_a_second_row`、`batch_tag_must_match_the_category_and_is_case_insensitive`。
- [x] 事务（铁律 4）：`convenience_overloads_open_exactly_one_transaction_and_tx_overloads_reuse_the_caller_transaction`、
      `a_caller_rollback_discards_both_the_price_change_and_the_log`（外层回滚同时丢弃价格与留痕）、
      `a_failing_listener_rolls_back_the_price_change_and_the_log`。
- [x] 硬边界（铁律 2 / 3 / 7 / 10）：`PricingBoundaryTest` 5 例——Port 无角色 / token、
      只返回 DTO、DML 只在 `PriceRepository`、留痕无删除路径、只拥有两张表。

## 集成前置

1. 根 `settings.gradle.kts` 需追加：
   ```kotlin
   include(":fpk-server:pricing")
   ```
   （本次写入范围只有 `fpk-server/pricing/**`，未改根工程；验证时用临时根工程指向本模块。）
2. 根 `settings.gradle.kts` 同时需要已有 `:fpk-server:customer`、`:fpk-server:category`；本模块的
   `build.gradle.kts` 已按 `api(project(":fpk-server:customer"))` / `api(project(":fpk-server:category"))` 声明依赖。
3. S13 路由必须在进入 `changePrice` / `history` 前完成登录与管理员权限拦截，并把已认证的 `adminId` 传入；
   `resolve` 供 S08 下单使用，不需要管理员权限。
4. S08 实现 `PriceChangeListener` 并在组合根注入；监听器内部用同一个 `TxContext` 重算当日订单，
   不要另开事务、不要做不可回滚的外部副作用。

## 待确认的落地假设（v1.0.0 基线）

1. 包名 `com.jiancai.pricing`（沿用 `com.jiancai` 前缀），表名 `price` / `price_change_log`。
2. **首次定价不留痕**：`PriceChangeLog.old_price` 在共享契约中非空，首次定价没有旧价；
   创建者由 `PriceDto.updated_by` 记录。若审计要求「建档也要一条记录」，需要先在 `shared` 把
   `old_price` 改为可空（契约主版本变更），不在本模块擅自伪造 old = new。
3. **锁的锚点是首次定价日**：当日改价不刷新 `effective_date`，因此 23:59 改价也会在 00:00 锁定。
   若业务希望「每次改价重新锁一天」，需要改锁口径（契约语义变更），不在本模块擅自扩展。
4. **价格必须大于 0**：`Money` 允许负数，但单价 0 视为「未定价」而不是「免费」；确需 0 元单价时
   需要产品确认后放开。
5. **批号按型号当前配置规范化**：如果管理员删除某个批号类型，引用它的旧价格将无法再被新订单
   `resolve`（`E422`）；建议用停用型号 / 保留批号类型而不是删除，历史订单已保存价格快照，不受影响。
6. **改价原因**限制 200 字符、拒绝控制字符；与原因为空 `E1003` 的契约一致。
7. `PriceChanged` 是服务端内部事件，不进 `shared`；若未来需要跨进程 / 持久化事件，再按契约只增不改
   追加 outbox，不在本模块自行引入消息中间件。
8. 根 `settings.gradle.kts` 尚未 include 本模块（写入范围限制）；本机用模块内临时验证根工程
   （`fpk-server/pricing/build/verify`，构建产物，不提交）跑通 `:fpk-server:pricing:test`。
