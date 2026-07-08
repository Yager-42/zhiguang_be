---
doc_type: feature-review
feature: 2026-07-07-frontend-promotion
status: passed
reviewed: 2026-07-07
round: 1
reviewer: subagent
source: cs-feat-impl
---

# frontend-promotion 代码审查报告

## 1. 范围与输入

- 来源：cs-feat-impl（4 step 全 done）
- Design: .codestable/features/2026-07-07-frontend-promotion/frontend-promotion-design.md（approved）
- Checklist: 同目录（4 step done / 17 check）
- 改动文件：后端 8（2 DTO + SnapshotService + WindowCloser + 4 测试）；前端 9（types+2 service+RoomPage+css+CreatePage+CourseDetailPage+App+css）
- reviewer B2"前端旧 commit"误报（同 notification feature）——本地核验前端在 76f8211 含全部引用代码

### 独立审查

- 环节 A（独立 Task agent）：completed。general-purpose subagent（agentId a80824c595d742424）
- 环节 B（OCR）：not-available
- reviewer: subagent

## 2. 改动摘要

- 后端：PromotionAuctionSnapshot + PromotionRankingItem long→String + 6 处构造 String.valueOf + 4 测试适配
- 前端：promotionService 5 方法 + walletService + PromotionRoomPage（createCampaign 表单 + 竞价视图 + 4s 轮询 + 出价 + 积分）+ CreatePage/CourseDetailPage 推广入口

## 3. Findings

### blocking

#### B1 轮询闭包陈旧致排名死锁（已修）
- Evidence: PromotionRoomPage startPolling tick 闭包捕获旧 snapshot，首次出价 snapshot=null → `snapshot?.status==="OPEN"` 恒 false → 轮询只跑一次停。S4 失效。
- 处置：**已修**。加 snapshotRef 持有最新 snapshot，tick 读 `snapshotRef.current?.status`；pollSnapshot 里 `snapshotRef.current = snap` 同步。lint 绿。

### important

#### I1 推广按钮 isSelf 昵称别名泄漏（已修）
- Evidence: CourseDetailPage 推广按钮用 isSelf（含昵称 fallback），昵称相同的非作者会看到"推广"按钮，点击 createCampaign 必失败（后端校验归属）。
- 处置：**已修**。推广按钮改用 `isSelfForPromote = !!(derivedId && user?.id === derivedId)`（去昵称 fallback，与 isSelfForReport 同源）。

#### I2 handleBid 成功路径缺 mountedRef 守卫（已修）
- Evidence: 成功路径 setBidding(false)/setBidPending(true) 无 mountedRef，卸载后 setState。
- 处置：**已修**。成功路径包进 `if (mountedRef.current)`。

#### I3 8s 定时器空跑（已修）
- Evidence: bidPendingTimerRef 8s 回调体空注释，bidPending 无超时收尾。
- 处置：**已修**。8s 后若仍 pending → setBidPending(false) + setBidError("出价处理中（可能延迟）")，给最终态。

### nit/suggestion

- S1 activeAllocations 返回 unknown[] 未对齐 design → 本期不消费此接口，记 follow-up 补类型
- S2 Number(bidAmount) 对 "1e3" 等通过 → 输入卫生小问题，非 bug
- S3 pollSnapshot 依赖 user?.id，user 异步未到时高亮失效 → 刷新恢复，低优先

### praise

- P1 后端 R1 精度修复完整：6 处构造 + 4 测试全改，record 签名改 String 后编译期兜底漏改
- P2 String(user.id)===bidderUserId 归一化正确（user.id number vs bidderUserId string）
- P3 submitBid 200 即结束 loading 契约实现正确（resultAvailable 恒 false 不依赖）
- P4 startAt 锁定 now + endAt +24h 覆盖 eligibility 约束
- P5 cleanup 清两个 timer + mountedRef，卸载收尾干净
- P6 钱包余额 availableBalance + 出价校验 ≤ 余额，与后端 hold 对齐

### residual-risk

- RR1 S8 SETTLED 停轮询 + 结算结果 UI——B1 修后轮询能停，但 SETTLED 终态文案 UI 未做（只有停轮询），QA 用 SQL 改 window status 验证
- RR2 createCampaign 失败无重试/返回 → 用户卡表单，QA 覆盖
- RR3 出价 400 错误信息透传后端英文 → 体验差非 bug

## 4. Test And QA Focus

QA 必须复核：
1. **首次出价后排名持续更新**（B1 修复后）：出价 → Network 看 4s 轮询持续
2. **SETTLED 停轮询**：SQL 改 window status 验证（RR1）
3. **8s 处理中**：出价后断 bprime → 8s 后显示"处理中（可能延迟）"（I3 修复后）
4. **非作者无推广按钮 + 昵称别名**（I1 修复后）：同昵称用户互访确认无推广按钮
5. **出价后离开页面**：无卸载告警（I2 修复后）
6. **余额边界**：=余额通过、=余额+1 拦、出价后余额刷新
7. **id string 精度**：snapshot 响应 id 带引号 + snowflake 帖子位次高亮命中
8. **createCampaign 时间段**：默认 now~+24h 成功
9. **重复出价防抖**：loading 期间 disabled
10. **清洁度**：grep 无 console/TODO/@stomp，后端 wallet 未改

## 5. Verdict

- Status: **passed**
- B1（轮询闭包陈旧）已修（snapshotRef）；I1（isSelf 别名）已修（derivedId 判 self）；I2（mountedRef）已修；I3（8s 最终态）已修。S1-S3 非阻塞。
- reviewer: subagent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 进入 `cs-feat-qa`
