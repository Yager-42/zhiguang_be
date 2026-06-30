# Child Design - Generated Knowledge

## Scope

本子任务只做结构化知识抽取层。

## Candidate Generated Assets

- repo / controller / API 入口索引
- config key 索引
- ownership / path mapping
- 可能的 enum / workflow 字段索引

## Imported Patterns From maestro-flow

### Generated Assets Stay Mechanical

参考 `maestro-flow` 的 spec / knowhow 分层，这一层只产出“代码事实”，不产出业务解释。

### Asset Metadata Contract

参考它对索引与注入所依赖的元信息，这里每份 generated 文档都需要显式记录：

- `generated_at`
- `source_paths`
- `generator`
- `refresh_trigger`

### Asset Typing

参考 `AST-` 资产文档思路，generated 文档在 skill 内应被视为 `asset` 类型，而不是普通 prose 文档。

### Invalidate / Rebuild Mindset

参考它的 `WikiIndexer.invalidate()` 与 `search-cache` 思路：

- generated 资产刷新后，需要显式触发所属 skill 的知识缓存失效
- 下次消费时要可重建，不依赖人工手动提醒
- 失效与重建是 best-effort 的自动行为，不应该要求研发手动维护多个状态位

### Hash-Based Skip Read

参考 `comet-handoff.sh --hash-only` 与 verify 阶段的 hash 比较：

- generated 资产需要可独立计算 hash
- diff-check / before-dev 在源输入未变化时，可以跳过对应 generated 资产重读
- 跳过前提必须是：
  - 输入源 hash 未变化
  - generated 输出存在
  - 元信息完整
- 这只是“跳过重复读取/刷新”，不是跳过语义校验

## Design Constraints

- 生成结果落在对应 skill 的 `references/generated-*.md`
- 脚本生成的是代码事实，不是业务解释
- 可抽取知识与人工知识必须分层
- 输出文件需要能被 child C 直接消费，不再二次猜测来源和归属
- generated 文件名与元信息足以支持刷新前后 diff
- generated 文件需要支持快速 hash 计算与 freshness 判定


## Output Contract

每个 generated 资产需要定义：

- 输入源
- 生成脚本位置
- 输出文件位置
- 刷新触发条件
- 元信息头字段
- 刷新后的缓存失效动作






