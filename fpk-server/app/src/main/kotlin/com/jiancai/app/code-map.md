# 代码地图 · fpk-server/app/src/main/kotlin/com/jiancai/app

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.app`：4 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `AccountStoreAdapter.kt` | S03 `AccountStore` ↔ S02 `AccountStorePort` 的薄适配器（约 20 行，只做转调 + 错误翻译）。 |
| `App.kt` | 组合根：进程启动时把 S00–S07 唯一装配一次，之后只对外暴露 Port。 |
| `GatewayMain.kt` | 常驻 HTTP 服务入口（组合根）：打开库 → 迁移 → 装配 S00–S14 → 启动 S13 网关。 |
| `Main.kt` | 启动自检入口：打开库 → 迁移 → 装配 Port → （可选）创建初始管理员，然后退出。 |
