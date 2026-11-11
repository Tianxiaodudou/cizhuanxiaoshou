# S08 · order

> contractVersion = **1.1.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

订单抬头 / 明细、建单幂等、改单乐观锁、仅当日单可改、应收与服务端金额派生、
退货单原单关联、按单号精确查询，以及消费 S06 的 `PriceChanged` 重算当日订单。

- **单号只来自 S07**：建单时调 `SerialPort.next(tx, SerialType.of(docType))`，
  本模块没有任何前缀字面量，也不拼接 / 截取单号（铁律 8）；
- **单价只来自 S06**：每条明细调 `PricePort.resolve` 取单价，本模块只按数量派生金额（铁律 5）；
- **客户 / 型号只经 Port**：客户快照来自 `CustomerPort.get`，型号快照与批号配置来自
  `CategoryPort.get`，不跨模块读写表（铁律 3 / 10）；
- **不判权限**：Port 参数里没有角色 / token；权限统一由 S13 api-gateway 拦截（铁律 7）。

写入范围：`fpk-server/order/**`（本模块独占 `order_header` / `order_line` 两张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `OrderPort.kt` | **Port**（唯一对外入口）：`create` / `update` / `today` / `getRecent` / `get` / `getReceivable` / `nextNoteNo`，各带 tx 与便捷重载 |
| `OrderService.kt` | 实现：幂等、取号、明细校验、单价解析、金额 / 应收 / 状态派生、乐观锁、当日单、查询、事务包裹 |
| `OrderPriceRecalculator.kt` | 实现 S06 的 `PriceChangeListener`：同一事务内重算受影响的当日订单 |
| `OrderRepository.kt` | 内部：两张表的**唯一** SQL 出口（幂等查询、分页、条件更新、明细替换） |
| `OrderMath.kt` | 内部：金额 / 总额 / 应收 / 状态的纯函数（不解析单价、不生成单号） |
| `OrderMigrations.kt` | 迁移 400（建表）+ 401（追加退货单原单号） |
| `OrderException.kt` | 失败异常，携带共享错误码 `E2xxx` / `E422` / `E404` / `E1002` / `E3004` / `E3005` / `E9001` |

测试：`OrderPortTest`（31 例）、`OrderMigrationsTest`（7 例）、`OrderBoundaryTest`（6 例）、
`TestSupport`（固定时钟 + 内存库 + 真实 S04 / S05 / S06 / S07 夹具）。

## 本模块实现的 Port

```kotlin
interface OrderPort {
    fun create(tx: TxContext, cmd: CreateOrderCmd, createdBy: Long): OrderDto
    fun create(cmd: CreateOrderCmd, createdBy: Long): OrderDto

    fun update(tx: TxContext, cmd: UpdateOrderCmd): OrderDto
    fun update(cmd: UpdateOrderCmd): OrderDto

    fun today(tx: TxContext): List<OrderDto>
    fun today(): List<OrderDto>

    fun getRecent(tx: TxContext, query: OrderQuery): PageDto<OrderDto>
    fun getRecent(query: OrderQuery): PageDto<OrderDto>

    // contractVersion 1.2.0 追加（接口只增不改）：按单号精确读，S09/S10/S11/S13 关联原单用
    fun get(tx: TxContext, orderNo: String): OrderDto?
    fun get(orderNo: String): OrderDto?

    fun getReceivable(tx: TxContext, orderNo: String): Money
    fun getReceivable(orderNo: String): Money

    fun nextNoteNo(tx: TxContext): String
    fun nextNoteNo(): String
}
```

- 出参一律 `shared` 的 `OrderDto` / `PageDto` / `Money`，不暴露持久化结构（铁律 2）；
- 每个方法都有带 `TxContext` 的重载：S09 / S10 / S11 可把「订单 + 自己的写入」放进同一个
  db-core 事务；便捷重载内部走 `TxRunner.run {}`，不存在非事务写入（铁律 4）；
- `createdBy` 是已由 S13 认证的操作者 id，只用于 `created_by` 留痕，不是权限判断；
- `getRecent(query.phone)` 经 S04 `CustomerPort.findByPhone` 解析客户，查不到返回空页，
  不扫描客户表（铁律 3）；`getRecent(query.orderNo)` 按单号精确过滤（1.1.0），
  S09 / S10 / S11 不再靠翻页或 `getReceivable` 探测原单；
- `nextNoteNo` 是给 S10 note 的可选代取号入口：与本模块直接调 S07 的实现共用同一条 `BZ`
  序列；S10 若直接依赖 S07 的 `SerialPort.nextNote` 也等价，任何路径都禁止自拼前缀；
  S10 应把 `nextNoteNo(tx)` 与备注单写入放进同一个事务。

## 备注单号代取（S10 用）

```kotlin
// S10 note：备注单写入与取号同一个事务，失败一起回滚，不会留下空号
db.txRunner.run { tx ->
    val noteNo = order.nextNoteNo(tx)          // BZ + yyyyMMdd + 4 位序号
    noteRepository.insert(tx, noteNo, ...)
}
```

- 前缀只能来自 `SerialType.NOTE`（`BZ`），本模块不写前缀字面量、不拼号（铁律 8）；
- 便捷重载 `nextNoteNo()` 内部自开事务，适合独立取号；与备注单同事务时必须用带 tx 的重载；
- 失败码 `E9001`（409 + retryable）原样来自 S07，S13 直接按 `ErrorCode` 映射。

## 数据模型（两张表，S08 独占）

```sql
order_header(
    order_no PK, order_type, status, customer_id, customer_name, customer_phone,
    customer_address, order_date, total_amount, receivable_amount, received_amount,
    remark, version, client_request_id UNIQUE, created_at, created_by, updated_at,
    source_order_no
)

order_line(
    order_no, seq, category_id, category_name, model, batch_tag, unit,
    quantity, unit_price, amount, packing_spec,
    PRIMARY KEY (order_no, seq),
    FOREIGN KEY (order_no) REFERENCES order_header(order_no) ON DELETE CASCADE
)
```

| 列 | 口径 |
| --- | --- |
| `order_no` | S07 产出（`PS/PT/ZS/ZT` + `yyyyMMdd` + 4 位序号），本模块只保存 |
| `order_type` / `status` / `unit` | 枚举名（内部持久化）；线值仍由 shared 的 `@SerialName` 决定 |
| `order_date` | 服务端业务日期 `yyyy-MM-dd`（`WireFormat.DATE`），库层 GLOB 形状校验 |
| `total_amount` / `receivable_amount` / `received_amount` | `Money` 十进制字符串（两位小数），不用浮点列 |
| `version` | 乐观锁版本，建单为 1，每次成功改单 +1 |
| `client_request_id` | 幂等键，`UNIQUE`；同一请求 id 只会有一张订单 |
| `source_order_no` | 1.1.0：退货单关联的原单号（`OrderDto.sourceOrderNo`）；非退货单 / 历史退货单为 NULL，改单不可变更 |
| 客户 / 型号快照 | 下单时复制名称、电话、地址、品类名、型号、单位、装箱规格；主数据后续变化不回改历史订单 |
| `packing_spec` | `shared` 的 `PackingSpec` JSON 快照（打印 / 展示用），服务端只保存不展示 |

## 金额与应收派生（验收「应收派生正确」）

```
明细金额 = round(从 S06 resolve 的 unit_price × 数量, 2, HALF_UP)
订单总额 = Σ 明细金额
应收 = 采购 / 直送 → +总额；采购退货 / 直送退货 → -总额
状态 = 已收 / 应收的纯函数：
       已收 = 0            → CREATED
       0 < 已收 < 应收     → PARTIALLY_SETTLED
       已收 ≥ 应收         → SETTLED
```

- 数量只接受十进制字符串，最多 3 位小数、必须 > 0；格式 / 归一化统一走 shared 的
  `Quantity.ofPlain`（`1.500` → `1.5`），本模块不自写数字规则；
- 批号裁剪与比较统一走 shared 的 `BatchTagRules`（trim + ASCII 大小写不敏感），
  与库层 `COLLATE NOCASE` 对齐；
- 明细金额与总额全部由服务端派生，客户端只展示（铁律 5）；
- 退货单的总额仍为正数、应收为负数，S11 结算时可与原单应收冲抵；
- v1.1.0 建单 `received_amount = 0.00`，因此新单状态恒为 `CREATED`；已收 / 未收读模型归 S11，
  本模块不读结算表（见「待确认的落地假设」）。

## 退货单关联原单（1.1.0）

```kotlin
// S09 校验通过后，S13 把 ReturnCheckResultDto.orderNo 原样放进 CreateOrderCmd
order.create(
    CreateOrderCmd(
        clientRequestId = requestId,
        orderType = OrderType.PURCHASE_RETURN,
        customerId = customerId,
        lines = lines,
        sourceOrderNo = checkResult.orderNo,   // 1.1.0 唯一真源字段
    ),
    operatorId,
)
```

- 持久化到 `order_header.source_order_no`，`OrderDto` 原样回填；改单不可变更；
- 传值时必须存在、同客户、对应正向类型（采购退货 → 采购单、直送退货 → 直送单）且未作废：
  不存在 / 客户不符 / 类型不符 → `E3005`，已作废 → `E3004`；
- 非退货单携带 `sourceOrderNo` → `E422`；
- **兼容口径**：`sourceOrderNo = null` 的退货单仍允许创建（1.0.0 历史数据与 S09
  `requireSourceOrderLink = false` 兼容归集），S09 的新退货单应始终带原单号；
- 历史退货单无法反推原单，迁移 401 只加空列，保持 NULL，不做伪造回填。

## 幂等（验收「幂等通过」）

1. 建单先按 `client_request_id` 查已有订单；命中直接返回**同一张订单**，不再取号 / 写明细；
2. 数据库 `client_request_id UNIQUE` 是并发兜底：两个请求同时到达时，后提交者捕获唯一冲突后
   按幂等键回查并返回首单；
3. 幂等键在事务内落库：订单失败回滚会一并回滚幂等键、单号账本与计数器，重试不会留下半条数据；
4. 同一请求 id 即使载荷不同也返回首单（幂等语义优先于「参数校验」），客户端不得复用请求 id。

## 乐观锁与改当日单（验收「乐观锁通过」「改当日单正确」）

```
读取订单（不存在 → E2001）
  → cmd.version != header.version → E2002
  → 已结清 → E2004；已作废 → E2003
  → order_date != 服务端今天 → E2003
  → 重新校验明细、经 S06 重算金额 / 应收 / 状态
  → UPDATE ... WHERE order_no = ? AND version = ?（0 行 → E2002）
  → 整单替换明细（同一事务）
```

- 改单是全量覆盖明细 + 备注；客户、订单号、`created_at` 不变，`received_amount` 保留；
- 只有服务端当天（`Clock` 时区）的订单可改，跨过午夜即锁定；
- 并发修改由条件更新兜底：即使两个请求都读到同一版本，只有一个能更新成功，另一个得到 `E2002`；
- 版本号是客户端刷新 / 冲突提示的唯一依据，客户端不得自行递增。

## PriceChanged 重算（验收「改价联动」）

`OrderPriceRecalculator` 实现 S06 的出站 Port `PriceChangeListener`：

- 组合根先建它、再建 `PriceService`、最后建 `OrderService`，避免构造环：

```kotlin
val recalculator = OrderPriceRecalculator(db.txRunner, clock)
val pricing = PriceService(db.txRunner, customer, category, clock, listOf(recalculator))
val serial = SerialService(db.txRunner, clock)
val order = OrderService(db.txRunner, serial, pricing, customer, category, clock)
```

- S06 在改价事务内同步调用监听器；监听器用同一个 `txRunner.run {}` 嵌套（SAVEPOINT），
  与改价 / 留痕共享事务——重算失败会让改价一起回滚；
- 只重算 `order_date` = 服务端今天、状态为 `CREATED` / `PARTIALLY_SETTLED` 的订单；
- 只替换命中价格维度（客户 + 型号 + 批号，批号大小写不敏感）的明细单价 / 金额，
  数量与型号快照不变；总额、应收、状态重新派生，`version + 1`；
- 重复事件 / 金额未变化时跳过且不推进版本，监听器幂等；不做任何不可回滚的外部副作用。

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 订单不存在（update / getReceivable） | `E2001` | 404 | 不写库 |
| 乐观锁版本不一致 | `E2002` | 409 | 客户端刷新后重试 |
| 非当日订单 / 已作废订单 | `E2003` | 409 | 不写库 |
| 已结清订单改单 | `E2004` | 409 | 不写库 |
| 明细为空 | `E2005` | 422 | 不写库 |
| 数量 ≤ 0 | `E2006` | 422 | 不写库 |
| 批号与型号不匹配 | `E2007` | 422 | 不写库 |
| 日期 / 页码 / 长度 / 控制字符 / 单位不匹配 | `E422` | 422 | 不写库 |
| 客户 / 型号不存在 | `E404` | 404 | 保留 S04 / S05 错误码 |
| 未配置价格 | `E1002` | 404 | 保留 S06 错误码，先定价再下单 |
| 退货单原单已作废 | `E3004` | 409 | 不写库；1.1.0 起 |
| 退货单原单不存在 / 客户或类型不符 | `E3005` | 422 | 不写库；1.1.0 起 |
| 取号冲突 / 序号用尽 | `E9001` | 409 | 保留 S07 错误码，可重试 |

`E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。

## 事务与并发

- 全部写操作经 `TxRunner.run {}`（含便捷重载）；带 tx 重载直接复用调用方事务，
  外层回滚会同时丢弃订单、明细、幂等键与 S07 的取号账本；
- 单连接 + `TxRunner` 锁串行化写入；`client_request_id UNIQUE` 是并发建单的最终兜底，
  `order_line` 外键保证不会出现「有明细、无抬头」；
- 改单的「抬头条件更新 + 明细删除 + 明细插入」在同一事务内，不存在「版本已涨但明细没换」的中间态；
- `PriceChanged` 重算与 S06 改价同事务，重算失败整体回滚。

## 迁移（版本 400 + 401）

```kotlin
DbCore.open(config, OrderMigrations.all + otherModules)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 400 | `create_order_tables` | `order_header`（幂等键 / 版本 / 金额 / 日期 CHECK）、`order_line`（外键级联、快照列、`(order_no, seq)` 主键）、客户 + 日期与价格维度索引 |
| 401 | `add_order_source_order_no` | 1.1.0：`order_header.source_order_no`（可空，历史行保持 NULL）+ 原单号索引 |

S04 用 300、S05 用 310、S06 用 320、S07 用 100、S08 用 400–401（db-core 的 400–499 段位）。
迁移只增不改；改表继续追加 402、403…（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(
    DbConfig.file("/var/lib/jiancai/jiancai.db"),
    SerialMigrations.all + CustomerMigrations.all + CategoryMigrations.all +
        PricingMigrations.all + OrderMigrations.all + otherModules,
)
db.migrator.migrate()

val customer: CustomerPort = CustomerService(db.txRunner)
val category: CategoryPort = CategoryService(db.txRunner)
val serial: SerialPort = SerialService(db.txRunner)
val recalculator = OrderPriceRecalculator(db.txRunner)
val pricing: PricePort = PriceService(
    db.txRunner, customer, category, Clock.systemDefaultZone(), listOf(recalculator),
)
val order: OrderPort = OrderService(
    db.txRunner, serial, pricing, customer, category, Clock.systemDefaultZone(),
)
```

S13 路由（示意，权限在网关）：

```
POST /orders                → order.create(cmd, 已认证 userId)
PUT  /orders/{orderNo}      → order.update(cmd)
GET  /orders/today          → order.today()
GET  /orders                → order.getRecent(query)     // query.phone 支持按电话查历史
GET  /orders/{orderNo}/receivable → order.getReceivable(orderNo)
```

## 验收对照

本机用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:order:test`：**44 个用例全部通过（0 失败 / 0 跳过）**
（`OrderPortTest` 31 + `OrderMigrationsTest` 7 + `OrderBoundaryTest` 6）。

- [x] 幂等：`same_client_request_id_returns_the_same_order_without_duplicating_rows_or_serials`
      （同单号、同版本、明细不被覆盖、`serial_number` 只有 1 条）、
      `client_request_id_is_unique_at_the_database_layer`（库层唯一约束兜底）。
- [x] 乐观锁：`a_stale_version_is_rejected_with_e2002_and_writes_nothing`
      （旧版本 `E2002`、版本 / 金额 / 数量都不变）。
- [x] 改当日单：`only_same_day_orders_can_be_updated`（次日改单 `E2003`）、
      `update_recomputes_amounts_receivable_and_bumps_version`（版本 +1、金额重算）、
      `a_settled_or_void_order_cannot_be_updated`（`E2004` / `E2003`）。
- [x] 应收派生：`all_four_order_types_use_the_serial_prefix_and_return_orders_are_negative_receivable`
      （采购 / 直送为正、退货为负）、`quantity_is_normalized_and_amounts_round_half_up_to_cents`
      （`HALF_UP` 到分）、`a_multi_line_order_sums_amounts_and_recalculates_only_the_matching_lines`
      （多行总额 / 只重算命中维度）、`the_receivable_of_a_void_order_is_zero`、
      `get_recent_filters_by_phone_type_dates_and_pages`。
- [x] 改价联动：`a_price_change_recalculates_same_day_orders_and_bumps_the_version`、
      `a_price_change_does_not_touch_other_customers_orders`、`a_price_change_skips_settled_orders`、
      `a_price_change_skips_historical_orders`、`replaying_the_same_price_event_is_idempotent`。
- [x] 事务（铁律 4）：`convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction`、
      `caller_rollback_discards_the_order_the_serial_and_the_idempotency_key`（回滚后号可复用）。
- [x] 备注单号代取：`note_numbers_use_the_bz_prefix_and_roll_back_with_the_callers_transaction`
      （`BZ` + 12 位、按日递增、调用方回滚后号可复用）、
      `convenience_overloads_open_one_transaction_and_tx_overloads_reuse_the_caller_transaction`
      （`nextNoteNo(tx)` 不另开事务）。
- [x] 1.1.0 原单关联：`return_orders_persist_source_order_no_and_keep_it_immutable`
      （落库 / 回填 / 改单不可变）、`non_return_orders_cannot_carry_a_source_order_no`（`E422`）、
      `return_orders_reject_missing_cross_customer_mistyped_or_void_sources`（`E3005` / `E3004`）、
      `source_order_no_is_nullable_for_legacy_rows_and_persisted_when_present`（迁移 401）。
- [x] 1.1.0 按单号查询：`get_recent_supports_exact_order_no_lookup`（精确命中 / 未知为空 / 空白 `E422`）。
- [x] 硬边界（铁律 2 / 3 / 7 / 8 / 10）：`OrderBoundaryTest` 6 例——Port 无角色 / token、
      只返回 DTO、DML 只在 `OrderRepository`、无单号前缀字面量 / 拼接、无打印逻辑、只拥有两张表。

## 集成前置

1. 根 `settings.gradle.kts` 需追加 `include(":fpk-server:order")`。本模块写入范围只有
   `fpk-server/order/**`，根工程由组合根统一维护；验证时用模块内临时根工程
   （`fpk-server/order/build/verify`，构建产物、不提交）跑通 `:fpk-server:order:test`。
2. 根工程需已有 `:shared`、`:fpk-server:db-core`、`:fpk-server:serial`、`:fpk-server:customer`、
   `:fpk-server:category`、`:fpk-server:pricing`；本模块 `build.gradle.kts` 已按 `api(project(...))` 声明。
3. 组合根必须把 `OrderPriceRecalculator` 注入 `PriceService.listeners`，并按上文顺序装配
   （先 recalculator、再 pricing、最后 order），否则改价不会联动订单或产生构造环。
4. S13 必须在进入 `create` / `update` 前完成登录校验，并把已认证用户 id 作为 `createdBy` 传入；
   本模块不做权限判断。

## 待确认的落地假设（v1.1.0 基线）

1. 包名 `com.jiancai.order`（沿用 `com.jiancai` 前缀），表名 `order_header` / `order_line`
   （`order` 是 SQL 关键字，不使用裸表名）。
2. **退货单应收为负**：采购 / 直送为正，采购退货 / 直送退货为负（总额仍为正数）。
   若财务口径要求「应收永远为正、方向由单据类型表达」，只需改 `OrderMath.receivable`，
   属于业务语义调整，不在本模块擅自扩展。
3. **v1.1.0 不接收结算回写**：建单 `received_amount = 0.00`，状态由已收 / 应收派生，
   因此新单恒为 `CREATED`；已收 / 未收读模型由 S11 用自己的收款记录派生。未来结算回写订单时，
   按契约「接口只增不改」给 `OrderPort` 追加方法（例如 `recordReceived`），不在本模块偷读结算表。
4. **明细单位 = 型号计价单位**：订单行必须使用 `CategoryDto.unit`，本模块不做单位换算；
   装箱规格只做快照，供打印 / 展示。若业务需要按箱 / 袋下单并自动换算，需先明确换算公式
   （base ↔ package、价格按哪个单位），再在 shared / Port 层扩展。
5. **幂等键全局唯一**：同一 `client_request_id` 只对应一张订单，重复提交返回首单，
   即使载荷不同也不覆盖；客户端每次新建单必须生成新的请求 id。
6. **改单是全量覆盖**：明细按入参顺序重排 `seq`，备注按入参覆盖；客户与订单号不可改。
7. **状态是纯派生值**：不持久化「客户端传入的状态」；`VOID` 目前无 Port 可达
   （未来由作废流程按契约追加），但改单已把它当作不可修改状态拦截。
8. **`getReceivable` 只返回订单应收**：作废订单返回 `0.00`；已收 / 未收由 S11 结合自己的
   收款记录计算，本模块不返回 `received_amount` 的实时值。
9. **批号不匹配映射 `E2007`**：型号未配置批号却传值、必填批号为空、批号不在型号配置中，
   均由本模块在调 S06 前拦截；其它价格错误（未定价 `E1002`）保留 S06 错误码。
10. **备注单号只开放 `nextNoteNo`**：目前只补 S10 需要的 `BZ` 号；结算单 `JS` 等其它代取号
    按需再以「接口只增不改」追加，不在本模块一次性放开通用取号入口。
11. **`sourceOrderNo` 兼容空值**：契约 1.1.0 描述退货单「必须带原单号」，但为兼容 1.0.0
    已存在的退货单与 S09 的兼容归集模式，本模块允许 `null`；传值时才做存在性 / 客户 / 类型 /
    作废校验。历史退货单无法反推原单，迁移 401 不做伪造回填，保持 NULL。
12. **数量 / 批号口径统一走 shared**：`Quantity.ofPlain` 负责数量格式、符号、3 位小数与去尾零；
    `BatchTagRules` 负责批号 trim 与 ASCII 大小写不敏感比较，本模块不再维护第二套规则。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 追加只读 `get(tx, orderNo)` / `get(orderNo)`：按单号精确读单（接口只增不改）；`getRecent(OrderQuery.orderNo)` 保留为兼容路径。S13 网关与 S10 备注单改用它，不再翻页探测。 | 1.2.0 |
