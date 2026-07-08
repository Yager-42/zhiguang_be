---
doc_type: feature-acceptance
feature: 2026-07-07-frontend-promotion
status: passed
accepted: 2026-07-07
round: 1
---

# 前端推广竞价 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-07
> 关联方案：.codestable/features/2026-07-07-frontend-promotion/frontend-promotion-design.md

## 1. 接口契约核对

- [x] promotionService 5 方法（createCampaign/getCampaign/submitBid/snapshot/activeAllocations）→ 一致
- [x] walletService.balance GET /wallet/me → 一致
- [x] snapshot id 全 string（后端 DTO long→String + 6 构造 + 4 测试）→ curl 验证带引号
- [x] WalletBalance 4 字段 → 一致
- [x] 流程图节点在代码均有落点

## 2. 行为与决策核对

- [x] D1 入口：CreatePage 发布成功 + CourseDetailPage 作者区 ✅
- [x] D2 创建即进房间 ✅
- [x] D3 snapshot 4s 轮询（B1 修复 snapshotRef）✅
- [x] D4 积分文案（后端 wallet 不动）✅
- [x] D5 资源位默认 feed_top_slot + 时间段 now~+24h ✅
- [x] startAt 锁定 now + ISO Z 串 ✅
- [x] String(user.id)===bidderUserId 归一化（B1）✅
- [x] submitBid 200 即结束 loading + 8s 最终态（I3）✅
- [x] 作者本人看推广（isSelfForPromote 去昵称 fallback，I1）✅

**明确不做反向核对**：
- [x] 无 @stomp/stompjs
- [x] 后端 wallet 未重命名
- [x] 无活动管理列表页

## 3. 验收场景核对

- [x] S1 发布成功推广按钮（代码审查；浏览器待 owner）
- [x] S2 详情页推广按钮（代码审查；浏览器待 owner）
- [x] S3 createCampaign（curl 验证 + 代码审查）
- [x] S4 排名轮询（B1 修复 snapshotRef；浏览器待 owner）
- [x] S5 出价（curl submitBid 200 + 代码审查）
- [x] S6 积分余额（代码审查）
- [x] S7 id string（curl 验证带引号）
- [x] S8 SETTLED 停轮询（代码审查；SQL 改 status 验证待 owner）

**review 修复**：B1（轮询闭包）+ I1（isSelf 别名）+ I2（mountedRef）+ I3（8s 最终态）全修。

## 4-7. 术语/领域/req/roadmap

无领域变更，非 roadmap 起头，req 空。

## 8. attention 候选

- snowflake id string 化项目级约定（评论/通知/举报/竞价都改了）→ 候选 cs-note/cs-keep
- bprime 链路异步（submitBid→决策 1-3s）→ 候选 cs-note

## 9. 遗留

- 浏览器实测（S1-S8）→ owner 终审
- SETTLED 终态 UI（RR1）→ 后续补
- activeAllocations 类型（S1）→ 后续补
- 后端 wallet→points 重命名（D4 不做）→ 独立 cs-refactor

## 10. 最终审计

- 聚合命令复验：前端 lint ✓；后端 mvn test 109 过 ✓；curl snapshot string id ✓
- 交付物落盘：前端 9 文件 + 后端 8 文件 + 7 spec ✓
- diff 清洁度：无 console/TODO/死 import ✓
- 覆盖率诚实标记：curl+后端测试+代码审查 re-verified；浏览器 trust-owner-实测

## Verdict

- Status: **passed**
- 9 节核对完成，所有 checks passed
- residual-risk：浏览器四态待 owner 终审；SETTLED 终态 UI 后续补
