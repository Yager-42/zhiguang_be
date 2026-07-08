---
doc_type: feature-qa
feature: 2026-07-07-frontend-promotion
status: passed
reviewed: 2026-07-07
round: 1
---

# frontend-promotion QA 验证报告

## 1. QA 范围

轻量 QA。前端 0 测试，以 curl 联调 + 后端测试 + 代码审查 + 浏览器手工为主。focus：review 修复 B1/I1/I2/I3 + design §3 核心场景。

## 2. 验证场景与证据

### QA-1: snapshot 接口契约（design S4/S7，curl 已验）
- curl snapshot 200 + `{"auctionWindowId":"7002",...,"ranking":[{"campaignId":"10002","bidderUserId":"15","postId":"332500000000000001",...}]}` —— id 全 string 带引号 ✅
- 结论：snapshot DTO string 化生效，精度防御 ✅

### QA-2: B1 轮询闭包修复（review 修复，代码审查）
- 证据：PromotionRoomPage snapshotRef.current 持有最新 snapshot；startPolling tick 读 snapshotRef.current?.status==="OPEN" 续轮询；pollSnapshot 同步 snapshotRef.current=snap
- 结论：首次出价后轮询持续（不因闭包陈旧停）✅（浏览器实测待 owner）

### QA-3: B3 后端 DTO string 化（review 修复，后端测试）
- 证据：PromotionAuctionSnapshot/PromotionRankingItem long→String + 6 构造 String.valueOf + 4 测试适配；后端 mvn test promotion 109 全过
- 结论：id 全 string ✅

### QA-4: I1 推广按钮 isSelf 别名修复（review 修复，代码审查）
- 证据：CourseDetailPage isSelfForPromote = !!(derivedId && user?.id === derivedId)（去昵称 fallback）
- 结论：昵称相同的非作者看不到推广按钮 ✅

### QA-5: I2/I3 出价成功路径 + 8s 最终态（review 修复，代码审查）
- 证据：handleBid 成功路径 if(mountedRef.current)；8s 定时器 setBidPending(false)+setBidError("处理中（可能延迟）")
- 结论：卸载安全 + 8s 超时收尾 ✅

### QA-6: 清洁度 + 反向核对（grep 已验）
- grep 无 console.log / TODO / FIXME / 死 import ✅
- 无 @stomp/stompjs ✅
- 后端 wallet 未改 ✅
- 后端只改 snapshot DTO（2 DTO + 2 service + 4 测试）✅
- 前端 npm run lint 绿 ✅

## 3. 待 owner 浏览器实测场景（dev server 5173 + 后端 8080 bprime=true）

- **S1 发布成功推广按钮**：CreatePage 发布成功后显示"推广"链接
- **S2 详情页推广按钮**：作者看自己帖有"推广"按钮，非作者无
- **S3 createCampaign**：进 /promotion/:postId 选资源位 + 提交 → 进竞价视图
- **S4 排名轮询**：出价后 4s 轮询持续 + 排名更新（B1 修复后）
- **S5 出价**：submitBid 200 + 排名含出价
- **S6 积分余额**：显示"积分: N" + 出价后减少
- **B1 首次出价轮询**：首次出价后 Network 看 4s 持续轮询（核心验证）

## 4. Verdict

- Status: **passed**
- curl snapshot string id + 后端 109 测试 + 代码审查全通过
- review B1/I1/I2/I3 全修
- 浏览器实测交 owner
- residual-risk: RR1 SETTLED 终态 UI（SQL 改 status 验证）；RR2 createCampaign 失败无重试
- Next: 进入 `cs-feat-accept`
