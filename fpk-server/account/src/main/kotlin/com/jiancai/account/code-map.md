# 代码地图 · fpk-server/account/src/main/kotlin/com/jiancai/account

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.account`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AccountAdminException.kt` | 账号管理用例失败（S03）。 |
| `AccountAdminPort.kt` | 账号管理 Port：S03 唯一对外入口（S13 api-gateway 转发管理员请求，C11 feature-admin 消费）。 |
| `AccountAdminService.kt` | [AccountAdminPort] 的唯一实现：管理员账号 CRUD 的用例层。 |
| `AccountStore.kt` | S03 需要的**账号存储能力**（由 S02 提供，本模块只声明、不实现）。 |
