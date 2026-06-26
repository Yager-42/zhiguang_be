# Harden Trellis Aegis Execution Governance

## Goal

改造当前 zhiguang_be 项目里的 Trellis + Aegis 集成工作流，消除桥接层的模糊授权，让 Aegis 执行层变成“可控、可停顿、可审计、强上下文继承”的执行机制，而不是让 LLM 自由发挥。

## Confirmed Facts

- 当前项目的主干流程由 `zhiguang-trellis-flow` 规定为：
  - `trellis-start`
  - `trellis-brainstorm`
  - `trellis-before-dev`
  - `aegis task execution`
  - `trellis-check`
  - `trellis-update-spec`
  - `trellis-finish-work`
- 当前桥接入口是 `.agents/skills/trellis-aegis-execution/SKILL.md`。
- 当前桥接已经要求：
  - 读取 active Trellis task
  - 识别 `implement.md` 当前 step
  - 在可执行时创建 lower-layer Aegis slice refinement
  - 然后再路由到 Aegis execution
- 当前桥接把 Aegis execution artifact 写成了软约束：
  - `Aegis plans may be created on Aegis-native timing and thresholds`
  - `Aegis work records may be created on Aegis-native timing and thresholds`
- 当前项目路由 `zhiguang-trellis-flow` 也把 `docs/aegis/plans/` 与 `docs/aegis/work/` 的生成时机写成了 Aegis 自己决定。
- 当前 Aegis 通用规则允许：
  - 用自然语言 trace 替代结构化 trace
  - 在某些路径下不生成 durable plan/work 文件
  - 只在需要时显式可见
- 当前桥接虽然要求读取：
  - current task path
  - `prd.md`
  - `design.md`
  - `implement.md`
  - 已由 `trellis-before-dev` 载入的 `.trellis/spec/`
  但没有写死：
  - 每个 slice 开始前必须重新列出这些 authority refs
  - 每个 slice 开始前必须做代码事实收集
  - 每个 slice 的 plan 必须基于 CodeGraph 或等价事实证据
- 当前桥接没有写死：
  - slice plan 必须持久化到哪里
  - slice plan 产出后必须暂停等用户确认
  - 没有 slice plan / 没有 receipt 时禁止声称 Aegis 已接管
- 上一个实际任务（notification-center）已经暴露出这个缺口：
  - 规则上应进入 Aegis execution
  - 实际没有 `docs/aegis/plans/...` 或 `docs/aegis/work/...` 留痕
  - 说明当前工作流对 LLM 的选择空间过大
- Trellis 当前已经有自己的权威文档家族：
  - task 级：`prd.md`、`design.md`、`implement.md`
  - 项目级：`.trellis/spec/`
  - 会话级：`.trellis/workspace/`
- Aegis 原版完整工作区文档家族包括：
  - `docs/aegis/specs/`
  - `docs/aegis/plans/`
  - `docs/aegis/work/`
  - `docs/aegis/baseline/`
  - `docs/aegis/adr/`
  - 以及 `README.md`、`INDEX.md`、`BASELINE-GOVERNANCE.md`
- 在当前项目的 Trellis 集成设计里，已经明确把 Aegis durable artifact 限缩为：
  - `docs/aegis/plans/`
  - `docs/aegis/work/`
  并明确默认不让 Aegis 创建：
  - `docs/aegis/specs/`
  - `docs/aegis/baseline/`
  - `docs/aegis/adr/`
- Trellis 与 Aegis 的文档功能重叠点至少包括：
  - Trellis `prd.md` / `design.md` 与 Aegis `docs/aegis/specs/`
  - Trellis `implement.md` 与 Aegis `docs/aegis/plans/`
  - Trellis `.trellis/spec/` 与 Aegis `docs/aegis/baseline/` 在“项目事实/当前约束/架构快照”上存在潜在重叠
  - Trellis `design.md` / `.trellis/spec/` 与 Aegis `docs/aegis/adr/` 在“持久化架构决策”上存在潜在重叠
- `docs/aegis/work/` 与 Trellis 的关系不是完全同型冲突：
  - `.trellis/workspace/` 更像开发者 journal / session record
  - `docs/aegis/work/` 更像 task/slice 级执行证据和 checkpoint

## Requirements

- 必须收紧 Trellis 与 Aegis 桥接层里的模糊描述，减少 LLM 自由裁量空间。
- 必须明确 Aegis execution 在什么条件下可以开始，什么条件下必须停回规划。
- 必须定义每个 slice 的最小必备工件与证据。
- 必须让每个 slice 在真正执行前先形成更细粒度的 plan。
- 必须支持“slice plan 先给用户看，用户点头后才继续执行”。
- 必须对每个 slice 都执行这一停顿确认流程，不允许按复杂度做例外分支。
- 必须要求用户给出显式批准语义后才能继续执行 slice，不允许默认继续，也不允许 agent 自行推断“用户应该同意”。
- 必须保证每个 slice 的 plan 继承并显式引用：
  - 当前 Trellis task
  - `prd.md`
  - `design.md`
  - `implement.md`
  - `.trellis/spec/` 中相关规范
- 必须保证每个 slice 的 plan 在实现前先做代码事实收集，而不是脱离代码库上下文独立规划。
- 必须优先使用 CodeGraph 获取与 slice 相关的代码事实；只有在 CodeGraph 不足时才退回普通文件搜索/读取。
- 必须把每个 slice 的前置上下文读取顺序写成硬顺序：
  1. current task path
  2. `prd.md`
  3. `design.md`
  4. `implement.md`
  5. `trellis-before-dev` 已加载的相关 `.trellis/spec/`
  6. 当前 slice 相关的 CodeGraph 代码事实
  7. 只有在 CodeGraph 不足时，才允许补充最小必要的文件搜索/读取
- 必须明确哪些工作流文件、技能文件、规则文本是 authority source。
- 必须明确哪些留痕是强制的，哪些只是可选增强。
- 必须定义 closeout / verification 阶段对 Aegis 接管声明的约束。
- 必须定义 slice plan 被用户否决后的回退规则：
  - 只影响 slice 内部做法时，留在 Aegis slice 规划层重写当前 slice plan
  - 一旦影响 scope / acceptance / top-level implement step / design boundary，必须退回 Trellis planning
- 必须在本项目里禁用 Aegis 的 `Planless Slice Lane`，不允许用纯会话 `Slice Card` 替代落盘 slice plan。
- 必须把 `alibaba-java-coding-guidelines-skill` 从“优先增强器”提升为本项目 Java / Spring / MyBatis / Maven / MySQL / SQL / DTO / mapper / database 相关改动的强制介入 skill。
- 必须把 `code-review-skill`（即你口中的 `codexreview`）从 check gate 中的“可选增强器”提升为本项目代码改动进入 `trellis-check` 时的强制介入 review skill。
- 必须从本项目本地路由里移除 `to-issues` 作为默认 planning enhancer，不再让它承担当前项目的任务拆分默认职责。
- 必须保持当前项目里的文档 owner 单一化：
  - Trellis 继续拥有 `prd.md`、`design.md`、`implement.md`、`.trellis/spec/`
  - Aegis 默认只保留 `docs/aegis/plans/` 与 `docs/aegis/work/`
  - Aegis `specs/`、`baseline/`、`adr/` 在本项目默认继续禁用
- 必须明确 Aegis durable artifact 的粒度：
  - `plans/` 为每个 slice 单独落一份 plan
  - `work/` 为每个 task 维护一个 work 目录，并按 slice 追加 checkpoint / evidence

## Acceptance Criteria

- [ ] `trellis-aegis-execution` 或其等价 authority 规则中，slice refinement、停顿确认、上下文继承、代码事实收集的要求变成明确的硬约束，而不是模糊建议。
- [ ] 工作流里明确规定：每个 slice 开始前必须先产出可审计的 slice plan。
- [ ] 工作流里明确规定：slice plan 产出后必须暂停，等待用户确认后才能继续执行 slice。
- [ ] 工作流里明确规定：这个暂停确认对每个 slice 都强制生效，不存在“简单 slice 自动跳过”的例外。
- [ ] 工作流里明确规定：只有收到用户显式批准语义，slice 才能继续执行；沉默、模糊表述、agent 推断都不算批准。
- [ ] slice plan 模板里明确包含：
  - parent implement step
  - slice goal / non-goals
  - authority refs
  - relevant specs
  - code facts / codegraph evidence
  - files / owners
  - edit order
  - verification order
  - stop condition
- [ ] 工作流里明确规定：没有 slice plan 或没有用户确认，不得进入 slice 实现。
- [ ] 工作流里明确规定：没有 Aegis receipt / plan / work 证据，不得在 closeout 中声称 Aegis 已接管执行。
- [ ] 工作流里明确规定：用户否决 slice plan 后，局部否决只重写 slice；涉及顶层范围、验收、step、设计边界的否决必须退回 Trellis planning。
- [ ] 工作流里明确禁用 `Planless Slice Lane`，不允许会话内 `Slice Card` 替代落盘的 `docs/aegis/plans/...`。
- [ ] 工作流里明确规定：Java / Spring / MyBatis / Maven / MySQL / SQL / DTO / mapper / database 相关改动，`alibaba-java-coding-guidelines-skill` 强制介入。
- [ ] 工作流里明确规定：代码改动进入 `trellis-check` 时，`code-review-skill` 强制介入，不再只是推荐增强器。
- [ ] 项目本地路由中不再把 `to-issues` 作为默认 planning enhancer 暴露给当前工作流。
- [ ] 相关 Trellis / Aegis / 项目本地技能之间的职责边界被重新写清，不再出现“桥接层要求细化，但留痕层允许不落盘”的冲突。
- [ ] 新规则仍然保留 `implement.md` 作为唯一 top-level authority，不允许 Aegis 擅自扩 scope。
- [ ] 新规则明确维持文档 owner 单一化：Trellis 不与 Aegis 在 `specs / baseline / adr` 上重叠，Aegis 默认只保留 `plans / work`。
- [ ] 新规则明确文档粒度：`plans/` 一 slice 一文件，`work/` 一 task 一目录，按 slice 追加执行证据。
- [ ] 新规则把 slice 前置上下文读取顺序写成强制步骤，并要求 slice plan 显式列出 authority refs、spec refs、codegraph facts。

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.

## Out Of Scope

- 直接修改业务代码功能
- 重写整个 Trellis 框架
- 让 Aegis 接管 Trellis 的 PRD / design / implement authority
- 改造与本项目无关的全局宿主平台行为

## Decisions Made

- 每个 slice 都必须先产出 plan，并暂停等待用户确认后才能继续执行。
- 在本项目里，Aegis 默认只保留 `docs/aegis/plans/` 与 `docs/aegis/work/`；`specs/`、`baseline/`、`adr/` 继续禁用。
- `docs/aegis/plans/` 按 slice 落单独 plan；`docs/aegis/work/` 按 task 保留一个 work 目录，在里面按 slice 追加执行证据。
- 每个 slice 出 plan 前，必须按固定顺序读取 task / PRD / design / implement / relevant spec / CodeGraph facts；CodeGraph 不足时才允许最小补充搜索。
- 每个 slice 只有在用户给出显式批准语义后才能继续执行；不允许默认继续，也不允许 agent 推断同意。
- 用户否决 slice plan 时，若只是 slice 内部实现方案问题，则由 Aegis 重写当前 slice plan；若否决触及 scope、acceptance、top-level step、design boundary，则退回 Trellis planning。
- 在本项目里禁用 `Planless Slice Lane`；每个 slice 都必须有落盘的 `docs/aegis/plans/...`，不能只在会话里给 `Slice Card`。
- 在本项目里，`alibaba-java-coding-guidelines-skill` 对 Java / Spring / MyBatis / Maven / MySQL / SQL / DTO / mapper / database 相关改动强制介入。
- 在本项目里，`code-review-skill`（`codexreview`）对进入 `trellis-check` 的代码改动强制介入。
- 在本项目里移除 `to-issues` 作为默认 planning enhancer；当前任务拆分职责不再由它承担。
