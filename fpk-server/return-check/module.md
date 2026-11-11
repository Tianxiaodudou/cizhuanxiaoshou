# S09 · return-check

> contractVersion = **1.1.0**（1.1.0 起 `ReturnCheck*Dto` / `Quantity` / `BatchTagRules` / `E3004` / `E3005` / `E413`
> 全部由 `shared` 持有唯一真源，本模块只 import）

## 职责

可退数量计算、批次校验。**规则独立**：判定口径收敛在 `ReturnCheckPolicy` + `ReturnRules`（纯函数），
换口径只换策略，Port 与读取路径不动。

- **只读**：本模块**没有任何表、没有迁移、没有 SQL**，也不改订单数据——可退数量完全由 S08 的单据派生；
- **只经 S08 的 Port 读**：原单与退货单来自 `OrderPort`，不碰 `order_header` / `order_line`（铁律 3 / 10）；
- **线格式只在 shared**：出参 DTO 全部 import 自 `com.jiancai.shared.dto`，本模块不再定义 `@Serializable`（铁律 9）；
- **数量 / 批号口径只在 shared**：数量走 `Quantity.ofPlain`（`WireFormat.QUANTITY_MAX_SCALE` = 3、去尾零、> 0），
  批号走 `BatchTagRules`（trim + ASCII 大小写不敏感，对齐 S08 的 `COLLATE NOCASE`）；
- **不生成单号**：退货单号仍由 S08 经 S07 产出（铁律 8），本模块只回答「能不能退、还能退多少」；
- **不判权限**：Port 参数里没有角色 / token；权限统一由 S13 api-gateway 拦截（铁律 7）。

写入范围：`fpk-server/return-check/`（本模块不写库，此目录只放代码与测试）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `ReturnCheckPort.kt` | **Port**（唯一对外入口）：`availableBatches` / `validate`，各带 tx 与便捷重载 |
| `ReturnCheckService.kt` | 实现：事务包裹 + 只经 `OrderPort` 读取 + 交给规则判定（无业务口径） |
| `ReturnRules.kt` | 内部：**纯函数**——已退数量汇总、可退批次派生、逐行校验；数量 / 批号调 shared |
| `ReturnCheckPolicy.kt` | 内部规则开关（可注入替换）：可退单据类型、退货单类型、作废 / 归属口径、行数 / 分页上限 |
| `ReturnCheckException.kt` | 失败异常，携带共享错误码 `E3001`–`E3005` / `E413` / `E422` |

线格式 DTO（shared，1.1.0）：`com.jiancai.shared.dto.ReturnableBatchDto` / `ReturnCheckResultDto` /
`ReturnCheckLineDto`。

测试：`ReturnCheckPortTest`（16 例，真实 S04–S08）、`ReturnRulesTest`（22 例，纯规则）、
`ReturnCheckBoundaryTest`（11 例，硬边界），合计 **49 例**。

## 本模块实现的 Port

```kotlin
interface ReturnCheckPort {
    fun availableBatches(tx: TxContext, original: OrderDto): List<ReturnableBatchDto>
    fun availableBatches(original: OrderDto): List<ReturnableBatchDto>

    fun validate(tx: TxContext, original: OrderDto, lines: List<OrderLineCmd>): ReturnCheckResultDto
    fun validate(original: OrderDto, lines: List<OrderLineCmd>): ReturnCheckResultDto
}
```

- **入参为什么带 `OrderDto`**：S08 的 Port 目前仍没有「按单号取单」的方法（契约 1.1.0 已给
  `OrderQuery.orderNo`，待 S08 实现后本模块可切到精确查询）。S13 本来就要先经
  `OrderPort.today` / `getRecent` 取到原单再做归属校验，把同一个 DTO 传进来即可；
- **入参为什么复用 `OrderLineCmd`**：退货单与普通单同构（型号 + 批号 + 单位 + 数量），不必新造入参 DTO；
- 每个方法都有带 `TxContext` 的重载：S13 可把「校验 + 建退货单（S08）」放进同一个 db-core 事务；
  便捷重载内部走 `TxRunner.run {}`（只读事务，不改任何数据）；
- **建退货单的约定**：把 `ReturnCheckResultDto.orderNo` 原样填进 `CreateOrderCmd.sourceOrderNo`（1.1.0 字段），
  S08 才能把退货单关联回原单；
- 出参一律 DTO，数量是**服务端产出的规范十进制字符串**（去尾零），客户端只展示、零计算（铁律 2 / 5）。

## 可退数量口径

```
批次维度 = (型号 id, 批号比较键, 单位)          批号比较键 = BatchTagRules.comparisonKey(批号)
订购数量 = Σ 原单明细里该维度的数量（同型号同批号的多行会合并）
已退数量 = Σ 属于本原单的有效退货单（采购退货 / 直送退货，未作废）里该维度的数量
可退数量 = 订购数量 − 已退数量                  （≤ 0 的批次不出现在结果里）
```

**退货单归属本原单的判定**（1.1.0 的 `source_order_no`）：

```
退货单.sourceOrderNo 有值 → 必须等于原单号（trim 后精确比较）
退货单.sourceOrderNo 为空 → 默认按「客户 + 批次」兼容归集
                            （requireSourceOrderLink = true 时一律不认，严格模式）
```

- 单位参与匹配：同型号同批号但按吨 / 按袋是两个量纲，不能混算；
- 批号比较与 trim 走 shared 的 `BatchTagRules`，批号写法回显原单快照；
- 已作废的退货单默认**不占额度**（`countVoidReturnOrders = false`），需要占额度时改策略即可；
- 退货单本身不可再退；已作废的原单可退量为 0（`availableBatches` 返回空列表，不抛异常）。

## 批次校验顺序

```
原单不存在（OrderPort.getReceivable → E2001）        → E3001
原单已作废（1.1.0 专用码）                            → E3004
单据类型不可退（退货单不能再退）                       → E3002
明细为空 / 型号 id 非法 / 数量格式非法                 → E422
明细行数超过策略上限                                  → E413
批次不在原单明细上（1.1.0 专用码）                     → E3005
批次在原单上但已退满（可退量为 0）                      → E3002
同批次多行累加后超过可退量                             → E3003（先合并再比较，不能拆行绕过）
通过 → 逐行回显 requested / returnable / remaining
```

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 原订单不存在 | `E3001` | 404 | 不写库 |
| 单据类型不可退 / 批次在原单上但已退满 | `E3002` | 422 | 不写库 |
| 可退数量不足（含多行累加超额） | `E3003` | 422 | 不写库 |
| 原单已作废 | `E3004` | 409 | 1.1.0 新增专用码，不写库 |
| 退货批次与原单不符 | `E3005` | 422 | 1.1.0 新增专用码，不写库 |
| 明细行数超过策略上限 | `E413` | 413 | 1.1.0 通用码（原先借 E503） |
| 订单号 / 客户 id / 数量格式 / 小数位非法 | `E422` | 422 | 不写库 |
| 退货单翻页超过策略上限 | `E413` | 413 | 数据异常保护，避免无限翻页 |

`E403` 不会从本模块抛出：权限只在 S13 判断（铁律 7）。

## 只读与硬边界

- 生产源码里**没有任何 SQL**（`ReturnCheckBoundaryTest` 扫描 `SELECT/INSERT/UPDATE/DELETE/CREATE`）
  与 `DriverManager` / `Connection`；本模块不拥有任何表，也不贡献迁移（db-core 的 410–419 段位保留未用）；
- 生产源码里**没有 `@Serializable` / `@SerialName`**：线格式只在 shared（1.1.0 迁移动作）；
- 对 `OrderPort` 只调 `getRecent` / `getReceivable`（源码扫描断言），绝不调 `create` / `update` / `nextNoteNo`；
  测试里的假 Port 对写方法与取号方法直接抛异常；
- 只 import `shared` / `db` / `order`：一旦有人加了 S04 / S05 / S06 / S07 的依赖，边界测试即失败；
- 数量 / 批号的 trim、大小写、小数位判断不在本模块复制（边界测试扫描 `lowercase()` 与 `batchTag.trim()`）。

## 事务与并发

- 读取全部在调用方事务内完成（带 `tx` 重载）；便捷重载自开一个只读事务，不存在非事务读取（铁律 4）；
- 本模块不写库，因此**不产生锁竞争**；「校验通过 → S08 建退货单」之间的并发窗口由 S08 的建单事务
  与 `client_request_id` 幂等键兜住（同一退货请求不会被建两张单）；
- 若要求「校验与建单严格互斥」，把 `validate` 与 `order.create` 放进同一个 `TxRunner.run {}` 即可。

## 组合根装配（服务启动一次）

```kotlin
val returnCheck: ReturnCheckPort = ReturnCheckService(
    db.txRunner,
    order,                                  // S08 的 OrderPort 实例
    ReturnCheckPolicy(),                    // 1.1.0 默认口径；要换规则就换这个实例
)
```

S13 路由（示意，权限在网关）：

```
GET  /orders/{orderNo}/returnable-batches  → returnCheck.availableBatches(原单 DTO)
POST /orders/{orderNo}/return-check        → returnCheck.validate(原单 DTO, 退货明细)
POST /orders  (orderType = 退货)           → 先 validate，再把 result.orderNo 填进 sourceOrderNo 建单
```

## 验收对照

本机用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:return-check:test`：**49 个用例全部通过（0 失败 / 0 跳过）**
（`ReturnCheckPortTest` 16 + `ReturnRulesTest` 22 + `ReturnCheckBoundaryTest` 11）。

- [x] **只通过 OrderPort 读**：`available_batches_subtract_returns_of_the_same_customer_and_batch`、
      `returns_of_another_customer_do_not_consume_the_returnable_quantity`、
      `return_orders_are_read_page_by_page_until_the_total_is_reached`、
      `only_the_order_port_is_used_and_only_its_read_methods`、
      `the_module_depends_on_no_module_other_than_shared_db_core_and_order`；
      `validate_and_availability_never_create_or_change_an_order`（校验前后订单总数与版本不变）。
- [x] **规则可独立升级**：`voided_return_orders_only_consume_quantity_when_the_policy_says_so`、
      `returnable_order_types_are_policy_driven`、`strict_mode_ignores_returns_without_a_source_order_link`；
      策略参数越界在构造期 `require` 失败。
- [x] **1.1.0 契约接入**：`quantities_and_batch_tags_come_from_the_shared_caliber`（数量调 `Quantity.ofPlain`、
      批号调 `BatchTagRules`，模块内无 trim / lowercase 复制）、`the_module_defines_no_wire_dto_of_its_own`
      （线格式只在 shared）、`the_port_only_returns_dtos_defined_in_shared`、
      `the_error_codes_used_by_this_module_live_in_the_contract_bands`（E3xxx + E413）。
- [x] **可退数量正确**：聚合重复批次行、扣减已退、已退满消失、型号 / 单位不同不互扣、
      边界值（正好退完）通过并返回 `remaining = 0`。
- [x] **退货单归属精确**：`a_return_linked_to_this_order_is_counted_even_in_strict_mode`、
      `returns_linked_to_another_order_are_not_counted`（纯规则）、
      `returns_linked_to_another_order_do_not_consume_the_returnable_quantity`、
      `returns_linked_to_this_order_are_subtracted_even_when_strict`（假 Port）。
- [x] **批次校验独立**：作废原单 `E3004`（规则 + 集成各 1 例）、批次不在原单上 `E3005`（各 1 例）、
      已退满 `E3002`、超额 `E3003`、拆行绕过被合并拦下、坏数量 `E422`、行数超限 / 翻页失控 `E413`。
- [x] **硬边界（铁律 2 / 3 / 4 / 5 / 7 / 8 / 9 / 10）**：`ReturnCheckBoundaryTest` 11 例。

## 集成前置

1. 根 `settings.gradle.kts` 需追加 `include(":fpk-server:return-check")`。本模块写入范围只有
   `fpk-server/return-check/`，根工程由组合根统一维护；验证时用模块内临时根工程
   （`fpk-server/return-check/build/verify`，构建产物、不提交）跑通 `:fpk-server:return-check:test`。
2. 根工程需已有 `:shared`（≥ 1.1.0）、`:fpk-server:db-core`、`:fpk-server:order`；本模块
   `build.gradle.kts` 已按 `api(project(...))` 声明，且**只**声明这三个（不再需要 serialization 编译插件）。
3. 组合根需先建 S08 的 `OrderPort`，再建 `ReturnCheckService`（本模块依赖 S08 的实例，不反向依赖）。
4. S13 必须在调用前完成登录与权限校验，并在建退货单前调 `validate`；本模块不做权限判断。

## 待确认的落地假设（v1.1.0 基线）

1. **S08 尚未回填 `source_order_no`**（契约 1.1.0 已加字段，迁移与读写属 S08 待办）：因此
   `OrderDto.sourceOrderNo` 目前恒为 `null`，本模块默认按「客户 + 批次」兼容归集；
   等 S08 落库后，把 `ReturnCheckPolicy.requireSourceOrderLink` 置 `true` 即可切到严格归属，代码不改。
2. **`OrderQuery.orderNo` 已进契约但 S08 未实现**：本模块暂不敢用它（S08 会静默忽略该过滤条件，
   导致「按单号查回来的是整页订单」）。S08 实现后，可把 `availableBatches` / `validate` 的入参换成
   `orderNo: String` 由本模块自己精确读单，Port 只增不改。
3. **「原单不存在」仍用 `getReceivable` 探测**：S08 的 Port 没有按单号取单的方法，这里借它的
   `E2001` 判定存在性并翻译成 `E3001`；返回的应收金额不参与任何计算。
4. **可退单据类型默认只有采购 / 直送**：退货单不可再退、作废单不可退（`E3004`）。若业务允许「退退」，
   改 `ReturnCheckPolicy.returnableOrderTypes` 即可。
5. **不落台账**：可退数量每次由 S08 的单据实时派生，不做冗余表；db-core 给 S09 预留的 410–419 段位未使用。
6. **翻页上限 `maxReturnOrderPages = 50`（10 000 张退货单）**：超过抛 `E413` 而不是静默截断。
7. **`E413` 用于两个「数据量」场景**：明细行数超策略上限、退货单翻页超上限。二者都是「输入 / 数据量
   超过允许上限」的语义，共用 shared 的通用码，不再借用 `E503`（服务不可用）。
