# 代码地图 · fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.gateway`：12 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ApiGateway.kt` | 网关核心：请求 → 路由 → 鉴权 → 幂等 → handler → 统一响应。 |
| `AuthGate.kt` | 鉴权 + 权限拦截（验收「权限在 API 层拦截」，铁律 7）。 |
| `ErrorMapping.kt` | 错误码统一映射（验收「错误码统一」）。 |
| `GatewayDeps.kt` | 网关的全部依赖（组合根装配时一次性注入）。 |
| `GatewayException.kt` | 网关自身产生的失败（路由 / 鉴权 / 幂等 / 编解码）。 |
| `GatewayMigrations.kt` | S13 的迁移（版本号段 800–899；100 S07、200 S01、210 S02、300 S04、310 S05、320 S06、 |
| `GatewayRoutes.kt` | 全部路由（对客户端暴露的唯一入口；C00 只能看到这里的路径）。 |
| `Http.kt` | HTTP 方法（只支持网关实际暴露的四种；其它方法一律 404，避免半实现语义）。 |
| `HttpServerAdapter.kt` | JDK 自带 HTTP 服务器适配层（唯一与具体服务器绑定的一层）。 |
| `Idempotency.kt` | 幂等键存储：接口在这里，实现在本模块内（生产可换成持久化实现，不需要改路由）。 |
| `Route.kt` | 路由的权限级别（铁律 7：权限**只**在这里判断，业务模块不各自判断）。 |
| `SqliteIdempotencyStore.kt` | `gateway_idempotency` 的唯一 SQL 出口（铁律 10）。 |
