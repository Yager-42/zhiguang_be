# Implement Trellis Knowledge Architecture

## Goal

在当前仓库里，把已批准的知识架构真正落成一套可运行的 Trellis 本地机制：

- 目录与 owner 分层清楚
- `AI-Meeting` 风格入口由 `.trellis/views/` 提供
- workflow 负责路由
- hooks 负责注入
- 任务结束后能按 owner 提示回写

## Preconditions

- 用户已批准当前 planning artifacts。
- 当前任务保持 `planning`，直到 `prd.md`、`design.md`、`implement.md` 审核通过。
- Codex 仍采用 inline 模式，主会话直接实现和检查。

## Implementation Discipline

每个实现点都先对照 `Maestro-Flow` 的对应文件，再决定采用哪种动作：

- 直接移植
- 轻改适配
- 明确放弃

默认顺序是前两者优先，最后才是第三者。不能跳过对照步骤，直接在 Trellis 里另起一套自定义机制。

当前应优先对照的 `Maestro-Flow` 文件包括：

- `refs/maestro-flow/src/commands/hooks.ts`
- `refs/maestro-flow/src/hooks/session-context.ts`
- `refs/maestro-flow/src/hooks/spec-injector.ts`
- `refs/maestro-flow/src/hooks/keyword-spec-injector.ts`
- `refs/maestro-flow/src/hooks/kg-sync-hook.ts`
- `refs/maestro-flow/src/commands/load.ts`

允许偏离的场景只有三类：

- Trellis 的 `.trellis/spec/` 单 owner 语义要求不同
- 当前平台实际只有 Codex inline / 本地 hooks 这类接线条件
- `AI-Meeting` 风格入口被映射到 `.trellis/views/`，而不是平台 skill 根

## Execution State

- Active step: none
- Completed steps: none
- Next candidate: Task 1
- Notes: 本计划覆盖的是“把知识系统并入 Trellis”的第一版实现，不包含完整抽取器或图索引器。

## Task 1: Establish Knowledge Roots

Files:

- `.trellis/domain/`
- `.trellis/knowhow/`
- `.trellis/views/`
- corresponding `index.md`

Work:

- 先对照 `Maestro-Flow` 的知识根与入口组织方式，再建立 Trellis 对应根。
- 创建 `domain / knowhow / views` 三个知识根。
- 为每个根建立清晰的 index。
- 在 index 里写明 owner 边界，避免和 `.trellis/spec/` 重叠。
- 先放最小骨架，不在 v1 填充大量正文。

Validation:

```bash
find .trellis -maxdepth 2 \( -path ".trellis/domain" -o -path ".trellis/knowhow" -o -path ".trellis/views" \) -type d
sed -n '1,220p' .trellis/domain/index.md
sed -n '1,220p' .trellis/knowhow/index.md
sed -n '1,220p' .trellis/views/index.md
```

Expected:

- 三个目录存在
- 每个 index 都明确说明 owner 与非 owner 边界

## Task 2: Build AI-Meeting-Style View Entrypoints

Files:

- `.trellis/views/business-dictionary/`
- `.trellis/views/change-playbook/`
- `.trellis/views/debug-playbook/`
- `.trellis/views/repo-map/`
- `.trellis/views/runtime/`
- optional domain-specific view dirs

Work:

- 先对照 `AI-Meeting` skill 入口和 `Maestro-Flow` 的知识装配方式，再决定 view 目录形态。
- 按 `AI-Meeting` 的入口模型建立 view skeleton。
- 每个 view 只写：
  - 何时使用
  - 先看哪些正文
  - 常见误区
  - 下一步下钻去哪里
- 禁止在 view 里复制正文长内容。

Validation:

```bash
find .trellis/views -maxdepth 2 -type f | sort
rg -n "何时使用|先看|正文 owner|下钻|不要把" .trellis/views
```

Expected:

- 入口结构存在
- view 文本是摘要/导航，不是正文仓库

## Task 3: Extend SessionStart Knowledge Overview

Files:

- [.codex/hooks.json](/Volumes/lexar/revive/zhiguang_be/.codex/hooks.json)
- [.codex/hooks/session-start.py](/Volumes/lexar/revive/zhiguang_be/.codex/hooks/session-start.py)

Work:

- 先对照 `refs/maestro-flow/src/hooks/session-context.ts` 与相关 SessionStart hook 定义。
- 在 Codex 注册 `SessionStart` hook。
- 扩展 `session-start.py`，让它除了 workflow、task、spec index 外，也注入：
  - knowledge architecture summary
  - `domain / knowhow / views` 的入口索引
  - 当前项目有哪些 knowledge roots
- 保持轻量，不在 SessionStart 注入大段正文。

Validation:

```bash
sed -n '1,220p' .codex/hooks.json
rg -n "SessionStart|domain|knowhow|views|knowledge" .codex/hooks/session-start.py
```

Expected:

- `.codex/hooks.json` 注册了 `SessionStart`
- session-start injected context 能提到新知识层

## Task 4: Extend Per-Turn Workflow Hook Into Route Hint

Files:

- [.codex/hooks/inject-workflow-state.py](/Volumes/lexar/revive/zhiguang_be/.codex/hooks/inject-workflow-state.py)
- [.trellis/workflow.md](/Volumes/lexar/revive/zhiguang_be/.trellis/workflow.md)

Work:

- 先对照 `refs/maestro-flow/src/hooks/keyword-spec-injector.ts`、`kg-sync-hook.ts` 和 `skill-context` 相关触点。
- 保留现有 workflow-state breadcrumb。
- 在 per-turn hook 中增加 knowledge route hint：
  - planning 时优先看哪些 view / spec
  - implementation 前优先看哪些 owner 层
  - debug/change 类请求默认命中哪些 view
- 这一步只做“提示 + 索引路径”，不要把它做成正文大注入器。

Validation:

```bash
rg -n "workflow-state|route|view|domain|knowhow" .codex/hooks/inject-workflow-state.py
rg -n "views|domain|knowhow|knowledge" .trellis/workflow.md
```

Expected:

- breadcrumb 仍然工作
- 每轮提示开始出现知识路由信息

## Task 5: Teach Trellis Workflow About Knowledge Consumption

Files:

- [.trellis/workflow.md](/Volumes/lexar/revive/zhiguang_be/.trellis/workflow.md)

Work:

- 在 Phase 1/2/3 中增加知识层消费规则：
  - planning 先看 `views`，必要时下钻 `domain/knowhow/spec`
  - execute 前必须加载相关 `spec`，需要对象语义时读 `domain`，需要剧本时读 `knowhow`
  - finish 阶段决定知识回写去向
- 保持 workflow 是“路由和顺序提示”，不要把正文写进 workflow。

Validation:

```bash
rg -n "views|domain|knowhow|spec|回写|route" .trellis/workflow.md
```

Expected:

- workflow 清楚区分路由提示与正文 owner

## Task 6: Update Before-Dev and Spec-Capture Skills

Files:

- `.agents/skills/trellis-before-dev/SKILL.md`
- `.agents/skills/trellis-update-spec/SKILL.md`
- optional `.agents/skills/trellis-check/SKILL.md`

Work:

- 先对照 `refs/maestro-flow/src/hooks/spec-injector.ts` 的职责边界。
- `trellis-before-dev` 增加知识分层读取顺序：
  - 先 `views`
  - 再按需进 `spec/domain/knowhow`
- `trellis-update-spec` 增加回写分流规则：
  - 新规则去 `spec`
  - 新术语去 `domain`
  - 新经验去 `knowhow`
- 必要时在 `trellis-check` 里增加 owner 漂移检查。

Validation:

```bash
rg -n "views|domain|knowhow|spec|owner" .agents/skills/trellis-before-dev/SKILL.md .agents/skills/trellis-update-spec/SKILL.md .agents/skills/trellis-check/SKILL.md
```

Expected:

- 前置读取顺序和回写去向都被明确写死

## Task 7: Add Minimal Knowledge Loader Helpers

Files:

- `.trellis/scripts/` under a new helper if needed
- or extend existing `.trellis/scripts/get_context.py` related helpers

Work:

- 提供最小可复用的知识索引读取接口。
- 至少支持：
  - 列出 view 入口
  - 列出 domain / knowhow index
  - 给 hooks 提供稳定路径发现，而不是在脚本里写死大量路径

Validation:

```bash
python3 ./.trellis/scripts/get_context.py --help
python3 ./.trellis/scripts/get_context.py
```

Expected:

- 现有脚本不被破坏
- 新 helper 若存在，路径发现稳定

## Task 8: Add Post-Task Knowledge Capture Guidance

Files:

- `.trellis/workflow.md`
- `.agents/skills/trellis-update-spec/SKILL.md`
- task artifact templates if needed

Work:

- 在 finish 流程里增加“本次新增知识属于哪一层”的判断提示。
- 让任务完成后的知识沉淀不再默认都回到 `spec`。
- 仍然禁止把一次性执行过程写成长期知识。

Validation:

```bash
rg -n "domain|knowhow|spec|回写|长期知识|一次性" .trellis/workflow.md .agents/skills/trellis-update-spec/SKILL.md
```

Expected:

- finish 阶段已经有清楚的知识分流规则

## Deferred Work

以下内容明确延后，不放进 v1 首批实现：

- 独立 generated 第五层
- 自动 glossary 匹配
- 类似 `MaestroGraph` 的图索引
- 自动 duplicate-check
- freshness/hash 校验
- 全平台 hook 对齐

## Review Gate

开始真正实现前，确认以下几点：

- `.trellis/spec/` 仍是唯一开发规范 owner
- `.trellis/views/` 没有长成第二套正文知识库
- workflow 只负责路由提示，不承担正文注入
- hooks 至少在 Codex 本地真正承担 SessionStart 和 per-turn 注入职责
- 每个实现任务都已经标出对应的 `Maestro-Flow` 参照文件，并声明是“直接移植”、“轻改适配”还是“明确放弃”

## Final Validation

```bash
python3 ./.trellis/scripts/task.py validate .trellis/tasks/06-30-trellis-knowledge-architecture
rg -n "domain|knowhow|views" .trellis/workflow.md .codex/hooks .agents/skills
git diff -- .trellis .codex .agents/skills
```

Expected:

- task artifacts 校验通过
- active workflow / hook / skill 文本都能看出新的知识分层与触发链
- 没有新的双 owner 描述
