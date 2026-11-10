# S05 · category

> contractVersion = **1.0.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

品类 / 型号库 CRUD（**仅管理员**，权限在 S13 拦截）+ 每个型号的批号类型配置。

- 型号是订单明细、定价维度、打印换算共同引用的主数据；S08 / S06 / S12 只通过本模块的
  `CategoryPort` 取数据，禁止跨模块直读 `category` / `category_batch_tag` 表（铁律 3 / 10）；
- **返回 DTO**（铁律 2）：出参一律是共享契约的 `CategoryDto`，持久化结构不出本模块；
- **服务端产出**（铁律 5）：时间戳、批号类型的裁剪 / 去重 / 保序均由服务端完成，
  客户端只展示、零加工；
- **不生成单号**（铁律 8）：型号库没有单号，删除 / 修改也不写单据。

写入范围：`fpk-server/category/**`（本模块独占 `category` / `category_batch_tag` 两张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `CategoryPort.kt` | **Port**（本模块唯一对外入口）：`list` / `get` / `create` / `update` / `delete`，各带 tx 与便捷两种重载 |
| `CategoryService.kt` | 实现：入参裁剪与校验、批号类型规范化、装箱规格校验、唯一性兜底、DTO 出参、事务包裹 |
| `CategoryRepository.kt` | 内部：两张表的**唯一** SQL 出口（含批号类型全量替换） |
| `CategoryMigrations.kt` | 迁移：版本 310 建表（交给 db-core 的 `Migrator`） |
| `CategoryException.kt` | 失败异常，携带共享错误码 `E422` / `E404` / `E409` |

测试：`CategoryPortTest`（用例 + 校验 + 事务）、`CategoryMigrationsTest`（建表 / 幂等 / 号段）、
`CategoryBoundaryTest`（签名级硬边界）、`TestSupport`（固定时钟 + 内存库）。

## 本模块实现的 Port

```kotlin
interface CategoryPort {
    fun list(tx: TxContext, enabledOnly: Boolean = false): List<CategoryDto>   // 按品类名 + 型号升序
    fun list(enabledOnly: Boolean = false): List<CategoryDto>
    fun get(tx: TxContext, id: Long): CategoryDto                             // 不存在 → E404
    fun get(id: Long): CategoryDto
    fun create(tx: TxContext, cmd: CategoryCmd): CategoryDto                  // cmd.id 必须为空
    fun create(cmd: CategoryCmd): CategoryDto
    fun update(tx: TxContext, cmd: CategoryCmd): CategoryDto                  // cmd.id 必填，全量覆盖
    fun update(cmd: CategoryCmd): CategoryDto
    fun delete(tx: TxContext, id: Long)
    fun delete(id: Long)
}
```

- `enabledOnly = true` 给录入页下拉框（只列启用中的型号）；`false` 给管理员维护页（含已停用）。
- 所有写操作都提供带 `TxContext` 的重载，供调用方把「型号变更 + 其它写入」放进同一个 db-core 事务；
  便捷重载内部仍走 `TxRunner.run {}`，不存在非事务写入（铁律 4）。
- 错误一律自带共享错误码，S13 直接按 `ErrorCode` 映射响应，本模块不另写映射表。

## 数据模型（两张表，S05 独占）

```sql
category(id, category_name, model, unit, batch_tag_required, pack_*, enabled, created_at, updated_at)
category_batch_tag(category_id, tag, position)
```

| 列 | 口径 |
| --- | --- |
| `category_name` / `model` | 裁剪首尾空白后落库；**大小写不敏感唯一**（`COLLATE NOCASE` + `UNIQUE`） |
| `unit` | `PricingUnit` 枚举名（如 `SQUARE_METER`），线值仍由 shared 的 `@SerialName` 决定 |
| `batch_tag_required` | 0/1；为 1 时至少配置一个批号类型（否则录入页无值可选） |
| `pack_base_unit` | 装箱规格的基准单位；为 NULL 表示该型号未配置装箱规格（其余 `pack_*` 必须全空） |
| `pack_package_unit` / `pack_units_per_package` | 必须成对出现，数量 > 0 |
| `pack_weight_kg` / `pack_volume_m3` | 十进制**字符串**（保留客户端写法，不用浮点，避免精度丢失），必须非负 |
| `pack_spec_text` | 规格描述，只用于展示 |
| `enabled` | 0/1；停用的型号不出现在 `list(enabledOnly = true)` 中 |
| `created_at` / `updated_at` | 服务端产出，`WireFormat.TIMESTAMP` 口径（含时区偏移，如 `2026-11-10T10:00:00+08:00`） |

批号类型按 `(category_id, tag)` 主键去重、`position` 保序；随型号级联删除
（`ON DELETE CASCADE`，同时 Service 显式先删，不依赖 `PRAGMA foreign_keys` 开关）。

## 服务端校验与规范化（统一在这里，客户端不重复实现）

| 入参 | 规则 | 违反时 |
| --- | --- | --- |
| 品类名 / 型号 | 裁剪空白、非空、≤ 64 字符 | `E422` |
| 批号类型 | 逐项裁剪、非空白、≤ 32 字符、无控制字符、最多 20 个；**大小写不敏感去重**并保留首次写法与顺序 | `E422` |
| `batchTagRequired = true` | 至少一个批号类型 | `E422` |
| 装箱规格 | `baseUnit` 必须等于计价单位；包装单位与每包装数量成对；数量 > 0；重量 / 体积为合法非负十进制；空白视为未配置 | `E422` |
| `create` 的 `id` | 必须为空 | `E422` |
| `update` 的 `id` | 必须非空 | `E422` |
| 「品类 + 型号」 | 大小写不敏感唯一（先查重，数据库唯一约束兜底并发） | `E409` |
| get / update / delete 的目标 | 必须存在 | `E404` |

错误码：`E422` 参数校验失败、`E404` 型号不存在、`E409` 重复（`E403` 不会从本模块抛出）。

## 事务与并发

- 全部写操作经 `TxRunner.run {}`（含便捷重载），带 tx 重载直接复用调用方事务，
  因此「型号变更 + 其它模块写入」可以原子提交；
- 读操作也提供带 tx 的重载，便于 S13 在同一事务快照内组合多个读取；
- 单连接 + `TxRunner` 锁串行化；`UNIQUE (category_name, model)` 是并发建同名型号的最终兜底
  （捕获唯一冲突后转 `E409`，不会留下半条数据）；
- 批号类型与型号写在同一事务内：型号插入失败则批号类型不会写，反之亦然。

## 硬边界：禁止普通用户改型号库 / 禁止模块内自判权限

**权限唯一拦截点是 S13 api-gateway（铁律 7）**，本模块在签名层面就无法判权限：

- `CategoryPort` 与 `CategoryService` 的任何参数都不是调用者身份（没有 token、没有操作者 DTO、
  没有 `Role`），`CategoryBoundaryTest.the_category_port_never_receives_the_callers_identity_or_role`
  用反射守住；
- 「只有管理员能改型号库」由 S13 保证，顺序是：
  1. 取 `Authorization: Bearer <token>` → `AuthPort.verify(token)` 得到当前账号；
  2. `account.role != Role.ADMIN` → 返回 `E403`（403），**不进入本模块**；
  3. 通过后才转发到 `CategoryPort`，需要同事务时把同一个 `TxContext` 传进来；
- 禁止：在客户端（C11 feature-admin）隐藏按钮就当作权限控制；在本模块加
  `if (operator.role == ...)`；给 `CategoryPort` 增加 `operator` / `token` 参数来「顺便鉴权」。

验收「普通用户被拒」的端到端用例属于 S13：本仓库还没有 `fpk-server/api-gateway`，
因此该条在本模块只能验证「不自判权限 + 不接收身份」的前置条件，403 需在 S13 补齐后按其用例执行。

## 迁移（版本号段 300–399，本模块取 310）

```kotlin
DbCore.open(config, CategoryMigrations.all + otherModules)
```

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 310 | `create_category_tables` | `category`（`(category_name, model)` 唯一、装箱规格 CHECK、启用 CHECK）、`category_batch_tag`（主键 `(category_id, tag)`、`(category_id, position)` 唯一、外键级联） |

S04 用 300、S06 用 320（同段位内按模块顺延）。迁移只增不改；改表追加 311、312…
（db-core 的 checksum 漂移检测会拦住改历史迁移）。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), CategoryMigrations.all + otherModules)
db.migrator.migrate()
val category: CategoryPort = CategoryService(db.txRunner, Clock.systemDefaultZone())  // 注入给 S06 / S08 / S13

// S13 路由：/admin/categories/** 全部走「verify → ADMIN，否则 E403 → categoryAdmin」
```

## 验收对照

本机已用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:category:test`：**19 个用例全部通过（0 失败 / 0 跳过）**
（`CategoryPortTest` 15 + `CategoryMigrationsTest` 2 + `CategoryBoundaryTest` 2）。

- [x] 管理员 CRUD 通过：`admin_can_create_read_list_update_and_delete_a_category`
      （裁空白、装箱规格往返、时间戳由服务端产出、修改后 `created_at` 不变、删除后 `E404`）。
- [x] 列表稳定 / 过滤：`list_is_ordered_by_category_name_and_model`、
      `list_can_filter_to_enabled_models_for_the_entry_screen`。
- [x] 唯一性：`duplicate_category_and_model_is_rejected_with_e409_and_writes_nothing`
      （大小写不敏感重复；update 撞车不改原行）。
- [x] 入参口径：`blank_names_are_rejected_with_e422_and_write_nothing`、
      `create_requires_an_empty_id_and_update_requires_an_id`、`unknown_category_is_rejected_with_e404`。
- [x] 批号类型：`batch_tags_are_trimmed_deduped_and_fully_replaced_on_update`、
      `required_batch_tags_need_at_least_one_non_blank_value`（空白 / 控制字符 / 超长 / 超数全拒）。
- [x] 装箱规格：`invalid_packing_spec_is_rejected_with_e422_before_writing`（6 种非法组合逐一拒绝）、
      `blank_optional_packing_values_become_null_instead_of_empty_strings`。
- [x] 删除级联：`deleting_a_category_also_removes_its_batch_tags`。
- [x] 事务（铁律 4）：`every_convenience_overload_opens_exactly_one_transaction`、
      `tx_overloads_reuse_the_callers_transaction_and_never_open_their_own`、
      `a_failed_caller_transaction_rolls_back_the_category_write`。
- [x] 迁移：`category_tables_are_created_by_the_category_migration`、
      `migration_version_is_in_the_category_segment_and_idempotent`。
- [x] 普通用户被拒：**属于 S13**（当前仓库还没有 S13）；本模块提供
      `CategoryBoundaryTest`「无身份入参、只有 DTO 出参」的前置条件，`E403` 由 S13 用例验证。

## 集成前置

根 `settings.gradle.kts` 需补一行（本模块写入范围只有 `fpk-server/category/**`，未代改）：

```kotlin
include(":fpk-server:category")
```

本机实际用 `.tools/verify-category`（gitignore 的临时根工程）跑通
`:fpk-server:category:test`，因此模块自身的编译与测试不依赖根工程改动；
根工程补上 include 后即可在正式构建里执行同一任务。

## 待确认的落地假设（v1.0.0 基线）

1. 包名 `com.jiancai.category`（沿用 `com.jiancai` 前缀）。
2. 迁移版本号取 **310**（300–399 段内：S04 用 300、S06 用 320）；若集成时另有分配，
   上线前调整即可（尚未产生已落库版本）。
3. 删除是**硬删除**（批号类型一并删除）。有历史单据的型号建议用 `enabled = false` 停用：
   订单行保存了品类名 / 型号快照，删除不破坏历史展示；S06 的价格行若仍引用已删型号，
   由 S06 按自己的口径处理（本模块不跨表清理，铁律 3）。
4. 批号类型是**每个型号各自配置的可选值**（`CategoryDto.batchTags`），不是全局字典；
   录入时只能从该型号的列表里选，服务端在订单侧校验（S08 / S09）。
5. 批号类型去重按大小写不敏感（与 `tag COLLATE NOCASE` 一致）：`A` 与 `a` 视为同一个，
   保留首次出现的写法。
6. `created_at` / `updated_at` 用服务端时钟 + `WireFormat.TIMESTAMP`；`updated_at` 在新建时为
   `null`（表示从未修改），首次修改后写入。
7. 重量 / 体积以十进制字符串原样保存（不转 Double），避免精度问题；消费方（S12 打印 / S08 金额）
   自行按 `BigDecimal` 解析，换算仍只在服务端进行。
