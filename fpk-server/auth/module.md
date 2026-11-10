# S02 · auth

> contractVersion = **1.0.0**（只消费 `shared` 契约；本模块不新增跨端线格式）

## 职责

登录、token 签发与校验、账号密码。
密码只以 **PBKDF2-HMAC-SHA256 哈希**落库（库层再加一条 CHECK 兜底，禁止明文）；
token 由服务端签发并签名，客户端只保存不解释 —— 鉴权判断点唯一在服务端，禁止下沉到客户端。

写入范围：`fpk-server/auth/**`（本模块独占 `auth_account` 一张表）。

## 目录

| 文件 | 内容 |
| --- | --- |
| `AuthPort.kt` | **Port**：`login` / `verify`（各带 tx 与便捷两种重载） |
| `AccountStorePort.kt` | **Port**：`list` / `get` / `create` / `update` / `delete`（S03 account 的账号存储依赖，只增不改） |
| `AuthService.kt` | 实现两个 Port：登录校验、token 签发、回库校验、账号 CRUD、首次启动引导 |
| `AuthRepository.kt` | 内部：`auth_account` 的**唯一** SQL 出口（内部 `StoredAccount` 不出模块） |
| `PasswordHasher.kt` | 密码哈希策略：`PasswordHasher` + `Pbkdf2PasswordHasher`（JDK 自带实现，无三方依赖） |
| `TokenSigner.kt` | token 签发 / 校验（HMAC-SHA256，定长比较） |
| `AuthMigrations.kt` | 迁移：版本 210 建账号表（交给 db-core 的 `Migrator`） |
| `AuthException.kt` | 失败异常，携带共享错误码（`E401` / `E6002` / `E6004` / `E6001` / `E422`） |

测试：`AuthPortTest`（登录 / token 校验 / 防枚举 / 事务）、`AccountStorePortTest`（账号存储 Port）、
`AuthMigrationsTest`（建表与号段）、`PasswordHasherTest`（散列格式 / 随机盐 / 损坏输入 / 成本上下界）、
`TokenSignerTest`（线格式 / 验签 / 过期 / 时钟偏移 / 超长 / 密钥下限）。

## 本模块实现的 Port

```kotlin
interface AuthPort {
    fun login(tx: TxContext, cmd: LoginCmd): LoginResultDto   // 与调用方写操作同事务
    fun login(cmd: LoginCmd): LoginResultDto                  // 内部仍走 TxRunner.run
    fun verify(tx: TxContext, token: String): AccountDto      // S13 每请求一次
    fun verify(token: String): AccountDto
}
```

- 入参 / 出参全部是 `shared` 的 DTO（`LoginCmd` / `LoginResultDto` / `AccountDto`），
  不暴露 `StoredAccount` 这类持久化结构（铁律 2：返回 DTO，不返回 Entity）。
- `verify` 收**原始 token**：`Bearer ` 前缀的解析与请求头校验属于 S13，不在这里做。
- 错误一律自带共享错误码，S13 直接按 `ErrorCode` 映射响应，不另写映射表。

```kotlin
interface AccountStorePort {
    fun list(tx: TxContext): List<AccountDto>
    fun get(tx: TxContext, id: Long): AccountDto?
    fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto
    fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto?  // password 为 null / 空白 → 不改密
    fun delete(tx: TxContext, id: Long): Boolean
}
```

`AccountStorePort` 是给 S03 account 的账号存储能力（S03 只声明 `AccountStore`，适配器在组合根）：
`auth_account` 的 SQL 仍只在本模块 `AuthRepository`，密码仍只经本模块哈希；`get` / `update` / `delete`
对不存在的账号返回 null / false，由 S03 统一转 `E6005`。两者都**不自开事务**，只接受调用方的 `TxContext`。

## 登录与错误码

| 场景 | 错误码 | HTTP | 说明 |
| --- | --- | --- | --- |
| 账号不存在 / 密码错误 / 用户名或密码为空 | `E6002` | 401 | **同码同文案**，避免账号枚举 |
| 密码正确但账号已停用 | `E6004` | 403 | 错误密码时仍报 `E6002`，不泄露账号状态 |
| token 为空 / 结构不符 / 验签失败 / 过期 / 账号已删除 | `E401` | 401 | 统一「登录已失效，请重新登录」 |
| token 对应账号已停用 | `E6004` | 403 | 停用立即生效，旧 token 不再可用 |
| 首次引导时已存在账号 | `E6001` | 409 | 引导只允许空库执行一次 |
| 新建账号入参不合法（空用户名 / 空显示名 / 密码过短） | `E422` | 422 | 密码至少 8 位 |
| 账号 CRUD 时账号不存在 | — | — | `AccountStorePort` 返回 null / false，由 S03 转 `E6005` |

账号不存在时也会跑一次 PBKDF2 校验（`dummyHash`），抹平「有无此账号」的响应时间差。

## 密码存储（验收：禁止明文）

哈希串自描述算法与成本：`pbkdf2-sha256$<迭代次数>$<salt(base64)>$<派生密钥(base64)>`

- 算法 `PBKDF2WithHmacSHA256`（JDK `SecretKeyFactory`，不引入第三方依赖）；
- 默认 210_000 次迭代、16 字节随机盐（`SecureRandom`）、32 字节派生密钥；
  迭代次数写入哈希串，日后调高默认值不影响老账号校验；
- 校验用 `MessageDigest.isEqual` 定长比较，避免时序差异；
- 哈希串损坏 / 算法未知 → 判为不匹配（不抛 500）；
- 库层再兜一道：`CHECK (password_hash LIKE 'pbkdf2-%')`，绕过应用层直写明文会被 SQLite 拒绝。

## token 设计（验收：无效 token 被拒）

```
v1.<base64url(payload)>.<base64url(HMAC-SHA256(secret, "v1.<payload>"))>
payload = "<accountId>:<签发的 epoch 秒>:<过期的 epoch 秒>"
```

- **无状态签名**：token 不在库里留副本；过期时间由服务端时钟算出，客户端改不了；
- 校验顺序：结构 → 验签（定长比较）→ 过期 → 签发时间不得超前 60 秒（挡时钟回拨 / 伪造）；
- 通过验签后仍**回库核对账号**：账号被删除 → `E401`，被停用 → `E6004`。
  因此停用 / 删除账号会立刻让旧 token 失效，不需要维护撤销列表；
- 密钥由部署方提供（`AuthService` 构造参数，≥32 字节）；缺密钥 / 短密钥在构造期直接失败，
  没有默认密钥、没有硬编码密钥；
- 默认有效期 12 小时（`DEFAULT_TOKEN_TTL`，可注入），`LoginResultDto.expiresAt` 用共享
  `WireFormat.TIMESTAMP`（`yyyy-MM-dd'T'HH:mm:ssXXX`）输出，客户端只展示。

## 迁移（版本号段 210–219）

`config/module.md` 已把 200–299 分给 S01 / S02 / S03，子段约定 **S01 = 200–209、S02 = 210–219、S03 = 220–229**。

| 版本 | 名称 | 内容 |
| --- | --- | --- |
| 210 | `create_auth_account` | `auth_account`：`username_key` 唯一、`role` 只允许 ADMIN/STAFF、`enabled` 0/1、`password_hash` 必须以 `pbkdf2-` 开头 |

```sql
CREATE TABLE auth_account (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL,
    username_key  TEXT    NOT NULL UNIQUE,   -- trim + 小写，登录大小写不敏感
    display_name  TEXT    NOT NULL,
    role          TEXT    NOT NULL CHECK (role IN ('ADMIN', 'STAFF')),
    enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    password_hash TEXT    NOT NULL CHECK (password_hash LIKE 'pbkdf2-%'),
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL,
    last_login_at TEXT
)
```

`role` 列存 `Role` 枚举名（ADMIN / STAFF）；**线值**（`admin` / `staff`）仍以 `shared` 的 `@SerialName`
为唯一真源，本模块不新增角色字符串。迁移只增不改，改表追加 211、212…

## 事务与并发

- 登录是写操作（落 `last_login_at`），提供 `login(tx, cmd)` 让调用方把「登录 + 其它写入」放进
  同一个 db-core 事务；`login(cmd)` 内部也走 `TxRunner.run {}`，不存在非事务写入；
- 校验是只读，但同样给 `verify(tx, token)`，便于 S13 在同事务内组合多个读取；
- 并发：单连接 + `TxRunner` 锁串行化；`username_key` 唯一约束是「并发建同名账号」的兜底
  （撞约束时由唯一索引拒绝，不会出现两条同名账号）。

## 依赖与边界

- 依赖：`shared`（`LoginCmd` / `LoginResultDto` / `AccountDto` / `Role` / `ErrorCode` / `WireFormat`）、
  db-core（`TxRunner` / `TxContext` / `Migration` / `Migrator`）。
- 组合根（服务启动一次）：

```kotlin
val db = DbCore.open(DbConfig.file("/var/lib/jiancai/jiancai.db"), AuthMigrations.all + otherModules)
db.migrator.migrate()

// 密钥来自部署方（环境变量 / 密钥文件），禁止写进代码或配置文件仓库
val secret = System.getenv("JIANCAI_AUTH_SECRET")
    ?: error("缺少 JIANCAI_AUTH_SECRET（≥32 字节的 token 签名密钥）")
val authService = AuthService(db.txRunner, secret.toByteArray(Charsets.UTF_8))

// 只在空库的首次启动执行一次：创建第一个管理员；已有账号时抛 E6001（部署脚本捕获即可）
authService.bootstrapFirstAdmin(
    username = "admin",
    password = System.getenv("JIANCAI_ADMIN_PASSWORD") ?: error("缺少初始管理员密码"),
    displayName = "管理员",
)

// 对 S13 / 其它模块只暴露 Port：引导方法不属于 AuthPort，只有组合根能碰到 AuthService
val auth: AuthPort = authService
// S03 的 AccountStoreAdapter 用这个 Port（适配器在组合根，S03 不依赖本模块实现类）
val accountStore: AccountStorePort = authService
```

- 禁止：明文 / 可逆加密存密码；把 token 的判断、权限判断放到客户端或业务模块（权限在 S13）；
  其它模块读写 `auth_account`（要账号数据就调本 Port）；在 `AuthRepository` 之外出现该表 SQL；
  用默认 / 短密钥启动；在本模块判断角色权限（本模块只回答「你是谁」，「你能不能做」在 S13）。

## 验收对照

- [x] 登录 / 校验通过：`AuthPortTest.login_returns_a_token_and_the_account_without_the_password`
      （token + 契约版本 + 账号 DTO，且不含密码）、
      `verify_accepts_a_freshly_issued_token_and_returns_the_account`、
      `verify_is_stable_across_service_instances_with_the_same_secret`（重启后旧 token 仍可校验）。
- [x] 无效 token 被拒：`verify_rejects_a_tampered_token`、`verify_rejects_tokens_that_are_not_server_issued`
      （空串 / 结构不符 / 客户端自造）、`verify_rejects_a_token_signed_with_another_secret`（换密钥）、
      `verify_rejects_an_expired_token_but_honours_the_one_issued_before_expiry`（12 小时边界两侧）、
      `verify_rejects_a_token_whose_account_was_deleted`、`verify_rejects_a_token_once_the_account_is_disabled`。
- [x] 禁止明文存密码：`password_is_persisted_only_as_a_pbkdf2_hash`（哈希串自描述 + 不含明文）、
      `database_rejects_a_plaintext_password`（库层 CHECK 兜底）、`default_hasher_uses_the_documented_cost`；
      散列自身边界：`PasswordHasherTest.encoding_is_self_describing_and_carries_no_plaintext`、
      `every_hash_uses_a_fresh_random_salt`、`corrupt_or_unknown_encodings_are_not_matching_instead_of_throwing`
      （损坏 / 未知算法 / 成本越界一律判为不匹配，不抛 500）、
      `tampering_with_salt_or_derived_key_breaks_the_match`、`unsafe_cost_parameters_are_rejected_at_construction`、
      `hashing_a_blank_password_is_a_programming_error`。
- [x] token 自身边界：`TokenSignerTest.issued_token_round_trips_with_the_expected_layout_and_claims`、
      `token_is_opaque_url_safe_text_and_leaks_no_raw_fields`（客户端拿到的只是不透明串）、
      `tampering_with_payload_or_mac_is_rejected`、`expired_tokens_are_rejected_at_and_after_the_expiry_instant`、
      `tokens_issued_in_the_future_beyond_the_clock_skew_allowance_are_rejected`、
      `a_token_signed_with_another_secret_is_rejected`、`malformed_or_client_forged_tokens_are_rejected`、
      `version_downgrade_is_rejected_even_with_a_valid_mac`（签名覆盖方案前缀）、
      `oversized_tokens_are_rejected_before_parsing`、`short_secrets_are_rejected_at_construction_but_32_bytes_is_accepted`。
- [x] 禁止鉴权下沉到客户端：token 由服务端签名、每次校验回库核对账号状态；
      客户端拿到的只是不透明字符串（无解析入口、无自签能力）。
- [x] 错误码口径：`unknown_username_and_wrong_password_share_the_same_error`（同码同文案，防枚举）、
      `blank_credentials_are_rejected_with_the_same_login_error`、`disabled_account_cannot_log_in`。
- [x] 事务（铁律 4）：`login_joins_the_callers_transaction`（调用方回滚 → `last_login_at` 一起回滚）、
      `token_is_only_accepted_inside_a_fresh_transaction`。
- [x] 迁移：`AuthMigrationsTest.auth_account_table_is_created_by_the_auth_migration`、
      `migration_version_is_in_the_auth_segment_and_idempotent`、
      `account_table_enforces_unique_usernames_and_known_roles`。
- [x] 首次引导：`bootstrap_only_creates_the_first_admin`、`weak_or_blank_input_is_rejected_at_account_creation`。
- [x] 账号存储 Port（S03 依赖）：`AccountStorePortTest.create_list_and_get_round_trip`、
      `create_rejects_duplicate_usernames_case_insensitively`（`E6001`）、
      `create_rejects_invalid_input_with_validation_error`（`E422`）、
      `update_without_password_keeps_the_old_password` / `update_with_a_new_password_rotates_it`、
      `disabled_account_can_no_longer_log_in_and_its_token_is_rejected`、
      `delete_removes_the_account_and_revokes_its_token`、
      `update_and_delete_missing_accounts_report_absence_without_throwing`、
      `created_password_is_persisted_only_as_a_hash`、
      `writes_join_the_callers_transaction_and_roll_back_together`（铁律 4）。

## 本机验证环境

仓库仍没有根工程（`settings.gradle.kts` / 根 `build.gradle.kts`），但本机已装好工具链，
测试已实际执行并通过（**51 个用例：AuthPortTest 20 + AccountStorePortTest 11 + AuthMigrationsTest 3
+ PasswordHasherTest 7 + TokenSignerTest 10，0 失败**）：

- JDK 17：`D:\建材系统\.tools\jdk-17.0.21+9`（Temurin 17.0.21）
- Gradle 8.10.2：`D:\建材系统\.tools\gradle-8.10.2`
- 临时验证根工程（不进仓库源码，`.tools/` 已 gitignore）：`.tools/verify`，
  用 `include` + `projectDir` 指向仓库里的真实 `shared` / `db-core` / `auth` 模块。

复跑命令（PowerShell）：

```powershell
$env:JAVA_HOME="D:\建材系统\.tools\jdk-17.0.21+9"
$env:GRADLE_USER_HOME="D:\建材系统\.tools\gradle-home"
& "D:\建材系统\.tools\gradle-8.10.2\bin\gradle.bat" --no-daemon --no-watch-fs `
  --console=plain -p "D:\建材系统\.tools\verify" :fpk-server:auth:test
```

正式根工程建好后，`include(":shared", ":fpk-server", ":fpk-server:db-core", ":fpk-server:auth")`
后直接跑 `:fpk-server:auth:test` 即可（`jvmToolchain(17)` 与 `shared` / db-core 一致）。

## 待确认的落地假设（v1.0.0 基线，如有异议按契约流程调整）

1. 包名 `com.jiancai.auth`（沿用 `com.jiancai` 前缀）。
2. **已定：S02 自持 `auth_account`，不拆表、不改迁移**。依赖方向是 S02 → S00、S03 → S02；
   若 S03 拥有账号表，S02 登录就必须反向调 S03，形成环；且 `LoginResultDto.account` 需要
   displayName / role / enabled，S02 不调 S03 就只能自持这些字段。S03 定位为管理员用例层，
   账号 CRUD 通过 S02 的 `AccountStorePort`（已提供，接口只增不改）读写账号；
   S03 **不得**直接读写 `auth_account`、不得碰本模块内部类型。
3. **首次启动引导** `bootstrapFirstAdmin`：只在空库可用，用于新部署创建第一个管理员；
   它不是日常 CRUD，也不提供改密 / 重置能力（属 S03），因此不构成线上后门。
4. **token 无状态**：不做服务端撤销列表，靠「每次校验回库核对账号状态 + 短有效期」保证失效；
   若以后要求「立即踢人」之外的强制撤销（如改密后旧 token 全失效），追加 `token_epoch` 列与迁移即可。
5. 用户名大小写不敏感（`username_key = trim + 小写`），显示用原始 `username`；密码最小长度 8 位。
6. 时间戳统一 `WireFormat.TIMESTAMP`（服务端时区输出），客户端只展示、不参与过期判断。
7. `PasswordHasher` **仅 S02 内部使用**（测试注入低迭代次数只是为了跑得快，生产默认 210_000 次）；
   密码哈希只能经 S02 的 Port 完成，S03 不直接依赖 `PasswordHasher`，也不得自行哈希密码。
