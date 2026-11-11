# SHARED · shared 契约层

> contractVersion = **1.2.0**（唯一真源：`Contract.kt` 的 `CONTRACT_VERSION`；`contract.version` 为构建/CI 镜像，由测试强制核对）

## 职责

两端唯一真源：DTO、枚举、错误码、单号前缀、单位与装箱规格。
本模块**只有数据与线格式定义**，不实现任何业务逻辑；服务端与客户端都只引用这一份，
禁止两端各写一套字符串（硬性约束 R9）。

## 目录（本模块实现的 Port）

| 目录 | 内容 | 文件 |
| --- | --- | --- |
| `enums/` | 跨端枚举与线值 | `DocType`、`OrderType`、`OrderStatus`、`SettleDirection`、`SettleStatus`、`DiffType`、`Role` |
| `serial/` | 单号前缀表（PS/PT/ZS/ZT/BZ/JS） | `SerialType` |
| `errors/` | 全部错误码 + 分类 + 映射 | `ErrorCategory`、`ErrorCode`、`ErrorResponse`（含 `toResponse` / `toErrorCode`） |
| `units/` | 计价单位、计量维度、装箱规格结构 | `PricingUnit`、`UnitDimension`、`PackingSpec` |
| `dto/` | 跨端 DTO 与入参 Cmd | `Money`、`Quantity`、`BatchTag`、`Common`、`OrderDto`、`ReturnCheckDto`、`CustomerDto`、`CategoryDto`、`PriceDto`、`SettleDto`、`NoteDto`、`AccountDto` |
| 根 | 契约版本、共享 JSON、通用请求头 | `Contract.kt`、`contract.version`、`build.gradle.kts` |

## 唯一真源清单（两端禁止另写字符串）

- 枚举线值（JSON 值）：`@SerialName` 全部在 `enums/`；
- DTO 字段名：全部 `@SerialName` snake_case，以 DTO 定义为准；
- 单号前缀：只在 `serial/SerialType`，业务模块取号只走 `SerialType.of(docType)`；
- 错误码 / HTTP 状态 / 分类 / 默认文案：只在 `errors/ErrorCode`；
- 金额格式：十进制字符串两位小数，只能经 `Money.ofPlain` 构造（显式 `MoneySerializer` 固定线格式与解码校验），客户端只 `display()`；
- 数量口径：`Quantity.ofPlain`（十进制、> 0、最多 `WireFormat.QUANTITY_MAX_SCALE` 位小数、去尾零归一化，`1.500` → `1.5`）；现有 DTO 的 quantity 字段仍为 `String`（字段签名只增不改），S08/S09 校验统一调它；
- 批号口径：`BatchTagRules`（trim + ASCII 大小写不敏感，与 S08 SQLite `COLLATE NOCASE` 对齐），禁止各模块自写比较；
- 日期/时间 / 数量 / 批号线格式：`dto/WireFormat`；
- 请求头：`HEADER_CONTRACT_VERSION`、`HEADER_AUTHORIZATION`、`AUTH_SCHEME_BEARER`、`HEADER_IDEMPOTENCY_KEY`；
- JSON 配置：两端统一用 `ContractJson.json`（`ignoreUnknownKeys` + `encodeDefaults`），不得另行配置命名策略。

## 双端接入

根工程（由地基阶段维护，现状：`settings.gradle.kts` 已 `include(":shared")`；根 `build.gradle.kts` 已声明
Kotlin 2.0.21 / serialization 插件版本）：

```kotlin
// settings.gradle.kts
include(":shared", ":fpk-server", ":android-app")

// 根 build.gradle.kts：插件版本统一在这里声明
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}
```

两端引用：`api(project(":shared"))`（服务端与 Android 同用 JVM 字节码）。
客户端使用：DTO 直接反序列化 + `Money.display()` 展示；服务端使用：
`Money.ofPlain(...)` 构造 → `ContractJson.json.encodeToString(...)` / `ErrorCode.toResponse(...)`。

> **当前引用状态**：服务端各模块已通过 `import com.jiancai.shared.*` 引用本契约（76 个文件）；
> `android-app` 尚未创建（客户端属阶段 4，C00 起接入），客户端侧「引用同一份契约」待 C00 完成后核验。
> 本机 JDK 17 位于 `.tools/jdk-17.0.21+9`，`:shared:test` / `:shared:build` 已真实执行通过。

## contractVersion 与变更规则

- 只追加（新可空字段、新枚举项、新错误码）→ **次版本 +1**（1.0.0 → 1.1.0）；
- 删除字段、改字段名 / 枚举线值 / 金额格式、改已有语义 → **主版本 +1**（并保留旧字段双通）；
- 已有字段签名只增不改，废弃用 `@Deprecated` + 新旧双通；
- 升级步骤：改 `Contract.kt` 的 `CONTRACT_VERSION` → 同步 `contract.version` → 在下方变更记录追加一行 → 跑 `:shared:test`。
  测试 `contract_version_matches_mirror_file` 会拦住两处不一致。

## 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| 1.0.0 | 2026-11-10 | 初始契约：5 类枚举 + `SettleStatus` / `Role`（按 DTO 需要补充，仅增）、单号前缀表、错误码基线、单位与装箱规格、跨端 DTO 与入参 Cmd |
| 1.2.0 | 2026-11-11 | 仅增不改：新增 `AuditEntryDto`（审计查询读模型，S14 产出 / S13 `GET /audit` 转发）；配合 S08 追加 `OrderPort.get(orderNo)` 的只读单号查询（Port 接口只增，不改既有签名） |
| 1.1.0 | 2026-11-11 | 仅增不改：S09 `ReturnCheck` 三个出参 DTO 平移到 `dto/`；`OrderDto` / `CreateOrderCmd` 增加可空 `source_order_no`；`OrderQuery` 增加可空 `order_no` 精确过滤；新增 `Quantity` 值对象与 `BatchTagRules` 批号口径，`WireFormat` 增加数量 / 批号常量；错误码新增 `E413`（数据量超限）、`E3004`（原单已作废不可退）、`E3005`（退货批次与原单不符） |

## 依赖与边界

- 依赖：**无**（不依赖 db-core / 任何业务模块）。
- 禁止：业务逻辑、SQL、事务、权限判断、单号生成算法（序号分配属 S07）、打印排版（属 S12）。
- 本模块被两端引用，但只出 DTO/枚举/错误码；任何新业务字段先在这里定义，再改 Port。

## 待确认的落地假设（v1.0.0 基线，如有异议按契约流程升版本）

1. 包名 `com.jiancai.shared`（服务端/客户端后续模块沿用此前缀）。
2. 序列化选型为 kotlinx.serialization（服务端与 Android 通用，最低 1.4.0），版本可由根工程
   `gradle.properties` 的 `kotlinxSerializationVersion` 覆盖。金额使用显式 `MoneySerializer`
   （不可变类型、主构造器私有，只经 `Money.ofPlain` 构造），线格式与校验不依赖库版本的隐式行为。
3. `OrderStatus` 采用 `CREATED / PARTIALLY_SETTLED / SETTLED / VOID`；结算状态用 `SettleStatus`，
   角色用 `Role`——原方案只点名 5 个枚举，这 2 个是 DTO 必需的补充（仅增不改）。
4. 金额与数量在线格式上一律字符串：金额只能经 `Money.ofPlain` 构造（两位小数，解码时同样校验归一化）；
   数量最多 3 位小数、由 S08 校验。分页缺省值统一用 `DEFAULT_PAGE` / `DEFAULT_PAGE_SIZE`。
5. 日期 `yyyy-MM-dd`、时间戳 `yyyy-MM-dd'T'HH:mm:ssXXX`；业务日期以服务端为准，客户端只展示。
6. 错误码按模块段位分配（E1xxx pricing、E2xxx order、E3xxx return-check、E4xxx note、E5xxx settlement、
   E6xxx account/auth、E7xxx print、E9xxx serial），新增只追加。
7. 1.1.0 的 `source_order_no` / `order_no` / `ReturnCheck*Dto` / `Quantity` / `BatchTagRules` / 新错误码
   都是**只增**，旧字段签名未动；各业务模块的接入迁移清单见下节。

## 1.1.0 业务模块迁移待办（shared 已就绪，业务模块需跟进；不属本模块写范围）

| 模块 | 待办 |
| --- | --- |
| S09 return-check | 删除本地 `fpk-server/return-check/.../ReturnCheckDto.kt`，改 import `com.jiancai.shared.dto.ReturnableBatchDto / ReturnCheckResultDto / ReturnCheckLineDto`（线格式不变）；作废单 / 批次不符改抛 `E3004` / `E3005`；翻页超限从 `E503` 改用 `E413`；数量 / 批号校验改用 `Quantity.ofPlain` / `BatchTagRules`；测试 `FakeOrderPort` 补 S08 新增的 `nextNoteNo` 桩（当前 `compileTestKotlin` 失败，与 shared 无关） |
| S08 order | 持久化并回填 `source_order_no`（含迁移）；`OrderQuery.orderNo` 实现按单号精确查询；退货单创建时校验原单归属（`E3005`）与作废（`E3004`）；`normalizeQuantity` / 批号比较改用 `Quantity.ofPlain` / `BatchTagRules` |
| S10 / S11 / S13 | 原单读取改用 `OrderQuery.orderNo` 精确查询，不再靠 `getReceivable` 探测存在性；错误码映射表按新增码补齐 |
| 客户端 C06/C07/C09 | 直接反序列化 `ReturnCheck*Dto` 展示，不自行计算数量 |

## 验收对照

- [x] 双端引用同一份契约，无魔法字符串：枚举/DTO/错误码/前缀/单位/格式/请求头均集中在本目录，两端只 import。
  - 服务端实测：`fpk-server/**/src/main` 共 76 个文件引用 `com.jiancai.shared.*`；未发现重复定义的
    `OrderType/DocType/ErrorCode/...` 枚举，也没有前缀 / 错误码 / 请求头 / 日期格式的重复字面量（2026-11-11 扫描）。
  - 客户端：`android-app` 尚未创建（阶段 4），接入 C00 后按同一份契约引用。
- [x] contractVersion 随变更递增：版本在 `Contract.kt` + `contract.version`，规则与记录见上，测试强制核对。
  - 实测：`:shared:test` → **18 tests / 0 failures**（2026-11-11），`:shared:build` 通过；
    下游 `:fpk-server:order:test` 在 1.1.0 契约下同样通过（源码兼容）。
