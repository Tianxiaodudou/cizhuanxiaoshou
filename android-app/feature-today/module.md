# C08 · feature-today

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

当日单列表 + 编辑（复用 C06 录入壳）。

写入范围：`android-app/feature-today/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `TodayRoute.kt`：今日列表、编辑回填、保存（带服务端版本） |

## 入口（Port）

`TodayRoute`

## 依赖

C00、C02、**C06（复用录入壳，卡上要求）**

## 禁止

客户端判断是否跨日；自增版本号。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-today:test`：**`TodayRouteTest` 6 例 全部通过（0 失败 / 0 跳过）**。

- [x] 复用录入壳：编辑直接 `OrderFormComponent.prefillFrom`，金额字段仍为只读
- [x] 跨日不可改由服务端锁：源码无 `LocalDate` / `Clock` / 日期比较，`E2003` 原样回显
- [x] 保存原样回传服务端 `version`，空白备注归一为 null

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C08（客户端阶段 4）：今日改单 + `TodayRouteTest` 6 例 | 1.1.0（未改契约） |
