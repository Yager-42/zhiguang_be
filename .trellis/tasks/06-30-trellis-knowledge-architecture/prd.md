# Trellis knowledge architecture

## Goal

为当前仓库里的 Trellis 工作流设计一套可持续使用的项目知识管理架构，让 Trellis 在实现需求时，能够从项目知识中自动或半自动获得业务事实、结构事实、术语边界和开发规范辅助，而不是每次都重新摸索。

这次规划需要先吸收并评估 `refs/maestro-flow` 已实现的知识系统，重点看它的知识如何存储、索引、检索、注入工作流，以及这些机制里哪些适合本仓库，哪些不适合直接照搬。

## Confirmed Facts

- 用户已经明确要求：本轮先研究 `Maestro-Flow` 的知识系统实现，以及它如何和工作流合并，然后再推进 Trellis 的知识架构设计。
- 当前任务是复杂规划任务，不是立即实现代码。
- 当前 active planning task 为 `.trellis/tasks/06-30-trellis-knowledge-architecture`。
- 用户已经明确确认：顶层方向按 `Maestro-Flow` 的知识管理体系来，目标是完整复刻其知识体系。
- 用户已经明确确认：复刻范围包含完整运行时闭环，而不是只复刻静态目录和文档协议。
- 用户已经明确确认：被复刻的知识系统并入 Trellis，而不是和 Trellis 并行存在。
- 用户已经明确确认：接受对 `Maestro-Flow` 的 `spec` 做语义映射，保留 `.trellis/spec/` 作为唯一开发规范 / code-spec owner，不复制第二套开发规范 owner。
- 用户已经进一步明确：真正想引入的是 `refs/AI-Meeting/skills` 这种知识层次和分区方式，但底层实现形式要采用 `Maestro-Flow` 的存储、索引、搜索、注入和回写机制。
- 用户已经进一步明确：当前接受的主层次至少包括
  - `.trellis/spec/`
  - `.trellis/domain/`
  - `.trellis/knowhow/`
  - 路由层（AI-Meeting 风格的 skill / 入口视图层）
- 用户已经明确确认：
  - `v1` 不单独建立 generated 第五层
  - 路由层不继续做成仓库根 `skills/`，而是并入 `.trellis/` 体系
  - 这层不命名为 `.trellis/skills/`，采用不会和平台 skill 目录冲突的名字
  - 这层采用 `.trellis/views/` 命名
  - `.trellis/views/` 只承载入口、摘要、关键入口和阅读顺序，不承载长正文 owner
- 仓库最近已经做过两项相关设计探索：
  - `06-30-skills-knowledge-structure`
  - `06-30-skills-generated-knowledge`
- git 历史里还出现过一次 `skills-driven knowledge workflow` 实现尝试，但当前工作区里对应的 `skills/`、`scripts/skills/`、`CONTEXT.md`、相关 workflow 知识层并不存在，说明那套结构现在不是仓库中的现行事实。
- 上述两项历史设计已经形成一些稳定约束：
  - 业务知识与工程规范应分层。
  - 结构化条目协议、knowhow 分类、glossary schema、append-only 写入模型都已经被认为值得保留。
  - generated knowledge 只应承载机械可抽取的代码事实，不应伪装成人工业务解释。
- 当前仓库已有 `.trellis/spec/`，且它主要承载工程规范，而不是完整的项目业务知识。
- 当前仓库的 `trellis-update-spec` 已经把 `.trellis/spec/` 定义成 code-spec owner，负责 durable implementation contracts、coding conventions、cross-layer contracts 和可复用工程规则。
- 仓库历史设计已经多次强调 single owner，尤其反对在同一项目里出现两套并行的 authority/spec 文档家族。
- 当前仓库没有活跃的 `.workflow/` 根目录，也没有活跃的 `skills/` 知识根目录。
- 当前 Trellis 的脚本、workflow 和技能大量依赖 `.trellis/spec`、`.trellis/tasks`、`.trellis/workspace` 这些既有路径，但没有看到“禁止 `.trellis/` 下增加新兄弟目录”的硬约束。
- 当前 Trellis 的本地技能惯例仍然是平台目录或共享 `.agents/skills/`，没有现成的 `.trellis/skills/` 正式惯例。
- 因此“把路由层并进 `.trellis/`”在当前仓库里是可行的新设计，但它不是直接套用 Trellis 既有目录约定。
- `views` 比 `routes` 更贴近这层实际职责，因为它不只负责把请求路由到哪个知识域，还负责定义先看什么、关键入口、如何下钻。
- 当前已确认的 owner 分工是：
  - `.trellis/spec/`：开发规范 / code-spec
  - `.trellis/domain/`：对象语义、术语、真相源、概念关系
  - `.trellis/knowhow/`：经验、坑点、变更剧本、排障剧本、决策
  - `.trellis/views/`：AI 消费视图层，只做轻入口
- `refs/AI-Meeting/skills` 的第一层组织方式不是纯知识类型，而是按 skill/业务域/职责入口分区，例如：
  - `repo-map`
  - `business-dictionary`
  - 具体业务域 skill
  - runtime skill
  - change/debug playbook
- 每个 AI-Meeting skill 内部会混合承载多种知识材料：
  - object dictionary
  - 流程/状态机说明
  - gotchas
  - generated 索引
  - 变更或排障剧本
- 从内容 owner 角度看，AI-Meeting skill 里的正文大体可拆回三类：
  - `.trellis/spec/`：开发规范、实现约束、测试规则
  - `.trellis/domain/`：对象词典、术语、真相源、概念关系
  - `.trellis/knowhow/`：坑点、排障剧本、变更剧本、经验与决策
- 但 AI-Meeting 风格 skill 还额外包含两类不是这三层正文本身的东西：
  - 视图/路由层：何时使用、先看什么、关键入口、如何下钻
  - generated 事实层：API 索引、配置索引、workflow contract 索引等机械抽取内容
- 因此用户要的不是单纯照搬 `Maestro-Flow` 的知识分层，而是“AI-Meeting 的知识分区模型 + Maestro-Flow 的实现闭环”。
- `refs/maestro-flow` README 和实现代码显示，它的知识系统不是外挂模块，而是工作流内环的一部分：
  - 知识写入：`knowhow` / `spec` / `domain`
  - 知识索引：`WikiIndexer` + `MaestroGraph`
  - 知识检索：`search` / `load` / KG query
  - 知识注入：`SessionStart`、`UserPromptSubmit`、`PreToolUse:Agent` hooks
  - 注入决策同时依赖 agent 角色、prompt 关键词和 domain glossary
- `refs/maestro-flow` 的 workflow / hooks 是分层协作关系，不是“只改 workflow 就能完成知识注入”：
  - workflow 负责阶段、路由、下一步提示
  - hooks 负责在会话开始、用户提问、子代理执行前把知识真正送进上下文
- 当前 Trellis 本地架构文档也明确把这两层分开：
  - `.trellis/workflow.md` 的 `[workflow-state:*]` 只负责 per-turn breadcrumb
  - `session-start`、`workflow-state`、`sub-agent context` 都属于平台 hook / agent 注入层
- `refs/maestro-flow` 的若干已核实可复用模式包括：
  - 结构化知识条目协议，而不是纯散文
  - knowhow/reference 文档类型前缀
  - glossary 字段化 schema
  - append-only + duplicate-check + 摘要/长文分离
  - generated asset 机械抽取、带 freshness / hash 元信息

## Requirements

- 顶层方向按 `Maestro-Flow` 的知识管理体系来设计，目标是完整复刻其知识体系，而不是只借鉴局部协议。
- 复刻范围包含完整运行时闭环：知识写入、索引、统一搜索、角色/关键词/术语注入、增量同步与复盘回写。
- 复刻后的知识系统并入 Trellis，自身不能制造第二套与 `.trellis/spec/` 冲突的 authority owner。
- `.trellis/spec/` 继续做唯一开发规范 / code-spec owner；复刻出的其他 Maestro 知识层应作为 `.trellis/` 下的兄弟层存在，并在索引与注入阶段统一装配。
- 设计必须允许知识的“消费入口分区”和“底层存储类型”分离：
  - 对外给 AI 的入口更接近 AI-Meeting 的 skill 分区
  - 对内的写入、索引、注入、回写机制更接近 Maestro-Flow
- 设计必须区分“正文 owner 层”和“视图/索引层”，不能把两者混为一谈。
- 设计需要明确“是否存在独立 generated 层”和“是否完全不做 generated 内容”是两个不同决策。
- 路由/视图层采用 `.trellis/views/` 命名，避免与 `.agents/skills/` 的平台技能语义冲突。
- `.trellis/views/` 不应重新长成第二套正文知识库；长内容必须回到 `spec/domain/knowhow` 这三类正文 owner。
- 为当前仓库定义一套供 Trellis 使用的项目知识架构，而不是只做一组静态文档目录。
- 设计必须明确知识的分层边界，至少区分：
  - 工程规范
  - 业务知识
  - 结构化代码事实
  - 术语/词汇表
  - 临时任务知识与可沉淀长期知识
- 设计必须说明知识如何进入 Trellis 工作流，至少覆盖：
  - planning 前后
  - 实现前
  - 实现中
  - 检查和复盘后
- 设计必须区分“流程提示”与“知识注入”这两种机制：
  - `.trellis/workflow.md` 负责状态提示、路由、下一步建议
  - 平台 hooks 负责真实注入、同步、增量更新
- 设计必须说明知识如何被加载或注入，而不是只说明“放在哪里”。
- 设计必须明确哪些知识由人工维护，哪些知识可以或应该从代码机械生成。
- 设计必须说明和现有 `.trellis/spec/` 的边界，避免同一类事实有两个 owner。
- 设计必须明确处理 `Maestro-Flow` 中 `spec` 的语义与当前 Trellis `code-spec` 的冲突，避免出现“双 spec 根”或“双开发规范 owner”。
- 设计必须吸收 `Maestro-Flow` 已验证的优点，但不能不加筛选地把它整套搬进当前仓库。
- 实现策略必须明确：除 `spec/domain/knowhow/views` 的语义映射、目录落点和平台接线差异外，知识系统的运行时机制应尽可能对齐 `Maestro-Flow`，优先借用其已验证实现，只有在 Trellis 语义冲突或平台条件不同时才做最小适配。
- 设计需要兼容当前仓库的 Trellis 组织方式，尽量以本仓库现有 `.trellis/`、`.agents/skills/`、工作流和 task artifact 为基础。
- 设计要能支持未来再实现自动抽取、diff-check、预加载、失效重建等机制，但本轮优先先把架构边界定清。

## Acceptance Criteria

- [ ] 能清楚说明 Trellis 在本仓库里需要哪些“知识类型”，以及每种知识的 owner、存储位置和消费方式。
- [ ] 能清楚说明哪些 `Maestro-Flow` 机制值得复用，哪些只适合作为参考，哪些不该引入。
- [ ] 能清楚说明哪些实现点应直接对照 `Maestro-Flow` 借用，哪些地方只能做语义映射或最小适配。
- [ ] 能清楚说明 `.trellis/spec/` 与新知识层之间的边界，不再出现“规范、业务事实、代码事实”混放。
- [ ] 能清楚说明知识进入 Trellis 工作流的触发点和时机，而不是停留在目录设计。
- [ ] 能清楚说明人工知识和 generated knowledge 的分工边界。
- [ ] 当前规划任务至少产出可审阅的 `prd.md`；如果范围继续保持复杂，还需要补 `design.md` 与 `implement.md` 后才能进入实现。

## Out of Scope

- 本轮不直接实现自动抽取脚本。
- 本轮不直接实现 hook、索引器、检索器或注入器代码。
- 本轮不重写 Trellis 核心机制。
- 本轮不直接决定所有具体 skill 文档内容。

## Open Questions

- 当前无阻塞性 open question。下一步应进入 planning review；通过后再开始实现。
