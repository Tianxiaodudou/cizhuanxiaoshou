# S03 · account

> contractVersion = **1.0.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

账号 CRUD（**仅管理员**）：列表、查看、新增、修改（显示名 / 角色 / 启用状态 / 可选改密）、删除。

本模块是**管理员用例层**，不拥有账号表、不碰密码哈希、不写 SQL：

- 账号表 `auth_account` 归 **S02 auth** 独占（`AuthRepository` 是唯一 SQL 出口）；
- 密码只能经 S02 哈希后落库（`auth/module.md` 第 7 条），S03 只把明文**透传**给 S02，不哈希、不落库、不回传；
- 本模块只实现用例级规则：入参裁剪与空值校验、账号存在性、
  「系统至少保留一个已启用管理员」这条业务不变量。

写入范围：`fpk-server/account/**`（本模块**不拥有任何表**，因此没有迁移）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `AccountAdminPort.kt` | **Port**（本模块唯一对外入口）：`list` / `get` / `create` / `update` / `delete`，各带 tx 与便捷两种重载 |
| `AccountAdminService.kt` | 实现：入参裁剪、存在性校验、最后一个可用管理员守卫、DTO 透出、事务包裹 |
| `AccountStore.kt` | S03 需要的**账号存储能力**（由 S02 提供，本模块只声明、不实现；组合根用适配器接上） |
| `AccountAdminException.kt` | 失败异常，携带共享错误码（`E6001` / `E6003` / `E6005` / `E422`） |

测试：`AccountAdminPortTest`（用例 + 不变量 + 事务 + 密码边界）、`AccountBoundaryTest`（签名级硬边界）、`TestSupport`（内存假账号库）。

## 本模块实现的 Port

```kotlin
interface AccountAdminPort {
    fun list(tx: TxContext): List<AccountDto>            // 按 id 升序
    fun list(): List<AccountDto>
    fun get(tx: TxContext, id: Long): AccountDto          // 不存在 → E6005
    fun get(id: Long): AccountDto
    fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto
    fun create(cmd: CreateAccountCmd): AccountDto
    fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto   // password 为空 = 不改密码
    fun update(cmd: UpdateAccountCmd): AccountDto
    fun delete(tx: TxContext, id: Long)
    fun delete(id: Long)
}
```

- 入参 / 出参全部是 `shared` 的 DTO（`CreateAccountCmd` / `UpdateAccountCmd` / `AccountDto`），
  不暴露持久化结构（铁律 2）；`AccountDto` 无密码字段（`AccountBoundaryTest` 守住）。
- 所有写操作都提供带 `TxContext` 的重载，供调用方把「账号变更 + 其它写入」放进同一个 db-core 事务；
  便捷重载内部仍走 `TxRunner.run {}`，不存在非事务写入（铁律 4）。
- 错误一律自带共享错误码，S13 直接按 `ErrorCode` 映射响应，本模块不另写映射表。

## 硬边界：禁止普通用户调用 / 禁止模块内自判权限

**权限唯一拦截点是 S13 api-gateway（铁律 7）**，本模块在签名层面就无法判权限：

- `AccountAdminPort` 与 `AccountStore` 的**任何参数都不是调用者身份**（没有 token、没有操作者 `AccountDto`、
  没有 `Role`），`AccountBoundaryTest.the_admin_port_never_receives_the_callers_identity_or_role` 用反射守住；
- 所以「只有管理员能调账号 CRUD」必须由 S13 保证，顺序是：
  1. 取 `Authorization: Bearer <token>` → `AuthPort.verify(token)` 得到当前账号；
  2. `account.role != Role.ADMIN` → 返回 `E403`（403），**不进入本模块**；
  3. 通过后才转发到 `AccountAdminPort`，并把同一个 `TxContext` 传进来（需要同事务时）。
- **验收「非管理员被 api-gateway 拒绝」的用例属于 S13**：本仓库还没有 `fpk-server/api-gateway`，
  因此该条在本模块只能验证「不自判权限 + 不接收身份」，端到端的 403 需在 S13 补齐后按其用例执行。
  本模块不含任何角色判断代码（没有 `role == ADMIN` 的权限分支，只有「最后一个可用管理员」这条业务不变量）。
- 禁止：在客户端（C11 feature-admin）隐藏按钮就当作权限控制；在本模块加 `if (operator.role == ...)`；
  给 `AccountAdminPort` 增加 `operator` / `token` 参数来「顺便鉴权」。

## 与 S02 auth 的分工（为什么本模块没有 repository / 迁移）

依赖方向是 **S03 → S02**（`auth/module.md` 待确认项 2）：S02 自持账户表，S03 是管理员用例层。
若 S03 自己读写 `auth_account`，就会出现两处 SQL、两套密码处理，并直接违反铁律 3 / 10。

因此本模块只声明所需能力 `AccountStore`（`list` / `get` / `create` / `update` / `delete`），
真实数据由 S02 提供。S02 已按 `auth/module.md` 交付 `AccountStorePort`（`fpk-server/auth`），
两者方法签名一一对应。落地时在**组合根**（不属于任何业务模块）放一个薄适配器：

```kotlin
// S02 已提供的账号存储 Port（方法签名与 AccountStore 一一对应）
class AccountStoreAdapter(private val auth: AccountStorePort) : AccountStore {
    override fun list(tx: TxContext) = auth.list(tx)
    override fun get(tx: TxContext, id: Long) = auth.get(tx, id)
    override fun delete(tx: TxContext, id: Long) = auth.delete(tx, id)
    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto =
        try { auth.create(tx, cmd) } catch (e: AuthException) { throw AccountAdminException(e.code) }
    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto? =
        try { auth.update(tx, cmd) } catch (e: AuthException) { throw AccountAdminException(e.code) }
}

val accountAdmin: AccountAdminPort = AccountAdminService(db.txRunner, AccountStoreAdapter(authService))
```

适配器必须满足的契约写在 `AccountStore` 的 KDoc 里（同事务、重名 `E6001`、密码只经 S02 哈希、DTO 出参）。

> **适配器为什么不在本模块**：写入范围只有 `fpk-server/account/**`，而适配器要编译期引用
> `com.jiancai.auth.AccountStorePort` / `AuthException`。两侧 `module.md` 都约定
> 「S03 只声明 `AccountStore`，适配器在组合根，S03 不依赖 S02 实现类」，
> 这样 S03 与 S02 都不引入反向依赖。适配器约 25 行，只做转调 + 错误码翻译，
> 由装配方（组合根）编写；本模块的 Service / Port / 测试都无需改动。

## 错误码口径

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 用户名 / 显示名为空 | `E422` | 422 | 首尾空白先裁掉，仍为空则拒 |
| 账号不存在（get / update / delete） | `E6005` | 404 | 删除时并发已被他人删掉同样按此报 |
| 新增时用户名已存在 | `E6001` | 409 | 由 S02 的唯一约束裁决（大小写不敏感），本模块只透出 |
| 删除 / 降级 / 停用最后一个**可用管理员** | `E6003` | 409 | 见下条业务不变量 |
| 非管理员调用 | `E403` | 403 | **在 S13 拦截**，不会到达本模块 |

密码规则（长度 ≥ 8、哈希算法、成本）全部属于 S02 的密码策略，本模块不重复实现、不另写错误码；
S02 的 `E422` 经适配器翻译后原样到达网关。

## 业务不变量：至少保留一个可用管理员

「可用管理员」= `enabled == true && role == ADMIN`。以下三种操作在会让可用管理员数归零时抛 `E6003`：

1. 删除最后一个可用管理员；
2. 把最后一个可用管理员降级为 `STAFF`；
3. 停用最后一个可用管理员。

口径细节：

- 只统计**已启用**的管理员 —— 已停用的管理员本来就不能登录，删除 / 降级它不会收窄管理入口；
- 改显示名、改密码、启用一个账号都不受影响；
- 判断与写入在**同一事务**内完成（`store.get` + `store.list` + 写入），并发下由 `TxRunner` 串行化，
  不会出现「两个请求同时把彼此判为不是最后一个」；
- 本条是**业务不变量**（防止把管理入口改没），不是权限判断，因此留在本模块是合规的。

## 事务与并发

- 全部写操作都经 `TxRunner.run {}`（含便捷重载），带 tx 重载直接复用调用方事务，
  因此「账号变更 + 其它模块写入」可以原子提交（`AccountAdminPortTest.tx_overloads_reuse_the_callers_transaction`）；
- 读操作也提供带 tx 的重载，便于 S13 在同事务快照内组合多个读取；
- 单连接 + `TxRunner` 锁串行化；用户名唯一约束（S02 的 `username_key`）是并发建同名账号的最终兜底。

## 迁移

**本模块不拥有表，因此不提交任何迁移**；`config/module.md` 分配给 S01/S02/S03 的 220–229 段位保持未用，
留给 S03 将来如果真的需要自己的表（按 220、221… 递增，只增不改）。

`auth_account` 的建表在 S02 的 210 迁移里，S03 不改表、不加列。

## 组合根装配（服务启动一次）

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"),
                     AuthMigrations.all /* + 其它模块迁移 */)
db.migrator.migrate()

val auth: AuthPort = AuthService(db.txRunner, secret)

// S02 补上账号存储 Port 后：
val accountAdmin: AccountAdminPort = AccountAdminService(db.txRunner, AccountStoreAdapter(authStore))

// S13 路由：账号管理路由全部走「verify → ADMIN，否则 E403 → accountAdmin」
```

## 验收对照

本机已用 JDK 17 + Gradle 8.10.2 执行 `:fpk-server:account:test`：**18 个用例全部通过（0 失败 / 0 跳过）**
（`AccountAdminPortTest` 14 + `AccountBoundaryTest` 4）。

- [x] 管理员可 CRUD：`admin_can_create_read_list_update_and_delete_an_account`（裁空白、默认启用、改显示名、删除后 `E6005`）。
- [x] 列表稳定：`list_is_ordered_by_id_so_the_admin_screen_is_stable`。
- [x] 重名：`a_duplicate_username_is_rejected_with_e6001`（且不写入）。
- [x] 空入参：`blank_username_or_display_name_is_rejected_with_e422_and_writes_nothing`。
- [x] 不存在：`unknown_account_is_rejected_with_e6005`（get / update / delete 三处）。
- [x] 最后一个管理员：`the_last_enabled_admin_cannot_be_deleted_demoted_or_disabled`、
      `a_disabled_admin_does_not_count_as_an_available_admin`（停用的不算可用入口）、
      `an_admin_can_be_demoted_while_another_enabled_admin_remains`（还有别的管理员时允许）、
      `renaming_the_last_admin_is_still_allowed`（改名不受限）。
- [x] 普通账号删除 / 停用不受限：`a_staff_account_can_always_be_disabled_or_deleted`。
- [x] 密码边界：`account_dtos_never_carry_the_password`（出参无密码）、
      `a_blank_password_means_keep_the_current_password`（空密码 = 不改密码）。
- [x] 事务（铁律 4）：`every_convenience_overload_opens_exactly_one_transaction`、
      `tx_overloads_reuse_the_callers_transaction_and_never_open_their_own`。
- [x] 不自判权限：`AccountBoundaryTest.the_admin_port_never_receives_the_callers_identity_or_role`、
      `the_account_store_contract_never_asks_for_permission_inputs`（签名层面拿不到身份）；
      `the_admin_port_only_returns_dtos`（铁律 2）；`the_account_dto_has_no_password_field`。
- [ ] 非管理员被 api-gateway 拒绝：**属于 S13**（当前仓库还没有 S13），S03 只提供「无身份入参、无权限分支」的前置条件。

## 集成前置（根工程已就绪）

1. 根工程已配好：`settings.gradle.kts` 已 include
   `:shared`、`:fpk-server`、`:fpk-server:db-core`、`:fpk-server:config`、`:fpk-server:auth`、
   `:fpk-server:account`、`:fpk-server:serial`，插件版本与坐标在根 `build.gradle.kts` 统一声明；
   `jvmToolchain(17)` 需要 JDK 17。本机用便携 JDK / Gradle（`.tools/`，已 gitignore）从根工程
   跑通 `:fpk-server:account:test`（18/18），全仓 `test` 共 123 个用例全绿。
2. ~~S02 需补账号存储 Port~~ **已就绪**：`fpk-server/auth` 已交付 `AccountStorePort`
   （`list` / `get` / `create` / `update` / `delete`，签名与本模块 `AccountStore` 一致），
   组合根只需加薄适配器（见上节）；S03 的 Service / Port / 测试无需改动。
3. S13 路由需把 `/admin/accounts/**` 标为管理员专属，并在转发前做 `verify → ADMIN` 拦截（`E403`）。

## 待确认的落地假设（v1.0.0 基线）

1. 包名 `com.jiancai.account`（沿用 `com.jiancai` 前缀）。
2. 删除是**硬删除**（不是软删 / 停用）：S02 的 token 校验每次回库，删号后旧 token 立即 `E401`；
   需要留痕时由 S14 observability 记录，不在本模块加审计表。
3. 「最后一个管理员」按**已启用**口径判定；删除/降级已停用管理员不受限。
4. 本模块**不直接调用** `AuthPort.login / verify`：卡片里列的 S02 依赖在运行期体现为账号存储能力，
   而调用 `verify` 就等于在本模块判权限（违反铁律 7 与「禁止模块内自判权限」），因此身份判断只留在 S13。
5. S03 编译期不依赖 `:fpk-server:auth`（依赖倒置 + 避免环），适配器放组合根；若评审更希望 S03 直接依赖
   S02 的账号存储 Port，只需删除 `AccountStore.kt` 并把 Service 的构造参数换成 S02 的 Port（一处改动）。
