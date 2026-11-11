# S04 · customer

> contractVersion = **1.0.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

客户档案：建档、按电话查询、按主键读取，并为下单提供**当前地址**（地址快照由 S08 在创建订单时复制保存）。

写入范围：`fpk-server/customer/**`。本模块自持 `customer` 表，SQL 只出现在
`CustomerRepository`（铁律 10），不访问任何其它模块的表（铁律 3）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `CustomerPort.kt` | **Port**（唯一对外入口）：`findByPhone` / `get` / `create`，各带 tx 与便捷两种重载 |
| `CustomerService.kt` | 实现：入参裁剪、电话格式与唯一性、`E404` / `E422`、幂等建档、事务包裹 |
| `CustomerRepository.kt` | `customer` 表的唯一 SQL 出口 + 内部持久化类型 `StoredCustomer`（转为 DTO 后出模块） |
| `CustomerMigrations.kt` | 迁移 300：建 `customer` 表（电话唯一且只允许纯数字、空白 CHECK） |
| `CustomerException.kt` | 失败异常，携带共享错误码（`E404` / `E422`） |

测试：`CustomerPortTest`（建档 / 查询 / 唯一性 / 事务）、`CustomerContractTest`（线格式）、
`CustomerBoundaryTest`（签名级硬边界 + SQL 位置 + 表归属）、`TestSupport`（内存库夹具）。

## 本模块实现的 Port

```kotlin
interface CustomerPort {
    fun findByPhone(tx: TxContext, phone: String): CustomerDto?   // 未命中 → null
    fun findByPhone(phone: String): CustomerDto?
    fun get(tx: TxContext, id: Long): CustomerDto                 // 不存在 → E404
    fun get(id: Long): CustomerDto
    fun create(tx: TxContext, cmd: CreateCustomerCmd): CustomerDto
    fun create(cmd: CreateCustomerCmd): CustomerDto
}
```

- 入参 / 出参全部是 `shared` 的 DTO（`CreateCustomerCmd` / `CustomerDto`），不暴露持久化结构（铁律 2）；
  `CustomerDto` 不含任何持久化专用列（`CustomerBoundaryTest` 守住）。
- 读、写都提供带 `TxContext` 的重载：调用方可以把「客户建档 + 订单写入」放进同一个 db-core 事务；
  便捷重载内部仍走 `TxRunner.run {}`，不存在非事务写入（铁律 4）。
- 错误自带共享错误码，S13 直接按 `ErrorCode` 映射响应，本模块不另写映射表（铁律 9）。

## 电话口径（客户的唯一 id，唯一格式）

`phone` 是客户的**唯一 id（业务主键）**，只有一种写法：**纯数字，无空格、无划线**。

- 服务端不做任何归一（不 trim、不去划线）：含空格 / 划线的值一律 `E422`，
  从入口保证同一号码不会在库里出现两种形态；
- 建档 / 查询都按电话原值精确匹配；电话已存在时**直接返回已有客户（幂等）**，
  不新建第二条、不覆盖已有名称 / 地址（库层 `phone UNIQUE` 是并发兜底）；
- 空电话 → `E422`；查询空电话同样 `E422`，避免把客户端 bug 当成「查无此人」；
- 不做号码段 / 长度校验：纯数字即可，手机与固话共用同一个字段。

## 地址快照：本模块只提供当前值

`CustomerDto.address` 是客户**当前**地址；下单时的地址快照归 S08 所有：

1. S08 调 `CustomerPort.get(tx, customerId)` 或 `findByPhone(tx, phone)` 取当前客户；
2. S08 把 `name / phone / address` 复制进订单（`OrderDto.customerName / customerPhone / customerAddress`）；
3. 客户资料此后再变化，不影响历史订单——本模块**不存快照、不回填订单**。

## 硬边界：不判权限 / 不碰别模块

- **权限唯一拦截点是 S13 api-gateway（铁律 7）**：`CustomerPort` 的参数里没有 token、操作者或角色，
  本模块也没有任何权限分支；谁能建档由 S13 决定。
- **不访问其它模块的表**：`customer` 表归本模块；需要客户数据的模块（S06 pricing、S08 order 等）
  只能调本 Port，不能写 `SELECT ... FROM customer`。
- **不生成单号、不碰金额**：客户档案没有任何单号 / 金额字段（铁律 5 / 8）。
- **不接收客户端时间**：`created_at` 由服务端时钟按 `shared` 的 `WireFormat.TIMESTAMP` 产出
  （如 `2026-11-11T10:00:00+08:00`），DTO 里只回传不接收。

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 名称 / 电话为空，或电话含空格 / 划线 / 其它非数字字符 | `E422` | 422 | 不写库；查询同样拒绝 |
| 按电话查询但电话为空 / 格式非法 | `E422` | 422 | 查询入参非法 |
| `get` 的客户不存在 | `E404` | 404 | 并发删除同样按此报 |
| 建档时电话已存在 | 不抛错 | 200 | 幂等返回已有客户，不覆盖已有资料 |
| 按电话查询未命中 | 不抛错 | 200 | 返回 null，由客户端进入「新建档案」流程 |

共享错误码目前没有客户专属段位（`shared/errors/ErrorCode.kt` 只到 E1xxx–E7xxx、E9xxx），
本模块因此复用通用码；若要客户专属码，必须先按「只增不改」在 `shared` 追加，不能在本模块自造。

## 事务与并发

- 所有写操作经 `TxRunner.run {}`（含便捷重载）；带 tx 重载直接复用调用方事务，
  外层回滚会同时丢弃客户建档（`CustomerPortTest.creating_in_an_outer_transaction_rolls_back_together_with_the_caller`）。
- 单连接 + `TxRunner` 锁串行化写入；`phone` 唯一约束是并发重复建档的最终兜底。
- 唯一约束冲突在 Service 内回查已有客户并幂等返回，不会把裸 `SQLException` 漏给网关。
## 迁移

- 版本 **300**（db-core 把 300–399 分配给 S04 / S05 / S06；本模块用子段 300–309，
  310–319 留给 S05、320–329 留给 S06，本模块只占 300）。
- 表 `customer` 的库层约束：
  - `name` 非空白；
  - `phone` 唯一（客户唯一 id），且只允许纯数字：`length(phone) > 0 AND phone NOT GLOB '*[^0-9]*'`；
  - `address` 可空，但非空时必须非空白；
  - `created_at` 非空，`updated_at` 可空（v1.0.0 没有修改入口，建档时为 null）。

组合根把 `CustomerMigrations.all` 与其它模块迁移合并后交给 `DbCore.open(..., migrations)` 并 `migrate()`。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"),
                     CustomerMigrations.all + otherModules)
db.migrator.migrate()

val customer: CustomerPort = CustomerService(db.txRunner)   // clock 默认 systemDefaultZone

// S13 路由（示意）：
//   GET  /customers/by-phone?phone=...  → customer.findByPhone(phone)
//   GET  /customers/{id}                → customer.get(id)
//   POST /customers                     → customer.create(cmd)
// 建档 / 查询的权限由 S13 统一拦截，本模块不判断。
```

## 验收对照

本机用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:customer:test`：**20 个用例全部通过（0 失败 / 0 跳过）**
（`CustomerPortTest` 12 + `CustomerContractTest` 3 + `CustomerBoundaryTest` 5）。
根 `settings.gradle.kts` 尚未 include 本模块（写入范围限制），验证时用临时根工程把
`:fpk-server:customer` 指向本目录后执行。

- [x] contract test 通过：`CustomerContractTest` 3 例——`CustomerDto` 往返保持 snake_case
      （`created_at` / `updated_at` / `address:null`），`CreateCustomerCmd` 从共享契约解码并忽略未知字段，
      可选 `address` 缺省为 null。
- [x] 按电话查询正确：`find_by_phone_is_exact_and_rejects_non_canonical_input`
      （原值精确命中；带空格 / 划线的写法不是合法电话，`E422`）、
      `find_by_phone_returns_only_the_matching_customer`（两条档案互不串号）、
      `find_by_phone_returns_null_for_an_unknown_number`（未命中返回 null）、
      `blank_phone_lookup_is_rejected_with_e422`。
- [x] 建档：`create_returns_a_server_generated_dto_and_trims_inputs`
      （裁剪名称 / 电话 / 地址，id 与 `created_at` 由服务端产出，`updated_at` 为 null，`get` 与建档一致）。
- [x] 唯一电话：`creating_an_existing_phone_returns_the_existing_customer_and_writes_nothing`
      （同一电话再次建档返回已有客户、仍只有一条、资料不被覆盖）、
      `phone_is_unique_and_digit_only_at_the_database_layer`（库层唯一约束 + 纯数字 CHECK 双兜底）。
- [x] 入参校验：`blank_name_phone_or_address_handling`（空名称 / 空电话 / `"---"` 均 `E422` 且不写库；
      空白地址归一为 null）。
- [x] 不存在：`unknown_customer_id_is_rejected_with_e404`。
- [x] 事务（铁律 4）：`every_convenience_overload_opens_exactly_one_transaction`、
      `tx_overloads_reuse_the_callers_transaction_and_never_open_their_own`、
      `creating_in_an_outer_transaction_rolls_back_together_with_the_caller`。
- [x] 硬边界（铁律 2 / 3 / 7 / 10）：`CustomerBoundaryTest` 5 例——Port 无身份入参、只返回 DTO、
      DTO 不暴露持久化列、DML 只在 `CustomerRepository`、库中只有 `customer` 一张业务表。

## 集成前置

1. 根 `settings.gradle.kts` 需追加：
   ```kotlin
   include(":fpk-server:customer")
   ```
   （本次写入范围只有 `fpk-server/customer/**`，未改根工程；验证时用临时根工程指向本模块。）
2. 根 `build.gradle.kts` 已统一声明 Kotlin 2.0.21 与 `jvmToolchain(17)`，本模块无需额外插件。
3. S13 路由需在进入本 Port 前完成登录 / 权限拦截；S08 下单时调用本 Port 取客户当前地址并落快照。

## 待确认的落地假设（v1.0.0 基线）

1. 包名 `com.jiancai.customer`（沿用 `com.jiancai` 前缀），表名 `customer`。
2. **电话唯一**：同一号码只建一条档案；建档时电话已存在则幂等返回已有客户（不新建、不覆盖资料）。
   若业务上允许一个号码对应多个客户，需要去掉 `phone` 的 UNIQUE 并让 `findByPhone` 返回
   `List<CustomerDto>`——这是契约变更（升版本），不在本模块擅自扩展。
3. **不做修改 / 删除客户**：任务卡只要求 `findByPhone / create / get`；因此 `updated_at` 建为可空、
   当前恒为 null，未来新增 `update` 时复用同列并追加迁移（只增不改）。
4. 电话只接受纯数字（无空格、无划线），服务端不 trim、不做任何归一；号码段 / 长度不校验。
   `created_at` 用 `shared` 的 `WireFormat.TIMESTAMP`（与服务端其它模块的线格式时间口径一致）。
5. 共享错误码没有客户专属段位，本模块复用 `E422 / E404`；如需客户专属码，先在 `shared` 追加。
6. 300–399 段位内再分子段（S04 = 300–309、S05 = 310–319、S06 = 320–329）是本模块的落地假设，
   需 S05 / S06 确认；本模块只占 300，不影响已落库版本号。
