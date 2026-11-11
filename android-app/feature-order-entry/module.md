# C06 · feature-order-entry

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

4 类录入页共用 `OrderFormComponent`，只传 `orderType`。

写入范围：`android-app/feature-order-entry/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `OrderEntryRoute.kt`：表单状态、命令拼装、本地必填校验、提交、服务端订单回填、屏规格 |

## 入口（Port）

`OrderEntryRoute(orderType)`、`OrderFormComponent`

## 依赖

C00（接口）、C02（组件）、`shared` 的订单 / 客户 DTO

## 禁止

客户端算金额 / 单号；上传单价 / 金额 / 单号 / 日期。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-order-entry:test`：**`OrderEntryRouteTest` 8 例 全部通过（0 失败 / 0 跳过）**。

- [x] 4 类页共用组件：`OrderType` 四个值生成的屏结构一致，仅标题不同
- [x] 提交走服务端：只 `POST /orders`，幂等键 = `client_request_id`，返回的订单号 / 金额全部来自服务端
- [x] 命令线格式不含 `unit_price` / `total_amount` / `order_no` / `order_date`
- [x] 回填时数量逐字复制服务端字符串，不做任何重算

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C06（客户端阶段 4）：下单录入 + `OrderEntryRouteTest` 8 例 | 1.1.0（未改契约） |
