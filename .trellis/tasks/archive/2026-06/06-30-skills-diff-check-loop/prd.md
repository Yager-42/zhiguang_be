# Skills Diff Check Loop

## Goal

为 skills 体系设计完整自动防腐化闭环：变更感知、影响 skill 识别、知识 diff-check、更新建议和人工审核门。

## Requirements

- 能基于代码变更判断受影响 skill
- 能区分硬错误与软建议
- 能消费 generated knowledge 和手写 knowledge
- 能在开发前、开发后或提交前提供检查入口
- 不允许直接无审查自动写回语义文档
- 需要有知识健康度视角，而不只是 diff 结果视角
- 需要有 session 级去重，避免同一轮重复轰炸同一建议
- 需要明确挂到 Trellis 哪些阶段，而不是做成一套额外流程
- 需要区分“可自动机械处理”和“必须人工确认的语义更新”

## Acceptance Criteria

- [ ] diff-check 闭环被拆成可实现组件
- [ ] 输入输出定义清楚
- [ ] 硬错误 / 软建议分级清楚
- [ ] 人工审核门被保留
- [ ] 低可信度 / 冲突 / 过期 / 孤立知识的表达清楚
- [ ] Trellis 集成时机清楚
- [ ] 语义更新先出建议文本、后写文件的约束清楚

## Out of Scope

- 全自动无人审核回写
- 与 CI/CD 的最终集成实现
