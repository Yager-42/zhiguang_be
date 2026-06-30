# Skills Generated Knowledge

## Goal

为 skills 体系建立可机械重建的 generated knowledge 层，包括抽取脚本、generated references 和 ownership / mapping 输入。

## Requirements

- 识别适合自动抽取的高漂移知识
- 为至少关键路由/入口/配置等信息提供脚本化生成能力
- generated 输出路径要归属于对应 skill
- 不重写业务语义文档，只处理结构化事实
- generated 文档需要显式可刷新、可失效、可重建
- generated 文档需要能作为 `asset` 被 skill 和 diff-check 消费
- generated 文档需要支持 hash-based freshness 判断，避免每次都全量重读


## Acceptance Criteria

- [ ] 至少定义一组 generated references 方案
- [ ] 明确哪些知识可抽取，哪些必须人工维护
- [ ] 抽取脚本与 skill 边界一致
- [ ] generated 资产可被后续 diff-check 消费
- [ ] generated 文档元信息与失效规则清楚
- [ ] generated 资产具备支持跳读的 hash / freshness 设计


## Out of Scope

- 自动更新语义化文档
- 人工审核门
- pre-commit / pre-dev 挂点






