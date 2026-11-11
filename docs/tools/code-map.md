# docs/tools · 代码地图与校验脚本

| 文件 | 说明 |
| --- | --- |
| `gen-code-map.ps1` | 给所有 `.kt` 文件写文件头代码地图（`@file/@role/@exports/@depends/@calledBy/@sideEffects`），幂等 |
| `check-file-headers.ps1` | 检查每个 `.kt` 前 10 行是否有 `@file`；默认只警告，`-Strict` 失败 |
| `scan-buttons.ps1` | 按钮 / 打印关键词扫描（关键词真源：C02 `ClientDenyPolicy`） |
| `verify-hooks.ps1` | hooks 自检：扫描 + 文件头 + CODEMAPS / module.md 完整性 |
| `device-e2e.sh` | 真机联调脚本（手机 curl 打真实网关，配合 `adb reverse`） |
| `code-map-template.json` | 文件头中文文案模板（脚本本体保持 ASCII，兼容 PowerShell 5.1） |

总览地图在 `docs/CODEMAPS/`；本目录不再生成逐目录的 `code-map.md`（避免与总览重复、过期）。
