# C12 · feature-settings

> contractVersion = **1.1.0**（客户端只消费 shared 契约，不新增线格式）
> **环境降级**：本机无 Android SDK，按任务卡允许的方式用 **JVM 模块 + JVM 单测**验证（真实工程换 ndroid library 插件即可，源码无需改动）。

## 职责

客户端配置 + 只读打印状态。

写入范围：`android-app/feature-settings/**`。

## 目录

| 文件 | 内容 |
| --- | --- |
| `SettingsRoute.kt`：服务端地址保存 / 复位、只读打印状态 |

## 入口（Port）

`SettingsRoute`、`PrintStatus`

## 依赖

C00（地址存储）、C02（组件与 deny 策略）

## 禁止

客户端打印；打印关键词。

## 验收对照

本机 JDK 17 + Gradle 8.10.2 执行 `:android-app:feature-settings:test`：**`SettingsRouteTest` 6 例 全部通过（0 失败 / 0 跳过）**。

- [x] 打印状态只读：`PrintStatus.readOnly` 恒 true，数值只能由服务端数据构造，无任何修改入口
- [x] 设置页按钮只有「保存地址 / 恢复默认」，不存在打印按钮
- [x] 打印相关文案只能复用 C02 的允许清单（否则测试失败）

## 客户端全局约束（与任务卡一致）

1. 金额、单号、批号只展示不计算（服务端产出）；
2. 只调 S13 的聚合接口（路径只能来自 C00 的 ApiPaths）；
3. 打印相关关键词全局 deny（唯一关键词真源在 C02 ClientDenyPolicy，由 SCAN 脚本核对）；
4. token 判断只在 C01 一处（本模块不接触 token / Authorization）。

## 变更记录

| 日期 | 变更 | contractVersion |
| --- | --- | --- |
| 2026-11-11 | 新建 C12（客户端阶段 4）：设置 + `SettingsRouteTest` 6 例 | 1.1.0（未改契约） |
