# 代码地图 · fpk-server/auth/src/test/kotlin/com/jiancai/auth

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.auth`：6 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AccountStorePortTest.kt` | `AccountStorePort` 验收：S03 account 的账号 CRUD 依赖本 Port。 |
| `AuthMigrationsTest.kt` | 迁移验收：号段归属、建表、重复迁移幂等。 |
| `AuthPortTest.kt` | 验收（S02）： |
| `PasswordHasherTest.kt` | 密码散列硬边界（S02）：自描述格式、随机盐、损坏 / 越界输入一律判为「不匹配」，不抛 500。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
| `TokenSignerTest.kt` | token 签发 / 校验硬边界（S02）：线格式、不透明性、验签、过期、时钟偏移、超长输入、密钥下限。 |
