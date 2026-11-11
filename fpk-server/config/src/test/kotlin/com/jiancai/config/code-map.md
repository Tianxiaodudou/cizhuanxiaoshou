# 代码地图 · fpk-server/config/src/test/kotlin/com/jiancai/config

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.config`：3 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ConfigMigrationsTest.kt` | 迁移验收：号段归属、建表、重复迁移幂等。 |
| `ConfigPortTest.kt` | 验收：商家名称由服务端部署配置生效，客户端无法传入 / 改写；配置持久化且可重启复用。 |
| `TestSupport.kt` | internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") |
