# 代码地图 · android-app/core-network/src/main/kotlin/com/jiancai/android/network

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：客户端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.android.network`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ApiClient.kt` | 客户端 HTTP 方法（与 S13 路由表一致；客户端只允许这四种）。 |
| `ApiPaths.kt` | 客户端唯一的接口清单（路径 + 方法 + 序列化器 + 幂等键来源）。 |
| `HttpApiClient.kt` | [ApiClient] 的唯一实现：拼请求 → 发传输层 → 统一解析错误码 / DTO。 |
| `HttpTransport.kt` | 一次真实的 HTTP 交换（C00 内部类型，测试可注入替身）。 |
| `ServerAddress.kt` | 服务端地址（客户端可改，见 C04 登录页）。 |
