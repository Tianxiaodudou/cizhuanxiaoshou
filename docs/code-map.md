# 代码地图

代码地图分两部分，本文件只是入口：

- **总览**：`docs/CODEMAPS/` —— [README](CODEMAPS/README.md)、[architecture](CODEMAPS/architecture.md)、
  [backend](CODEMAPS/backend.md)、[frontend](CODEMAPS/frontend.md)、[conventions](CODEMAPS/conventions.md)。
- **文件头**：每个 `.kt` 源码文件顶部 `@file / @role / @exports / @depends / @calledBy / @sideEffects`，
  由 `docs/tools/gen-code-map.ps1` 生成（幂等，改代码后重跑）。

约定：总览只讲"系统怎么运转"，文件头只讲"这个文件在系统里的位置"；行号、签名、调用顺序不写进文件头。
完整性检查：`powershell -File docs/tools/check-file-headers.ps1`（前 10 行必须有 `@file`，默认只警告）。
