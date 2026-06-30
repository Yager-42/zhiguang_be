# Design - Skills Knowledge Gap Closure

## Purpose

本任务在不推翻现有主链的前提下，把 `skills/` 知识体系补到“可检索、可治理、可持续增量维护”的状态。

当前已有能力：

- skill 路由
- generated asset 刷新
- diff-check
- 建议文本落盘
- 人工确认后 apply
- Trellis `before-dev / check / finish-work` 挂接

本轮新增能力覆盖九类缺口，并继续保持人工确认写回边界。

## Existing Baseline

### 已有能力

1. 结构层
   - 9 个 skills 已建立
   - `skills/README-zh.md` 已定义统一阅读入口
   - 词典已有结构化 schema 示例
2. 生成层
   - `repo-map.api-index`
   - `repo-map.skill-routing-map`
   - `common-runtime.config-index`
   - `platform-domain.enum-index`
3. 防腐化主链
   - `route`
   - `diff-check`
   - `status`
   - `apply`
   - `context-recovery.md`
   - `state.json`
4. Trellis 挂点
   - `trellis-before-dev`
   - `trellis-check`
   - `trellis-finish-work`

### 当前缺口

1. 结构化协议只停留在 README 声明，没有系统落地到 skill 文档与写入器。
2. append-only / duplicate-check / oversize-redirect 没有自动约束。
3. invalidate / rebuild 只有 freshness 判断，没有真正的索引失效与重建层。
4. 缺少 wiki / index / backlink / graph 层。
5. 缺少 CJK-aware 术语 / 关键词 / 领域语义匹配。
6. session 级建议去重只做了 scan 复用，没有语义去重桥。
7. 缺少 health report / orphan / broken-link / health score。
8. 缺少 context budget 分级。
9. 缺少 workflow generated asset。

## Architecture Decision

继续采用双层知识架构，不修改真相源边界：

- `skills/`：业务知识真相源
- `.trellis/spec/`：工程规范真相源

本轮只增强 `skills/` 的结构、索引、匹配、治理与 generated 事实层；不把业务知识倒灌进 `.trellis/spec/`。

## Single-Task Strategy

虽然范围很大，但本轮按一个大任务执行，不拆 parent/child。原因是这次补的是同一套知识系统的内部缺层，而不是多个独立产品能力。为了避免执行时再次漂移，设计上把所有缺口收敛为同一个运行时骨架。

## Owner Surfaces

本轮实现只允许改这些 owner surface：

- `scripts/skills/skilllib.py`
- `scripts/skills/skills_guard.py`
- `scripts/skills/refresh_generated_knowledge.py`
- `scripts/skills/skill_registry.yaml`
- 新增 `scripts/skills/skill_indexer.py`
- `skills/README-zh.md`
- 各 `skills/zhiguang-*/SKILL.md`
- 各 `skills/zhiguang-*/references/*.md`
- `.agents/skills/trellis-before-dev/SKILL.md`
- `.agents/skills/trellis-check/SKILL.md`
- `.agents/skills/trellis-finish-work/SKILL.md`
- `.trellis/spec/backend/skills-knowledge-workflow.md`

不新建第二套知识流程入口，不把同样的规则再复制进别的 owner surface。

## Target Architecture

### 1. Knowledge Document Layer

补齐 skill 文档协议与写入约束。

目标：

- skill 主文档继续薄化
- 长内容继续下沉 `references/`
- 新增或更新知识时统一走结构化条目协议
- 程序只操作可识别条目区块，不覆写整页正文

新增约束：

- 所有可被程序写入的知识页面，都必须能识别结构化条目边界
- 条目字段统一至少支持：
  - `category`
  - `keywords`
  - `date`
  - `ref`
  - `confidence`
  - `conflict-marker`
  - `conflict-note`
- 词典条目继续使用：
  - `id`
  - `canonical`
  - `aliases`
  - `definition`
  - `relationships`
  - `keywords`
  - `tier`
  - `status`
  - `source`

### 2. Wiki / Index Layer

新增一个轻量 wiki/index 层，但不引入外部数据库。

目标：

- 从 `skills/` 构建统一索引
- 记录页面、条目、术语、引用、backlink、generated asset 的关系
- 为 before-dev 路由和 diff-check 提供统一检索输入

建议实现形态：

- `scripts/skills/skill_indexer.py`
- 输出到 `skills/.index/`
- 至少包含：
  - `pages.json`
  - `entries.json`
  - `terms.json`
  - `backlinks.json`
  - `health.json`
  - `index-state.json`

索引来源：

- `skills/*/SKILL.md`
- `skills/*/references/*.md`
- generated assets
- `skill_registry.yaml`

### 3. Matching Layer

新增语义匹配层，替代当前仅靠包前缀 / 文件形态的粗路由。

目标：

- 支持中文术语、英文词边界、alias、keyword 的匹配
- 支持 canonical -> alias -> keyword 的匹配顺序
- 支持 1 层 relationship propagation
- 支持 stop words 与注入上限

建议输入：

- 词典 schema
- 结构化条目 `keywords`
- routing map
- changed files
- diff 文本里的类名、状态名、配置 key、接口 path

建议输出：

- `matched_skills`
- `matched_terms`
- `match_reasons`
- `confidence`

### 4. Generated Knowledge Layer

扩展现有 generated asset 体系，继续保持“只产代码事实”。

新增资产方向：

- workflow / state index
- path ownership index（若现有 routing map 不足）

workflow asset 的事实来源必须是源码已存在的稳定结构，不允许臆造状态机。当前可从下列来源抽取：

- `PromotionBidStatus`
- `PromotionCampaignStatus`
- `PromotionAuctionWindowStatus`
- `WalletEscrowStatus`
- `ReconciliationTaskStatus`
- `KnowPost` / `PublishAttempt` 的 `status` 字段与 publish status 入口

生成结果仍落在对应 skill 的 `references/generated-*.md`，并补充索引失效信息。

### 5. Invalidate / Rebuild Layer

在 freshness 之上补真正的索引失效与重建行为。

目标：

- generated asset 更新后，相关 skill 索引自动失效
- skill 文档变更后，wiki/index 自动失效
- before-dev / diff-check 读取前，如果发现索引已失效则自动重建

### 6. Governance Layer

新增知识健康治理输出。

最少要覆盖：

- broken links
- orphan pages / orphan entries
- missing generated asset reference
- low confidence / contested entries
- stale references
- health score

health report 既要给人看，也要给 `skills_guard.py status` 消费。

### 7. Session Dedup Layer

在当前 scan-hash 复用之外，补 session 级语义去重桥。

目标：

- 同一 session 内已经生成过的建议，不因局部扫描波动重复提示
- 去重粒度至少覆盖：
  - suggestion id
  - matched term / keyword
  - target file

状态建议继续落在 task 下的 `skills-review/state.json`，但补充去重字段。

### 8. Context Budget Layer

补上不同阶段读取 skills 的预算分级，减少无效 token 开销。

建议预算层级：

- `L0`：只读 `skills/README-zh.md` + 命中 skill 的 `SKILL.md` 头部边界
- `L1`：加读匹配到的结构化条目摘要与 generated asset 摘要
- `L2`：按需展开命中的 `references/` 细节页
- `L3`：仅在 diff-check / 复杂跨域场景下读完整 skill 长文档

before-dev 与 diff-check 都优先走低预算层级，再按命中结果逐级提升。

### 9. Workflow Generated Asset Layer

对 workflow / status 事实形成稳定 generated 文档，而不是散落在代码与词典里。

首版只抽：

- 状态名 / 常量名
- owning file
- 直接暴露的状态接口或入口
- 与之对应的 skill owner

不自动推导隐式状态迁移图。

## Artifact Contracts

### `skills/.index/pages.json`

至少包含：

- `page_id`
- `skill`
- `path`
- `page_type`
- `title`
- `outbound_refs[]`

### `skills/.index/entries.json`

至少包含：

- `entry_id`
- `skill`
- `page_id`
- `section_title`
- `category`
- `keywords[]`
- `ref[]`
- `confidence`
- `conflict_marker`

### `skills/.index/terms.json`

至少包含：

- `term_id`
- `canonical`
- `aliases[]`
- `keywords[]`
- `owner_skill`
- `source_entry_ids[]`
- `relationships[]`

### `skills/.index/backlinks.json`

至少包含：

- `source_path`
- `target_path`
- `relation_type`
- `target_kind`

### `skills/.index/health.json`

至少包含：

- `generated_at`
- `skills[]`
- `blockers[]`
- `high_risks[]`
- `warnings[]`
- `score`

### `<task>/skills-review/state.json`

在现有字段基础上至少补：

- `seen_match_keys[]`
- `emitted_suggestion_keys[]`
- `health_blockers[]`
- `health_high_pending[]`
- `context_budget_level`

## Gate Severity Mapping

必须把健康度与建议映射回 Trellis gate，而不是只生成报告。

- `hard_errors`
  - 缺失 `SKILL.md`
  - 缺失 referenced file
  - 缺失 required generated asset
  - index rebuild 失败
- `health_blockers`
  - broken links
  - required backlink 缺失
  - skill 索引损坏
- `high_priority_pending`
  - 高优先级知识更新建议未处理
- `health_high_pending`
  - impacted skill 的 orphan / contested / stale 风险达到 high

Gate 规则：

- `status --fail-on-hard` 失败于 `hard_errors` 或 `health_blockers`
- `status --fail-on-high` 失败于 `high_priority_pending` 或 `health_high_pending`

## Trellis Integration

### Before Dev

- 先根据改动或目标模块路由 skill
- 读取 skill 前先检查 index 是否新鲜
- 若失效，先重建 `wiki / index`
- 先用 `L0/L1` 预算读取，必要时升级到 `L2/L3`
- 读取顺序调整为：
  1. task artifacts
  2. 受影响 skill 索引摘要 / 恢复文本
  3. 命中的 SKILL / references
  4. `.trellis/spec/`

### Check

- `diff-check` 先消费索引与匹配层，而不是只看机械路径规则
- 若 generated asset 或 wiki/index 失效，先重建，再出建议
- 输出除现有 suggestion 外，还应写 health report

### Finish Work

- 除 `hard_errors` 和 `high_priority_pending` 外，再检查 `health_blockers` 与 `health_high_pending`
- 继续维持“建议文本先出、人工确认后写文件”的边界

## Write Policy

### Allowed Automatic Actions

- generated asset refresh
- wiki/index rebuild
- hash compare
- invalidation mark
- health report generation
- suggestion generation
- append-only / oversize-redirect 的建议文本生成

### Manual Confirmation Required

- 业务语义正文修改
- glossary 定义改写
- decision / playbook / gotcha 文案改写
- 任何真正写入 `skills/` 文件的动作
  - 追加条目
  - 新建 `references/` 长文档
  - 执行 oversize redirect
- 任何低可信度条目的最终定稿

## Deferred Items

本轮虽然按“全部补缺”规划，但以下内容即使进入实现，也不追求重量级版本：

- 不引入外部搜索服务
- 不做复杂图数据库
- 不做完整 RAG
- 不把 `.trellis/spec/` 变成 skills 的副本
- 不做自动语义改写

## Risks

1. 如果把匹配层做得太重，会侵蚀当前简单主链的稳定性。
2. 如果索引协议过于严格，现有 skill 文档需要一次性大改，风险偏高。
3. workflow generated asset 若没有严格限定事实来源，容易回到猜测实现。
4. context budget 若没有明确升级规则，容易变成“全量读一遍”的旧行为。

## Mitigation

1. 先在现有 `skilllib.py` / `skills_guard.py` 上扩展，不重写整套主链。
2. 索引与健康层优先做旁路输出，再逐步接入 gate。
3. workflow asset 只抽状态/入口事实，不推断未显式存在的状态迁移。
4. append-only 写入器只处理程序可识别的条目区块，不碰自由散文主体。
5. 预算层默认从 `L0/L1` 起步，只有命中不足时才升级。
