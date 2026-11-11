# 代码地图 · fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.gateway`：9 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ErrorMappingTest.kt` | 验收「错误码统一」。 |
| `GatewayMigrationsTest.kt` | 持久化幂等台账（`gateway_idempotency`，迁移 800）： |
| `HttpAdapterTest.kt` | HTTP 适配层（真起一个 JDK HTTP 服务器，用真实 `HttpClient` 打请求）。 |
| `IdempotencyTest.kt` | 验收「幂等键校验」。 |
| `PermissionTest.kt` | 验收「权限在 API 层拦截」。 |
| `RoutesBehaviourTest.kt` | 路由行为（每个业务模块一个入口用例）：证明「网关只转发、DTO 由服务端产出」。 |
| `RouteTableTest.kt` | 路由表的静态守卫（硬边界「禁止实现业务逻辑」，只转发 / 拦截 / 映射）： |
| `TestFakes.kt` | 网关测试用的「假世界」：所有 Port 的内存替身 + 调用计数。 |
| `TestSupport.kt` | internal fun <T> ApiResponse.decode(serializer: KSerializer<T>): T = |
