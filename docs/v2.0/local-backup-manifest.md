# OmniVid 本地备份清单

更新时间：2026-06-16

## 公开提交边界

- 会话恢复 Markdown 可以提交到 GitHub，便于后续 `codex resume` 后快速接续。
- 原始 `backups/` 和 `备份/` 目录继续只保留在本机；它们包含 MySQL dump、运行态清单和可能的 Provider 配置字段，不作为公开仓库产物提交。
- `.gitignore` 已覆盖 `backups/`、`备份/`、`apps/api/storage/`、`storage/`、`artifacts/` 和本地工具/模型文件。

## 会话恢复文件

| 文件 | 用途 |
| --- | --- |
| `codex-session-019eb1b9-full-chat.md` | 恢复后的完整可读对话内容 |
| `codex-session-019eb1b9-index.md` | 会话索引和分段定位 |
| `codex-session-019eb1b9-recovered-chat.md` | 原始恢复稿 |
| `codex-session-019eb1b9-tool-timeline.md` | 工具调用时间线 |

## 本地备份目录

| 路径 | 文件数 | 大小 | 说明 |
| --- | ---: | ---: | --- |
| `backups/omnivid-20260613-174815` | 1 | 48 B | `.env` 缺失提示 |
| `backups/omnivid-20260613-175429` | 4 | 289,385 B | MySQL dump、storage manifest、metadata |
| `backups/omnivid-20260613-175650` | 4 | 289,385 B | MySQL dump、storage manifest、metadata |
| `backups/omnivid-20260613-184209` | 4 | 290,901 B | MySQL dump、storage manifest、metadata |
| `备份/` | 2 | 0 B | 早期本地空占位文件 |

## 恢复优先级

1. 先用 GitHub 仓库恢复代码和文档。
2. 再按需读取本地 `backups/` 中最新的 `backup-metadata.json` 与 `storage-manifest.json`。
3. 如需恢复数据库，仅在本地可信环境导入对应 `mysql-omnivid.sql`，不要把 dump 推送到公开远端。
