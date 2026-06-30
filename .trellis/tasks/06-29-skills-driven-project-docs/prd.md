# Skills 驱动项目文档与防腐化总任务

## Goal

以父任务方式统筹 `zhiguang_be` 主线 `com.tongji` 的 skills 文档体系建设，包括：

- 仓库内共享 `skills/` 业务知识层
- generated knowledge 与抽取脚本
- 完整自动防腐化闭环
- 与现有 `.trellis/spec/` 的稳定协同边界

父任务负责总需求、任务拆分、跨子任务验收和集成边界，不直接作为实现目标。

## Confirmed Facts

- 用户已确认范围仅覆盖 `src/main/java/com/tongji/*`
- 新体系作为仓库共享资产放在根目录 `skills/`
- 首版不裁剪，目标是全部 9 个 skills
- 首版采用“先搭骨架，再补内容”
- 旧 `CONTEXT.md` 后续迁移后直接删除
- 历史文档中对 `CONTEXT.md` 的坏链本轮不处理，作为显式质量债
- 本轮不补 `.trellis/spec/backend` 模板内容
- 用户希望最终目标不是只做 skills 文档结构，而是把完整自动防腐化也纳入
- 用户接受“双层架构”：`skills/` 作为业务知识真相源，`.trellis/spec/` 作为工程规范真相源，不修改 Trellis 架构本身
- `maestro-flow` 已核实可复用的不只是字段协议，还包括实际行为：append-only、标题去重、长内容重定向、CJK 术语匹配、session 级去重、context budget、索引失效与健康度评分
- 当前方案必须融入现有 Trellis 主流程，而不是另起一套并行流程
- `comet` 已核实可借鉴三类实现：reference 渐进加载、压缩恢复检查点、hash 跳读；这些增强知识加载与恢复，但不替换当前 `skills + Trellis` 双层架构

## Task Map

### Child A: `06-30-skills-knowledge-structure`

负责：

- `skills/` 目录
- 9 个 skills 骨架
- `README-zh.md`
- `CONTEXT.md` 有效知识迁移与删除
- 初版 `references/` 内容和目录边界
- 与 `.trellis/spec/` 的职责切分文档化
- 结构化知识协议骨架：skill 内条目协议、knowhow 分类、business glossary schema
- 写入规则：append-only、标题去重、长内容转 knowhow ref

### Child B: `06-30-skills-generated-knowledge`

负责：

- generated references
- 抽取脚本
- ownership / mapping 元数据
- 结构化知识抽取与刷新入口
- generated 资产元信息协议：`generated_at`、`source_paths`、`generator`、`refresh_trigger`
- generated 写入后的刷新 / 失效策略

### Child C: `06-30-skills-diff-check-loop`

负责：

- 变更感知
- skill vs code diff-check
- 更新建议生成
- 人工审核门
- pre-dev / pre-commit 挂点方案
- confidence / conflict / freshness 表达模型
- 关键词 / 术语匹配、session 级建议去重、知识健康报告
- 与 Trellis Phase 2 / Phase 3 的挂接契约

## Trellis Integration Contract

这套知识管理不是并行于 Trellis，而是嵌进现有 Phase：

1. Phase 1 / planning
   - `trellis-brainstorm` 继续负责需求澄清
   - 规划文档中声明本任务涉及哪些 skill / glossary / generated assets
2. Phase 2 / before-dev
   - `trellis-before-dev` 继续加载 `.trellis/spec/` 工程规范
   - 新增 skills 加载步骤：实现前先按任务路由读取对应 `skills/`
   - 顺序上是“先业务知识，再工程规范，再改代码”
3. Phase 2 / check
   - `trellis-check` 继续做代码质量校验
   - 同轮增加 skill diff-check：判断当前改动是否使 skill 知识过期
4. Phase 3 / update-spec
   - `trellis-update-spec` 仍然只更新 `.trellis/spec/` 的工程知识
   - skills 更新不混入 `.trellis/spec/`，走独立的 skills update 路径
5. Phase 3 / finish-work
   - 结束前检查是否存在未处理的 skills 更新建议
   - 若有未决建议，不允许把“知识更新”默默留到下次

## Cross-Child Requirements

- Child A 产出的 skill 边界和 ownership 约定必须被 Child B/C 复用，不能各自定义一套
- Child A 需要先定义可复用的知识协议，至少包括：结构化条目字段、knowhow 类型前缀、business glossary 字段、append-only 更新规则
- Child B 的 generated 资产必须能被 Child C 纳入 diff-check，而不是独立存在
- Child C 只能对 Child A/B 产生的知识资产做建议更新，不允许绕过 skill 边界任意写入
- `.trellis/spec/` 与 `skills/` 必须职责分离：前者只承载工程规范，后者只承载业务知识
- 三个子任务最终要形成一条完整链路：
  - skills 结构
  - 可抽取知识
  - 变更感知与防腐化闭环
- Trellis 流程中必须明确“读 skills、校验 skills、沉淀回 skills”的时机，不能只定义存储结构

## Acceptance Criteria

- [ ] 子任务边界清晰，互不重复且可独立验证
- [ ] 父任务明确记录跨子任务依赖和集成验收条件
- [ ] 每个子任务都有各自的 `prd.md`，并为复杂任务补齐 `design.md` 和 `implement.md`
- [ ] `skills/` 与 `.trellis/spec/` 的双层分工被明确写清
- [ ] `maestro-flow` 可直接复用的知识协议和行为已经落入规划，而不是停留在口头参考
- [ ] `comet` 可直接复用的上下文压缩与 token 优化机制已经落入规划，而不是只停留在 README 层
- [ ] skills 与 Trellis 的集成时机已经写清，不是“后面再想”
- [ ] 本轮仍停留在 Trellis Phase 1，不启动任何父/子任务实现

## Out of Scope

- 本轮不启动父任务或任何子任务
- 本轮不直接创建真实 `skills/` 文件
- 本轮不落地 diff-check 或自动回写代码
- 本轮不改造 Trellis 机制本身







