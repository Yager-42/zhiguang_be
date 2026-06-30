# Skills Knowledge Structure

## Goal

为 `com.tongji` 主线建立仓库内共享的 `skills/` 业务知识结构层，包含 9 个 skills 的骨架、根 README、领域拆分、旧 `CONTEXT.md` 迁移删除方案，并明确它与 `.trellis/spec/` 的分工边界。

本子任务额外负责把 `maestro-flow` 已核实可复用的知识协议和写入行为落到结构设计里，而不是只建空目录。

## Requirements

- 新建根目录 `skills/`
- 首版落地全部 9 个 skills 骨架
- 技能命名、边界、路由关系必须稳定
- `CONTEXT.md` 中仍有效知识迁移到 `zhiguang-business-dictionary`
- `CONTEXT.md` 在本子任务实现范围内删除
- 明确 `skills/` 只承载业务知识，`.trellis/spec/` 继续只承载工程规范
- 本子任务不负责自动抽取脚本和 diff-check 闭环
- skill 内部需要定义结构化索引条目协议，参考 `maestro-flow` 的 `<spec-entry>` / `<knowhow-entry>`
- skill 下 `references/` 需要定义 knowhow 文档类型前缀，参考 `RCP-`、`TIP-`、`DCS-`、`AST-`、`REF-`、`DOC-`
- `zhiguang-business-dictionary` 需要定义 business glossary schema，参考 `id`、`canonical`、`aliases`、`definition`、`relationships`、`keywords`、`tier`、`status`、`source`
- 需要定义索引页与长文档的分工规则：短知识进索引条目，长知识转 `references/` 并通过 `ref` 连接
- 需要定义 append-only 与标题去重规则，避免 knowledge page 被随意覆盖

## Acceptance Criteria

- [ ] `skills/README-zh.md` 路由结构清晰
- [ ] 9 个 skills 骨架命名和边界明确
- [ ] `zhiguang-business-dictionary` 能承接旧词典知识
- [ ] `CONTEXT.md` 的迁移与删除策略清楚
- [ ] `skills/` 和 `.trellis/spec/` 的职责分工被文档化
- [ ] skill 内部条目协议字段集已经定稿
- [ ] `references/` knowhow 分类规则已经定稿
- [ ] business glossary schema 已经定稿
- [ ] append-only / duplicate-check / ref 模式已经定稿

## Out of Scope

- generated references 脚本
- diff-check / pre-commit / pre-dev 自动化
- Trellis 机制改造
