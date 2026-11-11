# C10 · feature-settle

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

结算上传，成功 / 失败当场回显。

写入范围：`android-app/feature-settle/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `SettleRoute.kt`：上传结算 + 当场结果模型 + 屏规格 |

## 入口（Port）

`SettleRoute`、`SettleOutcome`

## 依赖

C00、C02、`shared` 的结算 DTO

## 禁止

客户端判定结算；修改已收金额。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-settle:test`：**`SettleRouteTest` 5 例 全部通过（0 失败 / 0 跳过）**。

- [x] 上传与回显正确：成功只以服务端返回的 `SettleDto` 为准，失败原样展示服务端错误码与文案
- [x] 源码无金额比较 / 结算状态判断（`success` 只判断 `settle != null`）
- [x] 未收金额字段只读，展示服务端读模型

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C10（客户端阶段 4）：结算上传 + `SettleRouteTest` 5 例 | 1.1.0（未改契约） |
