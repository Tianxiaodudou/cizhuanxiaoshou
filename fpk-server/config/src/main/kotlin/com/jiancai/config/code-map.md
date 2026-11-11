# 代码地图 · fpk-server/config/src/main/kotlin/com/jiancai/config

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.config`：6 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ConfigException.kt` | 部署配置错误（S01）：缺少商家名称、配置为空白、配置表不可用等。 |
| `ConfigMigrations.kt` | S01 的迁移（版本号段 200–299，见 `db-core/module.md`）。 |
| `ConfigPort.kt` | 部署配置 Port：S01 唯一对外入口（客户端只能读，不能传）。 |
| `ConfigRepository.kt` | `deployment_config` 的唯一 SQL 出口（铁律 10）。 |
| `ConfigService.kt` | [ConfigPort] 的唯一实现：启动时把服务端部署配置落到库中，之后只读。 |
| `DeploymentConfig.kt` | 服务端部署配置（当前只有商家名称）。 |
