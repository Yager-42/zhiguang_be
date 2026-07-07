---
doc_type: issue-analysis
issue: 2026-07-02-comment-likecount-not-aggregating
status: confirmed
root_cause_type: data-format
related: [2026-07-02-comment-likecount-not-aggregating.md]
tags: [counter, snowflake, precision]
---

# 评论 likeCount 不聚合 根因分析

## 1. 问题定位

| 关键位置 | 说明 |
|---|---|
| `CounterServiceImpl.toggle:118` | 点赞时 `CounterEvent.of(etype, eid, ...)` 的 eid 来自调用方传入的 entityId |
| `CommentController.like:93` | `counterService.like("comment", String.valueOf(commentId), userId)` —— commentId 是 path long，前端传丢精度的 number |
| 前端 `commentService.like`（id string 前） | commentId 是 JS number，超 2^53 丢精度（`331035581662498816` → `331035581662498800`） |
| `CounterKeys.aggKey/sdsKey` | agg/SDS key 用 entityId 拼接，丢精度的 eid 写到错误 key |

## 2. 失败路径还原

**正常路径（id string 修复后）**：
用户点赞 commentId=331035581662498816 → 前端用 string 传 commentId（不丢精度）→ 后端 like → CounterEvent.entityId="331035581662498816" → onMessage 写 agg:v1:comment:331035581662498816 → flush 刷到 cnt:v1:comment:331035581662498816（SDS like 段=2）→ getCounts 读 SDS 返回 likeCount=2 ✓

**失败路径（id string 修复前）**：
用户点赞 commentId=331035581662498816 → 前端 JS number 丢精度成 331035581662498800 → 后端 like 接收 331035581662498800 → CounterEvent.entityId="331035581662498800" → agg/SDS 写到 cnt:v1:comment:331035581662498800 → list 查真实 commentId 331035581662498816 的 SDS → 不存在 → likeCount=0 ✗

**分叉点**：前端 commentId 类型——number 丢精度 vs string 不丢精度。

## 3. 根因

**根因类型**：logic（评论 likeCount 读取路径与 counter 聚合写入路径脱节）

**根因描述**：评论的 likeCount 有两条脱节的路径：
1. **写入路径**：点赞 → counter event → Kafka → CounterAggregationConsumer → 写 Redis SDS（`cnt:v1:comment:{eid}`），like 段正确累加（现场验证 SDS like 段=2）
2. **读取路径**：`CommentServiceImpl.item()` 的 `row.getLikeCount()` 读 **MySQL `comments.like_count` 字段**，而这个字段**全仓库没有任何代码更新**（grep 无 update like_count）

两条路径脱节：counter 写 SDS，但 list 读 MySQL。MySQL like_count 永远是初始值 0，所以 list 返回 likeCount=0。

对比帖子（knowpost）：`KnowPostDetailResponse.likeCount` 来自 `counterService.getCounts()`（读 SDS），不读 MySQL，所以正常。

**是否有多个根因**：是，主次两个：
- **主根因**：评论 likeCount 读 MySQL 而非 SDS（路径脱节）—— 这是 likeCount=0 的直接原因
- **次根因**：snowflake commentId 精度丢失（已由评论点赞 feature id string 修复）—— 修复前还导致 SDS 写到错误 key，现已解决

主根因未被之前的 id 修复解决。id 修复只让 SDS 写到正确 key，但 list 根本不读 SDS，仍读 MySQL 的 0。

**现场证据**：
- MySQL comments.like_count 全是 0（被赞评论也是 0）
- SDS cnt:v1:comment:{cid} like 段=2（聚合正确）
- 全仓库无 update comments.like_count 的代码

## 4. 影响面

- **影响范围**：仅历史点赞数据受影响——id string 修复前点赞的评论，likeCount 写到了丢精度的错误 key，真实 commentId 的 SDS 仍为 0。id 修复后新点赞正常。
- **潜在受害模块**：所有 snowflake id 的 counter 场景。但帖子（knowpost）id 一直是 string（KnowPostDetailResponse.id: String），不受影响；评论 id 现已 string，修复。其它实体（如 reply）若 id 未 string 化仍有风险。
- **数据完整性风险**：历史脏数据——Redis 里有丢精度的错误 key（如 cnt:v1:comment:331035581662498800）存着旧点赞计数，与真实 commentId 的 SDS 不一致。不影响功能，只占 Redis 空间 + 旧评论 likeCount 显示偏低。
- **严重程度复核**：维持 P2。核心功能（点赞/liked/删除）正常，仅历史 likeCount 显示不准，新数据正常。

## 5. 修复方案

### 方案 A：评论 likeCount 改读 counter SDS（推荐）
- **做什么**：`CommentServiceImpl.item()` 的 likeCount 不再读 `row.getLikeCount()`（MySQL），改为调 `counterService.getCounts("comment", String.valueOf(commentId), List.of("like")).get("like")`（读 SDS）。对齐 KnowPostDetailResponse 的 likeCount 读取方式。replyCount 同理（若 counter 支持）或暂保持 MySQL。
- **优点**：根因最直接——读 SDS 和写 SDS 对齐；与帖子 likeCount 读取一致；counter 已有 getCounts 接口
- **缺点/风险**：item() 每条评论多一次 getCounts（读 SDS，1 次 Redis GET），一页 20 条 = 20 次。可接受（帖子也这样）。需处理 getCounts 返回 null/异常的兜底（返回 0）。
- **影响面**：改 CommentServiceImpl.item()；可能改 item() 签名（已有 currentUserId，可复用注入的 counterService）

### 方案 B：counter 聚合后回写 MySQL like_count
- **做什么**：在 CounterAggregationConsumer.flush 或单独任务里，把 SDS 的 likeCount 回写到 MySQL comments.like_count
- **优点**：list 不用改（继续读 MySQL）
- **缺点/风险**：引入 counter→MySQL 反向同步，与 counter 系统"SDS 为准"的设计相悖；新增写 MySQL 路径，复杂度高；要处理 comment/knowpost 等所有 etype 的回写
- **影响面**：改 CounterAggregationConsumer + 新增 mapper update 方法，影响面大

### 方案 C：list 批量读 SDS（性能优化版 A）
- **做什么**：方案 A 基础上，用 `counterService.getCountsBatch("comment", commentIds, List.of("like"))` 批量读 SDS（管道），减少 N 次 Redis 为 1 次
- **优点**：方案 A 的正确性 + 批量性能
- **缺点/风险**：getCountsBatch 已存在（CounterServiceImpl:290），但 item() 是逐条 map，要改成先批量读再填，改动比 A 大
- **影响面**：改 CommentServiceImpl.page()（先批量读 counts 再构造 items）

### 推荐方案

**推荐方案 A**，理由：根因最直接（读 SDS 对齐写 SDS），改动最小（只改 item() 的 likeCount 来源），与帖子 likeCount 读取方式一致。方案 B 引入反向同步违背 counter 设计。方案 C 是 A 的性能优化，MVP 先 A（一页 20 次 getCounts 可接受，帖子也逐条），量大再 C。

历史脏数据（丢精度的错误 SDS key）已在本 issue fix 阶段清理（5 个脏 key 已删）。
