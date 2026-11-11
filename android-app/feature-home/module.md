# C05 · feature-home

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

5 按钮布局（采购下单 / 直送下单 / 历史查询 / 差异备注 / 结算上传）。

写入范围：`android-app/feature-home/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `HomeRoute.kt`：5 个按钮与对应导航意图 |

## 入口（Port）

`HomeRoute`

## 依赖

C02（组件）、`shared` 的 `OrderType`

## 禁止

业务计算；调用接口。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-home:test`：**`HomeRouteTest` 6 例 全部通过（0 失败 / 0 跳过）**。

- [x] 按钮跳转正常：5 个按钮 id 唯一、各自跳到正确路由
- [x] 四个下单类型共用同一个录入路由，只靠 `orderType` 区分
- [x] 首页源码不含 `ApiClient` / `Money` / 总额派生

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C05（客户端阶段 4）：首页 + `HomeRouteTest` 6 例 | 1.1.0（未改契约） |
