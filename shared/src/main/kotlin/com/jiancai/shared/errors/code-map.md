# 代码地图 · shared/src/main/kotlin/com/jiancai/shared/errors

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：生产源码根

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.shared.errors`：3 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `ErrorCategory.kt` | 错误分类（唯一真源）：S13 api-gateway 按分类统一映射 HTTP 状态、日志级别与客户端动作。 |
| `ErrorCode.kt` | 全量错误码（唯一真源）。 |
| `ErrorResponse.kt` | 统一错误响应体（唯一真源）：S13 产出，C00 统一解析。 |
