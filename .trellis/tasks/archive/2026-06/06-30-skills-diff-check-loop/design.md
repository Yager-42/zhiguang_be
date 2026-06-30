# Child Design - Diff Check Loop

## Scope

本子任务设计的是自动防腐化闭环，不直接落地 skills 内容。

## Loop Stages

1. change detection
2. skill impact routing
3. generated fact refresh
4. skill vs code diff-check
5. update suggestion generation
6. human review gate

## Trellis Integration Points

### Before Dev

- 在进入真实实现前，先按任务范围路由相关 `skills/`
- 这一步与 `trellis-before-dev` 是互补关系：
  - `skills/` 提供业务知识
  - `.trellis/spec/` 提供工程规范

### Check

- 在 `trellis-check` 同轮执行 skill diff-check
- 目标不是替代现有质量检查，而是补“代码变了，但业务知识没更新”的盲区

### Finish Work

- 在 `finish-work` 前确认当前任务没有未处理的 skills 更新建议
- 如果还有高优先级建议未处理，不能把它当成普通 dirty note 留到下次

## Imported Patterns From maestro-flow

### 1. Confidence Model

参考 `maestro-flow` 的知识可信度表达：

- `high`
- `medium`
- `low`
- `contested`

### 2. Conflict Expression

参考它的冲突标记字段：

- `conflict-marker`
- `conflict-note`

### 3. Keyword Matching

参考它的关键词索引与中文分词思路：

- 英文 token
- CJK 2-4 gram
- stop words 过滤
- 单次注入 / 建议条目数上限

这里不要求原样复刻 hook，但要求 diff-check 路由逻辑可复用这套匹配思路。

### 4. Domain Matching

参考它的 `domain-matcher`：

- 中文别名按 CJK 规则匹配
- 英文术语按词边界匹配
- 匹配顺序：canonical -> alias -> keyword
- 允许 1 层 relationship propagation

### 5. Session Dedup Bridge

参考它的 `spec-bridge`：

- 同一 session 已经给出的建议，不重复注入或重复提示
- 去重粒度至少覆盖“建议条目 ID”与“匹配关键词”

### 6. Health Signals

参考它的 `graph-analysis`：

- broken links
- orphans
- health score

本项目不一定首版就引入完整图分析，但 diff-check 输出要能承接这些健康信号。

### 7. Recovery Checkpoint

参考 `comet` 的 `brainstorm-summary.md` 和 `context-recovery.md`：

- diff-check 产出的建议不只在当前对话里展示，还要能落成“建议文本 / 恢复文本”
- 当上下文压缩或会话切换发生时，后续阶段应优先读取这些文本，而不是重新拼接全部扫描结论
- 至少要保存：
  - 本次命中的 skill
  - 命中的 generated 资产
  - 风险分组
  - 建议文本
  - 是否已人工确认

### 8. Hash-Gated Scanning

参考 `comet` 的 `handoff_hash` / `--hash-only`：

- 进入 diff-check 前先比较：
  - 代码输入 hash
  - generated 资产 hash
  - 上次建议文本 hash
- 若三者都未变化，则本轮直接复用上次建议结果，不重复做重扫描和重注入
- 若只有 generated 资产变化，则只重跑受影响 skill 的 diff-check

## Auto vs Manual Boundary

### Auto

允许自动执行：

- generated 资产刷新
- 索引失效 / rebuild
- ownership / routing 重算
- 候选 `low` / `contested` 标记生成
- skills 更新建议文本生成

### Manual Confirmation Required

不允许静默自动写入：

- 业务语义描述修改
- glossary 定义修改
- playbook / gotchas / decision 内容改写
- 任何真正写回 `skills/` 文件的语义更新

约束：先产出建议文本，人工点头后再写文件。

## Severity Model

### Hard Errors

- 引用路径不存在
- generated 资产缺失或未刷新
- ownership 无法路由

### Soft Suggestions

- 新增术语未进入词典
- 新约束未进入 playbook / gotchas
- 某段技能知识疑似过期
- 某条知识应降级为 `low`
- 某条知识应标记为 `contested`
- 某个知识条目疑似孤立，缺少 backlink / 引用来源

## Review Rule

- 自动生成建议
- 人工确认后再更新 skills
- 不允许静默自动合入语义更新
- diff-check 输出需要能表达“缺失、过期、冲突、低可信度、孤立”五种状态
- Trellis 中的 `.trellis/spec` 更新与 `skills/` 更新必须分别结算，不能混写
- diff-check 建议必须先落成建议文本，再等待人工确认，不允许直接写语义知识






