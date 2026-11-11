# C11 · feature-admin

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

型号库、价格、结算审核、账号四个子页。

写入范围：`android-app/feature-admin/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `AdminRoute.kt`：四个子页 + 各子页动作（全部走 C00 接口清单） |

## 入口（Port）

`AdminRoute`

## 依赖

C00、C02、`shared` 的管理类 DTO

## 禁止

客户端判断角色；绕过服务端权限。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-admin:test`：**`AdminRouteTest` 6 例 全部通过（0 失败 / 0 跳过）**。

- [x] 4 子页可用：型号库 / 价格 / 结算审核 / 账号各一屏，动作路径与方法正确
- [x] 权限由服务端把关：源码零 `Role` / `isAdmin` 分支，越权 `E403` 原样回显
- [x] 账号页不接触密码字段（`AccountDto` 无密码字段）

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C11（客户端阶段 4）：管理后台 + `AdminRouteTest` 6 例 | 1.1.0（未改契约） |
