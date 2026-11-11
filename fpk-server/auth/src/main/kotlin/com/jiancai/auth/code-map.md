# 代码地图 · fpk-server/auth/src/main/kotlin/com/jiancai/auth

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.auth`：8 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AccountStorePort.kt` | 账号存储 Port：S02 对外的**第二个** Port，供 S03 account（管理员用例层）取账号数据。 |
| `AuthException.kt` | 登录 / 校验失败（S02）。 |
| `AuthMigrations.kt` | S02 的迁移（版本号段 210–219，见 `db-core/module.md` 与 `config/module.md` 的子段约定： |
| `AuthPort.kt` | 登录 / token 校验 Port：S13 api-gateway 做鉴权、C01 只消费 token（S02 另有 [AccountStorePort] 给 S03）。 |
| `AuthRepository.kt` | `auth_account` 的唯一 SQL 出口（铁律 10）：SQL 只出现在这里，其它模块禁止读写本表。 |
| `AuthService.kt` | [AuthPort] 与 [AccountStorePort] 的唯一实现：账号密码登录 + 服务端签名 token 的签发与校验 + 账号 CRUD。 |
| `PasswordHasher.kt` | 密码哈希策略（硬边界：**禁止明文存密码**）。 |
| `TokenSigner.kt` | token 的签发与校验（HMAC-SHA256 签名，无第三方依赖）。 |
