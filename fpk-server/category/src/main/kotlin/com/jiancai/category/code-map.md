# 代码地图 · fpk-server/category/src/main/kotlin/com/jiancai/category

> 本文件由 `docs/tools/gen-code-map.ps1` 生成：列出**本层**的文件与子目录，子目录各自有 `code-map.md`（递归到所有层级）。

本目录：服务端模块包目录

> 说明：`build/`、`.gradle/`、`.git/`、`.tools/` 为构建 / 工具产物，不列入代码地图。

包 `com.jiancai.category`：5 个源码文件

## 文件

| 文件 | 说明 |
| --- | --- |
| `CategoryException.kt` | 型号库用例失败（S05）。 |
| `CategoryMigrations.kt` | S05 的迁移（版本号段 300–399，见 `db-core/module.md`；本模块取 310）。 |
| `CategoryPort.kt` | 品类 / 型号库 Port：S05 唯一对外入口。 |
| `CategoryRepository.kt` | 型号库写入形态（内部类型）：Service 完成裁剪 / 校验后交给 Repository 落库， |
| `CategoryService.kt` | [CategoryPort] 的唯一实现：型号库的用例层。 |
