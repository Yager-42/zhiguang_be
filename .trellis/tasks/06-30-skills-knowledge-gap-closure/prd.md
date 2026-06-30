# Skills Knowledge Gap Closure

## Goal

补齐当前 `skills/` 知识管理体系相对既有规划仍未落地的能力缺口，使其从“主链可用”提升到“结构、检索、治理三层闭环更完整”的状态，同时继续保持：

- `skills/` 是业务知识真相源
- `.trellis/spec/` 是工程规范真相源
- 语义更新必须先产出建议文本，再人工确认后写文件

## User Value

当前体系已经能完成主线的路由、generated asset 刷新、diff-check、人工确认后 apply，以及 Trellis 三段挂接。但仍缺少更强的知识检索、知识健康治理、以及部分结构化协议的真正落地。补齐这些缺口后，后续 AI coding 对业务知识的消费会更稳定，也更接近之前任务中承诺过的“防腐化”目标。

## Confirmed Facts

- 当前主链已落地，工程约束已经写入 [.trellis/spec/backend/skills-knowledge-workflow.md](/E:/idk/zhiguang_be/.trellis/spec/backend/skills-knowledge-workflow.md)。
- 当前核心实现集中在 [scripts/skills/skilllib.py](/E:/idk/zhiguang_be/scripts/skills/skilllib.py) 和 [scripts/skills/skills_guard.py](/E:/idk/zhiguang_be/scripts/skills/skills_guard.py)。
- 当前自动生成资产只有 4 个：
  - `skills/zhiguang-repo-map/references/generated-api-index.md`
  - `skills/zhiguang-repo-map/references/generated-skill-routing-map.md`
  - `skills/zhiguang-common-runtime/references/generated-config-index.md`
  - `skills/zhiguang-platform-domain/references/generated-enum-index.md`
- 之前归档任务的规划明确承诺过但未完全落地的能力包括：
  - 结构化条目协议的全面落地
  - append-only / duplicate-check / oversize-redirect 的自动约束
  - generated asset 的 invalidate / rebuild 语义
  - wiki / index / backlink / graph 层
  - CJK-aware 术语 / 关键词 / 领域语义匹配
  - session 级建议去重桥
  - health report / orphan / broken-link / health score
  - context budget 分级
  - workflow 字段类 generated asset
- 当前 `skilllib.py` 的 route / diff-check 仍主要依赖：
  - 包前缀
  - 配置前缀
  - `Controller.java` / `Properties.java` / enum 文件形态
- 当前 `skills/README-zh.md` 已声明统一字段协议、glossary schema 和 append-only 原则，但大多数 skill 文档还没有被这套协议全面约束。
- 通过源码搜索，当前仓库里确实存在稳定的状态/阶段事实抓手，可支撑后续 workflow 类 generated asset，例如：
  - `PromotionBidStatus`
  - `PromotionCampaignStatus`
  - `PromotionAuctionWindowStatus`
  - `WalletEscrowStatus`
  - `ReconciliationTaskStatus`
  - `KnowPost` / `PublishAttempt` 的状态字段与 `/publish/status` 接口

## Requirements

1. 新任务必须以“补缺”为目标，不重做已落地主链，不推翻现有 `skills/` 与 `.trellis/spec/` 双轨设计。
2. 本轮补缺范围按“全部补缺”处理，纳入这次审计确认的剩余缺口，而不是只收核心三层。
3. 本轮作为一个大任务执行，不拆 parent/child。
4. 补缺实现必须优先消费现有实现：
   - `skill_registry.yaml`
   - `skilllib.py`
   - `skills_guard.py`
   - `skills/README-zh.md`
5. 若引入新能力，必须明确挂接到 Trellis 主流程，而不是新增一套平行人工流程。
6. 继续遵守语义写回边界：
   - 自动阶段只允许生成建议、刷新 generated asset、产出健康报告、重建索引、失效缓存
   - 真正写回业务知识仍需人工点头后执行
7. 补缺任务需要把“之前规划里承诺过、但没落地”的能力分层说明清楚，避免再次出现只写规划不落地的情况。
8. 规划文档必须显式处理以下缺口：
   - `wiki / index / backlink / graph`
   - `术语 / 关键词 / 领域匹配`
   - `session` 级去重
   - `health governance`
   - `context budget` 分级
   - 结构化条目协议真正落地
   - `append-only / duplicate-check / oversize-redirect` 自动约束
   - `invalidate / rebuild`
   - `workflow generated asset`
9. 规划文档必须明确：
   - index / health / task state 的输出 contract
   - health severity 到 Trellis gate 的映射
   - 自动动作与人工确认动作的边界

## Acceptance Criteria

- [ ] 形成正式规划文档，明确本轮补缺的目标范围、分层设计、实现顺序和与 Trellis 的挂接方式。
- [ ] 规划文档明确区分：
  - 已有能力
  - 本轮补的能力
  - 暂不处理的能力
- [ ] 规划文档覆盖本轮全部补缺范围，不遗留已确认纳入的缺口，尤其包括 `context budget` 分级。
- [ ] 规划文档明确人工确认边界，不允许把自动语义改写引入本轮实现。
- [ ] 规划文档定义 index / health / state contract 与 gate severity 规则。
- [ ] 在开始实现前，用户已明确批准本轮补缺范围与任务组织方式。

## Out of Scope

- 改写 `.trellis/spec/` 为业务知识库
- 取消人工确认、改成自动语义写回
- 回头重构已归档父任务的历史规划文本
- 为了补缺而推翻现有 9 个 skills 的基本拆分
- 为了追求形式完整而新增独立的并行知识工作流
