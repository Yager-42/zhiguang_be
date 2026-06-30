# Parent Design

## Purpose

父任务只解决一件事：把“skills 文档体系”和“自动防腐化闭环”拆成可独立交付、可独立验证的子任务树，并与现有 Trellis `.trellis/spec/` 建立稳定边界。

## Architecture Decision

采用双层知识架构，不修改 Trellis 本身：

- `skills/`：唯一业务知识真相源
- `.trellis/spec/`：唯一工程规范真相源

回答的问题不同：

- `skills/` 回答“这是什么、改哪里、先看哪里”
- `.trellis/spec/` 回答“怎么改、怎么验、哪些模式禁止”

## Imported Patterns From maestro-flow

这次不是只借概念，而是借已经核实的实现模式：

1. 结构化条目协议
   - 参考 `maestro-flow` 的 `<spec-entry>` / `<knowhow-entry>`
   - 规划中为 skill 内索引条目保留字段：`category`、`keywords`、`date`、`ref`、`confidence`、`conflict-marker`、`conflict-note`
2. knowhow 分类法
   - 参考 `RCP-`、`TIP-`、`DCS-`、`AST-`、`REF-`、`DOC-`
   - 用于 skill 下 `references/` 的文档类型分层与文件命名
3. domain glossary schema
   - 参考 `id`、`canonical`、`aliases`、`definition`、`relationships`、`keywords`、`tier`、`status`、`source`
   - 用于 `zhiguang-business-dictionary`
4. 写入与防腐化行为
   - append-only
   - 标题去重
   - 长内容超过阈值后转 knowhow + ref
   - CJK-aware 术语匹配
   - session 级 dedup bridge
   - context budget 分级
   - health score / orphan / broken-link 报告



## Imported Patterns From comet

这次对 `comet` 借鉴的不是“五阶段工作流”本身，而是已经在源码里落地、且可直接增强当前知识体系的三类实现约束：

1. 渐进加载 reference
   - `comet` 把公共协议拆到 `reference/*.md`，主 `SKILL.md` 只保留硬约束和入口命令
   - 对应到本项目：`skills/` 主文档只保留路由、适用时机、核心约束；长说明、流程细节、案例、生成资产说明下沉到 `references/`
2. 压缩恢复检查点
   - `comet-design` 在 brainstorming 期间持续落盘 `brainstorm-summary.md`
   - 对应到本项目：在 Trellis planning / before-dev / diff-check 三个高上下文阶段，都需要可恢复的中间摘要或建议文本，而不是依赖对话历史
3. 哈希跳读
   - `comet-handoff.sh --hash-only` 允许只计算源文件 hash，不重建 handoff 包
   - 对应到本项目：skills diff-check 先比较 generated 资产 hash、skill 索引 hash、源代码输入 hash，未变化时跳过重复扫描和重复注入

## Trellis Integration Strategy

不修改 Trellis 主架构，只在现有流程上补 skills 读取和校验点：

### Phase 1

- 规划阶段声明本任务涉及的业务 skill
- 规划阶段声明是否会影响 glossary / generated assets / diff-check

### Phase 2

- `trellis-before-dev` 继续负责工程规范
- 在 `before-dev` 之前或内部增加 skills 路由读取
- 实际顺序是：
  1. 读任务 artifacts
  2. 路由并读取相关 `skills/`
  3. 读取 `.trellis/spec/`
  4. 实现
- 若相关 skill 存在长 reference，只按需渐进加载，不默认整包读完
- 若 planning / diff-check 已生成可恢复摘要，则优先消费摘要 + 必要引用，而不是重复全量回读

### Phase 2.2

- `trellis-check` 继续做质量校验
- 新增 skill diff-check 作为同一轮检查的一部分，而不是另起平行流程
- diff-check 先做 hash / freshness 判断，再决定是否重跑重扫描、重读 generated assets、重注入建议

### Phase 3

- `trellis-update-spec` 只负责工程规范沉淀
- skills 的业务知识更新通过独立 skills update 路径完成
- `finish-work` 前要确认当前任务没有遗留未处理的 skills 更新建议
- `finish-work` 若检测到未结算的知识建议文本，应优先消费建议文本，而不是重新依赖整段会话历史拼装


## Update Policy

对知识更新采用双轨：

- 机械性更新允许自动执行：generated 刷新、索引失效、rebuild、候选标记生成
- 语义性更新不允许静默自动写入：必须先产出建议文本，人工确认后才允许写 `skills/` 文件


## Token Strategy

在当前方案里，token 优化不是附属收益，而是知识体系能否长期可用的约束：

1. 主 skill 薄化
   - `SKILL.md` 只放入口、适用时机、硬约束、关键路由
   - 长内容拆到 `references/`
2. 渐进加载
   - 只加载命中的 skill 和命中的 reference 类型
   - 没命中的长文档不读
3. 恢复优先
   - 高上下文阶段必须有落盘检查点，恢复时先读检查点，再补必要源码 / reference
4. 哈希跳读
   - 代码输入、generated 资产、建议文本未变化时，不重复扫描和重复注入

## Why Parent/Child

当前目标包含三类不同交付物：

1. 知识结构层
2. 知识生成层
3. 防腐化闭环层

它们共享边界，但实现方式和验收方式不同，适合拆成子任务。

## Parent Responsibilities

- 保存总目标与边界
- 维护子任务地图
- 定义跨子任务依赖
- 定义最终集成验收口径
- 保证 `skills/` 与 `.trellis/spec/` 不漂移成重复系统
- 保证从 `maestro-flow` 借来的协议不会在子任务中各改一版
- 保证“字段协议”和“更新行为”一起被继承，而不是只复制字段名
- 保证 skills 体系挂进 Trellis 主流程，而不是变成旁路工具

## Child Boundaries

### A. Structure

聚焦静态知识层：

- skills 目录结构
- skill 命名
- 领域拆分
- README 路由
- 旧词典迁移
- 与 `.trellis/spec/` 的职责切分
- skill 内索引条目协议
- knowhow 分类协议
- business glossary schema
- append-only / duplicate-check / oversize-redirect 规则

### B. Generated Knowledge

聚焦可机械重建的知识：

- API / entrypoint 索引
- config / enum / workflow / ownership 索引
- 生成脚本与输出落点
- generated 文档元信息和刷新规则
- invalidate / rebuild 行为约定

### C. Diff-Check Loop

聚焦自动防腐化：

- 变更感知
- 影响 skill 识别
- 代码事实与知识资产的 diff-check
- 更新建议和审核门
- confidence / conflict / freshness 表达
- health score / orphan / broken-link 风险呈现
- 与 Trellis before-dev / check / finish-work 的挂点设计

## Dependency Rules

- A 先于 B/C 定义知识边界
- B 为 C 提供 generated 事实输入
- C 不重新发明 routing / ownership 模型，必须消费 A/B 的产物
- `.trellis/spec/` 不作为业务知识输入源；它只在工程约束层被保留
- 任何从 `maestro-flow` 借鉴的字段和协议，都先由 A 定义，再由 B/C 消费
- 任何从 `maestro-flow` 借鉴的行为规则，都要明确它属于 A/B/C 哪一层，不能笼统写“后续支持”
- 任何新增 skills 流程都必须挂到 Trellis 现有 Phase，不能要求研发额外记忆一套独立命令链

## Final Integration Criteria

父任务完成的条件不是“某个文件存在”，而是四件事同时成立：

1. 能说明项目业务知识应该放在哪个 skill
2. 能说明工程规范应该继续落在哪个 `.trellis/spec/`
3. 能从代码里重建部分高漂移知识
4. 能在变更发生时发现 skill 是否过期

同时增加四条：

5. skills 体系内部的条目协议、knowhow 分类、glossary schema 是统一的
6. diff-check 输出能表达“缺失”“过期”“冲突”“低可信度”四类风险，而不是只报文件缺失
7. skills 更新路径遵守 append-only 和 ref 模式，不把长知识直接堆进索引页
8. Trellis 的 before-dev / check / finish-work 三个时机都知道如何消费或校验 skills

## Stop Condition

本轮只完成任务树与规划文档，不进入 `task.py start`。













