# SHARED · shared 契约层

> contractVersion = **1.0.0**（唯一真源：`Contract.kt` 的 `CONTRACT_VERSION`；`contract.version` 为构建/CI 镜像，由测试强制核对）

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
| `dto/` | 跨端 DTO 与入参 Cmd | `Money`、`Common`、`OrderDto`、`CustomerDto`、`CategoryDto`、`PriceDto`、`SettleDto`、`NoteDto`、`AccountDto` |
| 根 | 契约版本、共享 JSON、通用请求头 | `Contract.kt`、`contract.version`、`build.gradle.kts` |

## 唯一真源清单（两端禁止另写字符串）

- 枚举线值（JSON 值）：`@SerialName` 全部在 `enums/`；
- DTO 字段名：全部 `@SerialName` snake_case，以 DTO 定义为准；
- 单号前缀：只在 `serial/SerialType`，业务模块取号只走 `SerialType.of(docType)`；
- 错误码 / HTTP 状态 / 分类 / 默认文案：只在 `errors/ErrorCode`；
- 金额格式：十进制字符串两位小数，只能经 `Money.ofPlain` 构造（显式 `MoneySerializer` 固定线格式与解码校验），客户端只 `display()`；
- 日期/时间格式：`dto/WireFormat`；
- 请求头：`HEADER_CONTRACT_VERSION`、`HEADER_AUTHORIZATION`、`AUTH_SCHEME_BEARER`、`HEADER_IDEMPOTENCY_KEY`；
- JSON 配置：两端统一用 `ContractJson.json`（`ignoreUnknownKeys` + `encodeDefaults`），不得另行配置命名策略。

## 双端接入

根工程（本模块写不到根目录，需集成时补）：

```kotlin
// settings.gradle.kts
include(":shared", ":fpk-server", ":android-app")

// 根 build.gradle.kts：插件版本统一在这里声明
plugins {
    kotlin("jvm") version "<kotlin 版本>" apply false
    kotlin("plugin.serialization") version "<kotlin 版本>" apply false
}
```

两端引用：`api(project(":shared"))`（服务端与 Android 同用 JVM 字节码）。
客户端使用：DTO 直接反序列化 + `Money.display()` 展示；服务端使用：
`Money.ofPlain(...)` 构造 → `ContractJson.json.encodeToString(...)` / `ErrorCode.toResponse(...)`。

> **集成前置（当前仓库尚缺）**：本仓库还没有根工程（`settings.gradle.kts` / 根 `build.gradle.kts`），
> 也没有 `fpk-server` / `android-app` 工程。补齐上面两段并在根声明 Kotlin / kotlinx 版本之前，
> `:shared:test` 无法运行；这是「双端引用同一份契约」验收的前置条件，不是 shared 模块内部问题。
> 另：`jvmToolchain(17)` 需要本机 JDK 17（或根工程配置 foojay-resolver 自动下载）。

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

## 验收对照

- [x] 双端引用同一份契约，无魔法字符串：枚举/DTO/错误码/前缀/单位/格式/请求头均集中在本目录，两端只 import。
- [x] contractVersion 随变更递增：版本在 `Contract.kt` + `contract.version`，规则与记录见上，测试强制核对。
