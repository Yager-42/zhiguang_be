---
doc_type: feature-design-review
feature: 2026-07-07-frontend-promotion
status: passed
reviewed: 2026-07-07
round: 1
---

# frontend-promotion feature design 审查报告

## 1. Scope And Inputs

- Design: .codestable/features/2026-07-07-frontend-promotion/frontend-promotion-design.md
- Checklist: 同目录（4 step / 17 check）
- Related docs: compound/counter-likecount-read-sds-not-mysql.md、notification/report design 先例
- Code facts checked: PromotionController、5 DTO、PromotionAuctionSnapshot/RankingItem、WalletBalanceResponse、CreatePage.tsx:385、CourseDetailPage.tsx:243、types/auth.ts:29、PromotionCommandSubmissionService:122/133

### Independent Review

- Status: completed
- Detection: native-agent（general-purpose subagent，无 Paseo，同类降级）
- Provider / agent: general-purpose（agentId aca5ea37b239377f0）
- Raw output: 见第 3 节（已逐条本地事实核验，B2 误报驳回）
- Gate effect: 无 blocking，可交用户整体 review

## 2. Design Summary

- Goal: 前端竞价模块——createCampaign + 竞价房间页（实时排名轮询 + 出价），入口发布成功页 + 详情页，余额文案改积分
- Key contracts: promotionService 5 方法 + walletService.balance + PromotionRoomPage + snapshot long→String
- Steps: 4（service+types+DTO → 房间页+createCampaign → 竞价视图+轮询+出价 → 入口接入+harden）
- Checks: 17，覆盖 8 验收场景 + 反向核对
- Baseline: npm run lint + mvn test + curl 5 接口 + 浏览器

## 3. Findings

### blocking
none。

**reviewer B2"前端 submodule 旧 commit"误报——本地核验驳回**：reviewer 称前端 HEAD=fef10df 无 publishStatus/isSelfForReport。本地核验：zhiguang_fe HEAD=`76f8211`（举报 commit），`usePublishStatus`/`publishStatus.phase`/`isSelfForReport`/`authorActions` 全在（CreatePage:10/15/375/377，CourseDetailPage:240/243/245）。reviewer worktree 隔离问题。前端引用属实。

#### B1 user.id(number) vs bidderUserId(string) 类型冲突致"你的位次高亮"失效（已修）
- Evidence: types/auth.ts:29 `id: number`；design RankingItem.bidderUserId: string；`===` 比较恒 false。
- 处置：**已修**。design 2.2 补"你的位次高亮：String(user.id)===bidderUserId（显式 String() 归一化）"；1.3 非显然依赖补"user.id 是 number"。

#### B3 WalletBalance 类型只 2 字段，后端实际 4 字段（已修）
- Evidence: WalletBalanceResponse 4 字段（availableBalance/heldBalance/escrowedBalance/status）；design 只声明 2。
- 处置：**已修**。design 2.1 WalletBalance 补全 4 字段；checklist 1.2 同步。

### important（已修）

- **I1 startAt/endAt ISO 序列化未声明** ✅ 2.2 补"new Date().toISOString()（ISO Z 串）"
- **I2 loading 终止条件未定义** ✅ 2.2 补"submitBid 200 即结束 loading（不等排名更新）；8s 后无出价显示'出价处理中'；resultAvailable 恒 false 不依赖"
- **I3 decisionVersion 类型不确定** ✅ 1.4 补"单调递增计数远小于 2^53，保留 number"
- **I4 WebSocket 后端背景未声明** ✅ 1.3 补"后端已有 STOMP 推送，本期前端不接入用轮询替代"；反向核对补"前端无 @stomp import"
- **I5 eligibility 完整约束 + startAt 可调风险** ✅ 2.2 补"startAt 锁定 now 不可调，endAt 下限 now+2h"；1.4 补 eligibility 约束公式

### nit/suggestion

- N1 bidAmount number 闭环 ✅ 1.4 补"积分类小额整数远小于 2^53"
- N2 campaignId path param 后端接 long ✅ checklist 1.3 补"前端传数字串即可"
- S1 PromotionRoomPage ~250 行无膨胀触发线 → 2.5 改"超 350 行拆 usePromotionRoom hook"
- S2 checklist step1 exit_signal 多条件 → 实现时按"全绿才 done"
- S3 构造处列举 ✅ 1.6 + checklist 1.2 补"SnapshotService 3 处 snapshot:37/42/45 + 2 处 ranking:63/83 + 3 测试文件"

### praise

- P1 R2 +24h 默认经核验确实覆盖最坏 allocation 窗口（windowEnd+60min≈now+120min << now+24h）
- P2 snapshot long→String 走通知/举报 B1/B3 先例，项目级一致性推进
- P3 反向核对"明确不做"4 条可 grep 核对

### residual-risk

- RR1 bprime 链路依赖 Kafka（非 RocketMQ 笔误已修正 1.5）→ 本地 Kafka 容器需在起
- RR2 S8 窗口 SETTLED 停轮询难自然触发 → acceptance 时用 SQL 改 window status 验证

## 4. User Review Focus

- 用户需重点拍板：无（owner 已定 4 决策，B1/B3 + I1-I5 是事实/逻辑错误已修，B2 误报驳回）
- implement 需重点遵守：snapshot long→String + 5 构造处 + 3 测试；startAt 锁定 now；String(user.id)===bidderUserId；submitBid 200 即结束 loading；ISO Z 串序列化
- code review/QA/acceptance 重点复核：B1 位次高亮、B3 wallet 4 字段、I2 loading 终止、I5 startAt 锁定、R1 精度

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | 8 场景+矩阵全，S7 含 snapshot string，S8 代码审查 | none |
| DoD Contract | pass | E | Design/Impl/Review/QA/Acceptance + 命令 + 产物齐全（含后端 2 DTO+5 构造+3 测试） | none |
| Steps and checks traceability | pass | E | 4 step/17 check 可追溯，构造处列举 | none |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | pass | C | WalletBalance 4 字段对齐 + String(user.id) 归一化 + startAt 锁定 | none |
| Validation and artifacts | pass | E | 命令+产物齐（含后端 DTO+构造+测试） | none |

Summary: E=5, C=1, H=0, H-only core checks=none。

## 6. Residual Risk

- RR1 bprime 依赖 Kafka 容器 → 本地需起
- RR2 S8 SETTLED 停轮询难自然触发 → SQL 改 status 验证

## 7. Verdict

- Status: **passed**
- reviewer B2"前端旧 commit"误报，本地核验驳回（前端 76f8211 含全部引用代码）；B1（user.id 类型冲突）+ B3（wallet 字段）+ I1-I5 全修。
- reviewer: native-agent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 交给用户整体 review。用户确认后回 cs-feat-design 标 approved → cs-feat-impl。
