# C02 · core-ui

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

通用组件、主题、按钮 `@UiButton` 注解、路由 id 目录、打印关键词 deny 策略。

写入范围：`android-app/core-ui/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `UiKit.kt`：组件规格、主题、`@UiButton`、`ShellTab`、`RouteEntry`、`AppRoutes`（路由 id 唯一真源）、`ClientDenyPolicy`（打印关键词唯一真源） |

## 入口（Port）

`ScreenSpec` / `ButtonSpec` / `FieldSpec` / `Theme` / `RouteEntry` / `ShellTab` / `AppRoutes` / `ClientDenyPolicy`

## 依赖

无（不 import 任何业务模块）

## 禁止

耦合具体业务；提供打印动作。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:core-ui:test`：**`UiKitTest` 8 例 全部通过（0 失败 / 0 跳过）**。

- [x] 组件可复用：同一套 `ScreenSpec` 描述下单 / 查询 / 设置三种屏；按钮 id 重复即失败
- [x] 不 import 业务 DTO / 网络层 / 任何 feature（测试只扫 import 行）
- [x] 打印相关关键词全局 deny：`ClientDenyPolicy.FORBIDDEN_KEYWORDS` 是 SCAN 脚本的唯一关键词真源

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C02（客户端阶段 4）：通用界面 + `UiKitTest` 8 例 | 1.1.0（未改契约） |
