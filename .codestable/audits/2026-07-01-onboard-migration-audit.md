# CodeStable Onboard Migration Audit

日期：2026-07-01

## 扫描结论

本仓库已存在大量历史规划、设计、接口与 OpenSpec 文档，因此按迁移路径处理。当前已完成 `.codestable/` 基础骨架落盘；历史文档尚未移动、删除或重命名，需 owner 确认后再归档。

未检测到旧版 `easysdd/` 或 `codestable/` 目录。

## 已发现的历史文档类别

| 现有路径 | 内容类型 | 建议归入 CodeStable | 置信度 | 当前处理 |
|---|---|---|---|---|
| `README.md` | 项目概览、技术栈、模块亮点 | 保留原位；必要摘要可由 `cs-domain` 后续写入 `.codestable/requirements/CONTEXT.md` | 中 | 保留原位 |
| `goal.md` | 一次任务执行指令与硬规则 | `.codestable/goals/2026-07-01-data-reconciliation/` 下的历史 goal 资料 | 中 | 保留原位，待确认 |
| `docs/计数系统设计方案.md` | 计数系统技术设计 | `.codestable/features/YYYY-MM-DD-counter-system/design.md` 或沉淀到 `.codestable/compound/` | 中 | 保留原位，待确认 |
| `docs/用户关系设计方案.md` | 用户关系模块技术设计 | `.codestable/features/YYYY-MM-DD-user-relation/design.md` 或沉淀到 `.codestable/compound/` | 中 | 保留原位，待确认 |
| `docs/API接口*.md` | API 接口文档 | `.codestable/compound/` 或由 `cs-doc-api` 后续整理 | 中 | 保留原位，待确认 |
| `docs/api/publish-attempts.md` | 发布 attempt API 文档 | `.codestable/compound/` 或由 `cs-doc-api` 后续整理 | 中 | 保留原位，待确认 |
| `docs/docker-local-env.md` | 本地 Docker 环境说明 | 可提炼关键命令到 `.codestable/attention.md`；原文保留 | 中 | 保留原位，待确认 |
| `docs/cassandra.md` | Cassandra 相关说明 | `.codestable/compound/` 或需求上下文 | 中 | 保留原位，待确认 |
| `docs/prd/2026-06-18-bidding-system-integration.md` | PRD / 功能需求 | `.codestable/features/2026-06-18-bidding-system-integration/` | 中 | 保留原位，待确认 |
| `docs/superpowers/plans/*.md` | 历史计划文档 | `.codestable/roadmap/`、`.codestable/goals/` 或对应 feature 目录 | 中 | 保留原位，待确认 |
| `openspec/specs/*/spec.md` | OpenSpec 能力规格 | 暂保留 `openspec/`；后续按能力映射到 `.codestable/requirements/` 或 feature 资料 | 中 | 保留原位，待确认 |
| `openspec/changes/**/{proposal,design,tasks}.md` | OpenSpec 变更提案、设计、任务 | 暂保留 `openspec/`；后续按 change 映射到 `.codestable/features/` 或 `.codestable/roadmap/` | 中 | 保留原位，待确认 |
| `openspec/changes/archive/**` | 已归档 OpenSpec 变更 | 暂保留 `openspec/changes/archive/`；可批量沉淀到 `.codestable/compound/` | 中 | 保留原位，待确认 |
| `openspec/changes/execution-order.md` | OpenSpec 变更执行顺序 | `.codestable/roadmap/` | 高 | 保留原位，待确认 |
| `openspec/grillme-decisions.md` | 规划前关键决策记录 | `.codestable/requirements/adrs/` 或 `.codestable/compound/` | 中 | 保留原位，待确认 |
| `openspec/11pdf-*.md` | 集成矩阵 / handoff 资料 | `.codestable/compound/` | 中 | 保留原位，待确认 |

## 本次已落盘骨架

- `.codestable/.gitignore`
- `.codestable/attention.md`
- `.codestable/requirements/.gitkeep`
- `.codestable/roadmap/.gitkeep`
- `.codestable/goals/.gitkeep`
- `.codestable/features/.gitkeep`
- `.codestable/issues/.gitkeep`
- `.codestable/refactors/.gitkeep`
- `.codestable/audits/.gitkeep`
- `.codestable/brainstorms/.gitkeep`
- `.codestable/compound/.gitkeep`
- `.codestable/gates/`
- `.codestable/tools/`
- `.codestable/reference/`
- `.codestable/hooks/`

## OCR 状态

Owner 已明确不使用 OCR。本机当前 `ocr` 未安装，`codestable-doctor.py --root .` 报告 `OCR tool: not-installed`。这是可选增强，不阻塞 CodeStable 使用。

## 后续迁移建议

优先不要一次性移动全部历史文档。建议先确认一批高价值映射：

1. 将 `openspec/changes/execution-order.md` 归入 `.codestable/roadmap/`。
2. 将 `docs/docker-local-env.md` 中每次都必须知道的命令提炼进 `.codestable/attention.md`。
3. 按当前活跃 feature 或 goal，逐个把相关 `docs/superpowers/plans/` 与 `openspec/changes/` 资料归入对应 `.codestable/features/` 或 `.codestable/goals/`。

在 owner 未确认前，所有历史文档继续保留原位。
