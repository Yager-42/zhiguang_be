# Trellis Knowledge Architecture Design

## Goal

把 `Maestro-Flow` 的知识闭环并入当前仓库的 Trellis，但不复制它原有的
`.workflow/specs` 语义，也不再引入第二套开发规范 owner。新的体系要同时满足两件事：

- 对 AI 的消费入口接近 `refs/AI-Meeting/skills` 这种按职责/场景分区的体验。
- 对知识的存储、索引、注入、同步和回写，采用 `Maestro-Flow` 那种运行时闭环。

## Core Decision

这一版采用“四层正文/视图结构 + workflow/hook 双层运行时”。

静态知识层：

- `.trellis/spec/`
  - 唯一开发规范 owner。
  - 只放实现约束、跨层 contract、测试规则、代码规范、工程上的硬边界。
- `.trellis/domain/`
  - 业务对象词典、术语、真相源、概念关系、状态机边界。
  - 解决“这个名词到底是什么”。
- `.trellis/knowhow/`
  - 项目经验、坑点、排障剧本、变更剧本、操作剧本、稳定决策。
  - 解决“这类问题通常怎么做、哪里容易出错”。
- `.trellis/views/`
  - AI 消费视图层，不是正文 owner。
  - 只放入口、摘要、阅读顺序、使用时机、下钻路径。
  - 它的组织方式尽量模仿 `AI-Meeting` 的 skill 分区。

运行时层：

- `.trellis/workflow.md`
  - 负责阶段状态、路由提示、下一步建议。
  - 它告诉 AI 什么时候该读哪类知识，但不负责把正文知识真正注入上下文。
- platform hooks
  - 负责 SessionStart、每轮用户输入、以及将来需要的 agent/tool 前注入。
  - 它们才是真正执行知识装配、同步、增量注入和回写触发的地方。

这就是对 `Maestro-Flow` 的语义映射：

- `Maestro spec` -> `.trellis/spec/`
- `Maestro domain/glossary` -> `.trellis/domain/`
- `Maestro knowhow/wiki` -> `.trellis/knowhow/`
- `AI-Meeting skill shell` -> `.trellis/views/`
- `Maestro workflow + hooks` -> `.trellis/workflow.md` + `.codex/hooks/*.py`

## Implementation Principle

这次不是“参考一下 `Maestro-Flow` 的思路，然后自己再发明一版”，而是反过来：

- 运行时机制优先借用 `Maestro-Flow`
- Trellis 只在语义 owner、目录落点、平台 hook 接线这些地方做必要适配

也就是说，默认假设应当是：

- 能直接移植的机制，不重写
- 能轻改适配的机制，不重设计
- 只有和 `.trellis/spec/` 单 owner 原则、Codex inline 现实、以及 `.trellis/` 目录语义冲突时，才允许偏离 `Maestro-Flow`

这样做的目的不是偷懒，而是减少实现偏差。当前我们要的能力本来就已经在 `Maestro-Flow` 里被验证过一次，重新自创一版只会增加行为漂移和后续修正成本。

## Why This Split

冲突的根源不在“目录长得像不像”，而在 owner 会不会重叠。

如果把开发规范、业务事实、经验剧本都继续塞回 `.trellis/spec/`，那它会重新变成一个混合仓库，未来注入时很难精确控制，也会让 `spec` 失去“唯一开发规范 owner”的边界。

如果反过来把 `Maestro-Flow` 的 `spec` 原样搬进来，就会出现第二套规范 authority。这正是当前仓库已经明确要避免的事。

所以正确做法不是再造一个“兄弟 spec”，而是保住 `.trellis/spec/` 的 owner 身份，同时把对象语义和经验剧本拆出去，再用 `views` 把它们重新编排成 AI 更好消费的入口。

## Knowledge Ownership

### `.trellis/spec/`

这里记录的是“未来开发必须继续遵守的规则”，而不是解释业务名词。

典型内容：

- package/layer 开发规则
- API / schema / event / cache / env contract
- cross-layer invariant
- 测试与验证要求
- 重复出现的 bug 防线

不应放入这里的内容：

- 术语词典正文
- 业务对象层级说明
- 具体排障剧本正文
- 临时任务研究笔记

### `.trellis/domain/`

这里记录的是“项目世界里有哪些对象，以及它们彼此是什么关系”。

典型内容：

- object dictionary
- glossary
- source of truth 说明
- 对象层级、生命周期、状态机边界
- 同名术语在不同域的差异

它对应 `AI-Meeting` 里类似 `business-dictionary` 和各业务域 skill 中的对象语义部分。

### `.trellis/knowhow/`

这里记录的是“项目里已经踩过的坑，以及稳定可复用的操作套路”。

典型内容：

- debug playbook
- change playbook
- gotchas
- 常见失败模式
- 决策记录
- 回滚注意事项

它和 `spec` 的区别是：

- `spec` 说“必须怎么做”
- `knowhow` 说“通常怎么做更稳，以及为什么”

### `.trellis/views/`

这是面向 AI 的消费入口层，借鉴 `AI-Meeting/skills` 的形态，但不复刻成平台 skill。

每个 view 只回答五件事：

- 什么时候看我
- 先读哪几个正文文件
- 当前场景最容易混淆的边界是什么
- 如果还不够，下一步往哪层下钻
- 哪些内容只是摘要，正文 owner 在哪里

建议的 view 形态接近：

- `repo-map`
- `business-dictionary`
- `<domain>-domain`
- `change-playbook`
- `debug-playbook`
- `runtime`

但这些只是 `.trellis/views/` 下的入口文件，不是 `.agents/skills/`。

## AI-Meeting Mapping

`refs/AI-Meeting/skills` 的第一层是“场景入口”，不是“知识 owner”。这一点要保留。

对应映射如下：

- `xunzhi-business-dictionary`
  - view 放进 `.trellis/views/business-dictionary/`
  - 正文主要落 `.trellis/domain/`
- `xunzhi-change-playbook`
  - view 放进 `.trellis/views/change-playbook/`
  - 正文主要落 `.trellis/knowhow/`
- 各业务域 skill
  - view 放进 `.trellis/views/<domain>/`
  - 正文通常同时落 `.trellis/domain/` 与 `.trellis/knowhow/`
- `repo-map` / `runtime`
  - view 做入口
  - 正文可分别指向 `domain`、`knowhow`、以及必要的 `spec`

也就是说，`AI-Meeting` 的“skill 外观”被保留在 `views`，但正文 owner 重新收拢回 `spec/domain/knowhow`。

## Runtime Architecture

### 1. SessionStart

会话开始时只注入轻量总览，不注入大段正文。

目标：

- 让 AI 知道项目已经存在知识系统
- 告诉它有哪些知识分层和入口
- 给出当前 task / workflow / spec index 的基础上下文

在当前仓库里，这一层最自然的承接点就是已有的
[session-start.py](/Volumes/lexar/revive/zhiguang_be/.codex/hooks/session-start.py)。

后续设计上应扩展它的 injected context，加入：

- knowledge architecture summary
- available view indexes
- domain / knowhow / spec 的入口索引

但仍保持“轻总览，不塞正文”。

### 2. UserPromptSubmit

每轮用户输入时做两件事：

- workflow breadcrumb：告诉当前处于 planning / in_progress / no_task 哪个状态
- knowledge route hint：根据关键词、当前 task 状态、以及 view 入口，提示该读哪类知识

当前仓库已有
[inject-workflow-state.py](/Volumes/lexar/revive/zhiguang_be/.codex/hooks/inject-workflow-state.py)，
它现在只负责 workflow-state。后续应扩展成“workflow-state + knowledge route hint”，但不要把它写成整段正文注入器。

这一层更适合做：

- 关键词命中哪个 `view`
- 当前 phase 下默认先看哪一层
- 当前 task 若是 planning / execute / check，应优先读哪组索引

### 3. Pre-Execution / Pre-Agent Context

这一步是最接近 `Maestro-Flow spec-injector` 的位置。

当前 Codex inline 模式没有独立的 `PreToolUse:Agent` 链在跑，但实际开发前仍有两个可承接点：

- `trellis-before-dev`
- 将来若要补强，可新增 Codex/Claude 平台的 pre-agent or pre-tool hook

这里负责的不是“入口提示”，而是真正把正文知识带进实现上下文：

- 相关 `.trellis/spec/*`
- 相关 `.trellis/domain/*`
- 相关 `.trellis/knowhow/*`
- 当前 task 的 `prd.md / design.md / implement.md`

也就是说：

- `views` 负责告诉 AI “该读什么”
- `before-dev` / hook 负责确保 AI “真的读到这些”

### 4. Post-Change / Post-Task Capture

闭环不能只有读，没有写回。

回写时按 owner 分流：

- 新的稳定开发规则 -> `.trellis/spec/`
- 新术语、新对象边界 -> `.trellis/domain/`
- 新坑点、新剧本、新操作经验 -> `.trellis/knowhow/`

`.trellis/views/` 只在新增知识导致入口变化时同步更新摘要或阅读顺序，不收长正文。

## Workflow vs Hooks

这是本设计最重要的边界。

`.trellis/workflow.md` 负责：

- 当前阶段怎么走
- planning / execute / finish 时默认先看什么
- 哪些 view 或知识层在某阶段是优先入口

hooks 负责：

- 会话开始时给总览
- 每轮用户输入时给 breadcrumb 和路由提示
- 实现前把正文知识真正送进上下文
- 代码或任务结束后触发知识回写建议

如果只有 workflow，没有 hooks，得到的只是“提醒式知识系统”。
它可以提示模型去读，但不能复刻 `Maestro-Flow` 那种稳定、自动、按时机注入的运行时闭环。

因此在真正实现时，每个运行时触点都应该先找 `Maestro-Flow` 对应物，再决定怎么落到 Trellis：

- `SessionStart` 对照 `session-context` / `kg-auto-init`
- `UserPromptSubmit` 对照 `keyword-spec-injector` / `kg-sync` / `skill-context`
- pre-execution 注入对照 `spec-injector`
- 统一 hook 注册方式对照 `src/commands/hooks.ts`

只有当这些点和 Trellis 当前平台现实冲突时，才做最小偏移。

## Generated Knowledge Policy

`v1` 不建立独立的第五层 generated root。

这不等于彻底禁止 generated 内容，而是做两个约束：

- 当前版本不把 generated 内容提升为独立 owner 层
- generated 内容只能作为 `domain / knowhow / views` 的辅助材料或后续扩展位

原因：

- 现在先解决 owner 冲突和运行时注入问题，比先做抽取器更重要
- 当前仓库已有共识：generated knowledge 只适合承载机械事实，不适合伪装成人工解释

因此 v1 的策略是：

- 文档结构里先预留扩展方式
- 不在目录层面新增 `.trellis/generated/`
- 后续真要加，优先作为受控子目录或派生缓存，而不是新的知识 authority

## Suggested Directory Shape

建议的目标形态如下：

```text
.trellis/
  spec/
  domain/
    index.md
    glossary/
    objects/
    relationships/
  knowhow/
    index.md
    debug/
    change/
    decisions/
  views/
    index.md
    repo-map/
    business-dictionary/
    runtime/
    change-playbook/
    debug-playbook/
    <domain>/
```

其中：

- `domain/index.md` 与 `knowhow/index.md` 是正文入口索引
- `views/index.md` 是 AI 消费入口索引
- `views/*` 内每个入口文件都必须明确正文 owner 路径

## Rollout Shape

落地上分两阶段最稳。

第一阶段先建立结构与路由：

- 建目录
- 建 index
- 改 workflow
- 改 SessionStart / UserPromptSubmit hook
- 改 `trellis-before-dev` / `trellis-update-spec` 的知识分流说明

第二阶段再补强自动化：

- 关键词路由
- 术语匹配
- 按 task phase 的默认注入
- 回写建议
- 机械抽取与 freshness 校验

两个阶段都遵守同一个实现纪律：

- 先找 `Maestro-Flow` 对应文件
- 判断是“直接移植”、“轻改适配”还是“明确放弃”
- 只有明确说明放弃理由时，才允许自己新造机制

## Risks

最大的风险不是实现复杂，而是 owner 再次漂移。

具体有四个风险：

- 把 `views` 写胖，重新长成第二套正文库
- 把 `domain` 和 `knowhow` 混写，导致术语事实和经验规则纠缠
- 只改 workflow，不补 hook，最后只能得到半自动提示版
- 为了“像 AI-Meeting”又偷偷把 `.agents/skills/` 当成知识正文入口，重新制造平台语义冲突

## Success Condition

这套设计成功的标志不是“目录看起来像 Maestro”，而是：

- `.trellis/spec/` 仍然是唯一开发规范 owner
- AI 可以通过 `.trellis/views/` 像用 `AI-Meeting skill` 一样找到入口
- runtime 上通过 workflow + hooks 实现按时机加载和注入
- 新知识在任务结束后能按 owner 正确沉淀回 `spec / domain / knowhow`
