# Child Implement Plan - Diff Check Loop

## Ordered Steps

1. 定义变更输入源（git diff / staged diff / task files）
2. 定义 ownership / routing 输入
3. 定义 generated 资产刷新前置
4. 定义 diff-check 输出模型
5. 定义硬错误 / 软建议门禁
6. 定义 session 级去重桥
7. 定义人工 review 流程
8. 定义健康度信号输出（broken link / orphan / low confidence / contested）
9. 定义与 Trellis 的挂点：before-dev / check / finish-work
10. 定义“建议文本 -> 人工确认 -> 写文件”的语义更新路径
11. 定义建议文本 / 恢复文本格式
12. 定义 hash 跳读规则与复用条件

## Validation

- 能清楚说出每一步输入输出
- 能说明哪些错误阻断、哪些只提示
- 能说明如何和真实开发流程挂接
- 能说明如何避免同一 session 重复刷同一批建议
- 能说明 `skills/` 更新和 `.trellis/spec/` 更新分别落在哪一步
- 能说明哪些动作自动做，哪些动作必须等人工点头
- 能说明上下文压缩后如何从建议文本恢复，而不是重新全量扫描


## Dependency

- 依赖 `06-30-skills-knowledge-structure`
- 依赖 `06-30-skills-generated-knowledge`








