# C09 · feature-note

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

备注单 + 选原单（`SelectOrderPage`）。

写入范围：`android-app/feature-note/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `NoteRoute.kt`：创建 / 作废备注单、选原单页 |

## 入口（Port）

`NoteRoute`、`SelectOrderPage`

## 依赖

C00、C02、`shared` 的备注与订单 DTO

## 禁止

修改原单；删除备注单。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-note:test`：**`NoteRouteTest` 6 例 全部通过（0 失败 / 0 跳过）**。

- [x] 备注单正确关联原单：创建只 `POST /notes` 且带 `source_order_no`
- [x] 不修改原单：本模块没有任何 `PUT /orders` / `UpdateOrderCmd`（测试扫描）
- [x] 作废只打到 `/notes/{noteNo}/void`，原单不受影响

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C09（客户端阶段 4）：差异备注单 + `NoteRouteTest` 6 例 | 1.1.0（未改契约） |
