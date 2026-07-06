---
doc_type: feature-acceptance
feature: 2026-07-06-frontend-publish-status-retry
status: passed
accepted: 2026-07-06
round: 1
---

# 前端发布状态 + 重试 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-06
> 关联方案：.codestable/features/2026-07-06-frontend-publish-status-retry/frontend-publish-status-retry-design.md

## 1. 接口契约核对

对照 design §2.1 名词层：

- [x] publish(id, idempotentKey, accessToken) → PublishAcceptedResponse {publishAttemptId: string} → knowpostService.ts:38-44 一致
- [x] publishStatus(id, attemptId, accessToken) → PublishStatusResponse {publishAttemptId/attemptStatus/postStatus/failedStep/retryable} → :46-49 一致
- [x] retryPublish(id, attemptId, accessToken) → PublishAcceptedResponse（无 body，path variable + token）→ :51-55 一致
- [x] PublishStatusResponse 类型字段与后端 DTO 对齐（attemptStatus/postStatus 枚举、failedStep string|null、retryable boolean）→ types/knowpost.ts 一致
- [x] attemptId 全 string（snowflake 精度防御，对齐 attention 约定）→ 一致
- [x] 流程图（design §2.2 mermaid）节点在代码均有落点：publish/轮询/四态/retry/timeout 出口

无偏差。

## 2. 行为与决策核对

对照 design §1 + §2.2：

- [x] D1 60s/2s/30 次 → usePublishStatus.ts:7-8 POLL_INTERVAL_MS=2000 / POLL_MAX_TIMES=30
- [x] D2 成功停留 CreatePage + 详情/我的知文链接 → CreatePage.tsx:378-380（`<a href={/post/${postId}}>` + `/profile`）
- [x] D3 不做刷新恢复 → 无 localStorage 存 attemptId（grep 反向核对）
- [x] D4 hook 抽出 → src/hooks/usePublishStatus.ts（新建，首个 hook）
- [x] retry 复用同一 attemptId → hook retry:108-119 用 attemptId 入参 startPolling（采纳 review S1）
- [x] publish 双 bug 修复 → service publish 传 token+body+返回类型（curl 202 验证）
- [x] 60s timeout ≠ 后端 5min → design 2.2 明示取舍，timeout 留重试/我的知文出口
- [x] failedStep 文案映射 → CreatePage.tsx:158-162（stuck_publishing→发布超时可重试 / critical_publish→发布失败）
- [x] 不假成功 → CreatePage.tsx publishing 态显示"发布中…"，不再立即"发布成功 ✅"
- [x] 发布中禁用 → `disabled={submitting || phase==="publishing"}`

**明确不做反向核对**（grep）：
- [x] 无 localStorage 存 publishAttemptId
- [x] 无 navigate 到详情（用 a href）
- [x] 后端 git diff 为空

**挂载点反向核对**（design §2.3）：
- [x] M1 CreatePage.handlePublish 调 hook.start → grep 确认
- [x] M2 usePublishStatus hook → 新建文件
- [x] M3 knowpostService publish/publishStatus/retryPublish → grep 确认
- [x] M4 CreatePage 四态 UI → grep 确认
- [x] 反向 grep：引用都在清单内
- [x] 拔除沙盘：删 hook+service 三方法+CreatePage 接入 → 功能消失无残留

## 3. 验收场景核对

对照 design §3（9 场景）：

- [x] **S1 成功**：curl publish 202+attemptId → publishStatus succeeded → UI"发布成功"+链接（curl 已验接口；浏览器 UI 待 owner 实测）
- [x] **S2 失败可重试**：curl status 返 failed+retryable=true → retryPublish 202 同 attemptId（curl 已验）；UI 重试按钮（代码审查）
- [x] **S3 失败不可重试**：后端不可达此态，代码审查 retryable===false 分支不渲染按钮（CreatePage.tsx:383）
- [x] **S4 超时**：design 取舍，timeout+重试/我的知文出口（代码审查）
- [x] **S5 不假成功**：publishing 态显示"发布中…"（代码审查）
- [x] **S6 publish 修复**：curl 带 token 202（已验）
- [x] **S7 去重**：curl 同 idempotentKey 同 attemptId 不重复触发（已验）
- [x] **S8 unmount 清理**：mountedRef+cleanup 代码审查
- [x] **S9 禁用**：`disabled={submitting || phase==="publishing"}`（代码审查）

**review 修复 I1（publish/retry 失败死锁）**：已修（start/retry try/catch 复位 phase），代码审查 + lint 通过。

## 4. 术语一致性

- PublishPhase（hook）/ attemptStatus（DTO）命名分离，无冲突 ✓
- PublishAcceptedResponse / PublishStatusResponse / usePublishStatus / publishStatus 全仓一致 ✓
- 防冲突：grep 无重名 ✓

## 5. 领域影响盘点

- [x] 新名词：无（发布术语后端已有）
- [x] 结构性选择：**首个自定义 hook + src/hooks/ 目录约定**——design 2.5 已标"建议沉淀 convention"，implement 跑通后走 cs-keep 归档
- [x] 流程级约束：60s timeout≠后端 5min 是有意取舍（design 明示）

无领域维度变更需本轮 cs-domain。

## 6. requirement delta 回写

design frontmatter `requirement:` 空（纯前端补功能，无新能力愿景）。保持现状不 backfill，与历史 feature 一致。

## 7. roadmap 回写

design frontmatter 无 roadmap/roadmap_item → 非 roadmap 起头，跳过。

## 8. attention.md 候选盘点

- [x] 候选1：apiFetch token 回退逻辑（undefined 回退 localStorage，null 不带 Authorization）——调用方传 null 会变公开请求 401，值得 attention 记一笔（review L2）
- [x] 候选2：轮询 hook cleanup 约定（unmount/新轮询前/reset 三处清 interval）——design 2.5 已埋，可走 cs-note

不擅自写入，落不落由 owner 退出后定。

## 9. 遗留

- I2（review）：pollOnce 网络抖动 catch 计入 timeout 配额 + token 过期中途轮询 60s 后 timeout 而非登录失效 → follow-up issue
- 浏览器四态实测（S1/S2/S5/S9 UI）→ owner 终审
- usePublishStatus 状态机单测 → 前端 0 测试基建，后续 follow-up
- CreatePage 重构（design 2.5 超出范围）→ 后续 cs-refactor

## 10. 最终审计

- 聚合命令复验：前端 npm run lint ✓；curl publish/status/retry 三接口 ✓
- 交付物落盘：types+service+hook+CreatePage 4 文件 + design/checklist/design-review/review/qa/acceptance 6 spec 文件 ✓
- diff 清洁度：无 console/TODO/死 import ✓
- 知识沉淀出口分流：src/hooks/ convention → cs-keep 候选；apiFetch token 回退 → attention 候选；I2 → follow-up issue
- 覆盖率诚实标记：curl+代码审查 re-verified；浏览器四态 trust-owner-实测
- 无未处理缺口

## Verdict

- Status: **passed**
- 9 节核对完成，所有 checks passed
- residual-risk：I2 记 follow-up；浏览器四态待 owner 终审（dev server 已就绪）
- attention 候选2条 owner 待定
- design 2.5 src/hooks/ convention 待 cs-keep 归档
