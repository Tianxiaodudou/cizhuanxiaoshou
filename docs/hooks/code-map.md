# Code map · docs/hooks

## 文件

| 文件 | 说明 |
| --- | --- |
| `dsh-hooks.json` | dsh hooks 配置：pre-commit 阻断打印关键词，pre-push 校验文档 / 代码地图新鲜度 |

## hooks 一览

| hook | 触发 | 命令 | 失败策略 |
| --- | --- | --- | --- |
| `print-keyword-deny` | pre-commit | `docs/tools/scan-buttons.ps1` | block（退出码 1） |
| `button-scan` | pre-push | `docs/tools/scan-buttons.ps1` | warn（只报告） |
| `code-map-freshness` | pre-push | `docs/tools/verify-hooks.ps1 -Check code-map` | warn |

本地验证（不需要 dsh 运行时）：`powershell -File docs/tools/verify-hooks.ps1` → `HOOKS OK`。
