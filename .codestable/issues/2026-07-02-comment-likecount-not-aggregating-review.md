---
doc_type: issue-review
issue: 2026-07-02-comment-likecount-not-aggregating
status: passed
reviewed: 2026-07-03
round: 1
reviewer: subagent
source: cs-issue-fix
---

# 评论 likeCount 不聚合 代码审查报告

> 阶段：issue-fix 收尾（cs-issue-fix → cs-code-review）
> 审查对象：`CommentServiceImpl.item()` 单方法改动（likeCount 读路径 MySQL → counter SDS）
> 关联：report + analysis + fix-note 同目录

## 1. 范围与输入

- 改动文件：`src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`（唯一代码改动，+16/-4 行）
- spec 产物：`analysis.md`（status=confirmed，方案 A）+ `fix-note.md`（status=fixed）
- baseline 对照：`KnowPostServiceImpl.java:341` 帖子 likeCount 读 SDS 路径

### 独立审查（环节 A / B）

- 环节 A（独立 Task agent reviewer）：completed。general-purpose subagent 只读审查，prompt 未透露主 agent 结论。
- 环节 B（OCR 行级扫描）：not-available（`ocr` CLI 未安装，非阻塞）。
- 合并策略：reviewer findings 已逐条本地事实核验（DTO 类型 / FIELD_SIZE / getCounts 返回路径 / 帖子基线），证据不足或与代码不符的降级或驳回。
- reviewer 字段：`subagent`（环节 A 完成，环节 B 不可用）。

## 2. 改动摘要

`item()` 的 likeCount 从 `row.getLikeCount()`（MySQL 死字段，永远 0）改为 `counterService.getCounts("comment", commentIdStr, List.of("like")).get("like")`（读 SDS，与写入路径对齐）。提取 `commentIdStr` 供 liked（位图）与 likeCount（SDS）共用，消除两条读取路径的 entityId 分叉。

## 3. Findings

### blocking
none。

### important

#### I1 `(int) likeCount` 截断——本 diff 引入的 correctness gap（已修）
- 证据：`CommentItemResponse.likeCount` 是 `Integer`（:14），而帖子基线 `KnowPostDetailResponse.likeCount` 是 `Long`（:20）。SDS `CounterSchema.FIELD_SIZE=4`，`readInt32BE` 返回无符号 32 位 [0, 2^32−1] 的 long。改前 `row.getLikeCount()` 是 MySQL INT（已是有符号 int 范围），改后 `(int) likeCount` 把 SDS 上半区 [2^31, 2^32−1] 截断成负数。
- 影响：评论点赞破 21 亿才会触发，现实不可能；但截断路径是本 diff 新引入，且帖子 DTO 用 Long 规避了——评论 DTO 用 Integer 是历史选择。
- 处置：**已修**。加 `if (likeCount > Integer.MAX_VALUE) likeCount = Integer.MAX_VALUE;` 钳制，保证非负。1 行，同方法内，未越 issue 范围。
- 验证：CommentServiceImplTest + CommentControllerTest 31 测试全过。

### nit

#### N1 全限定类名内联（已顺手修）
- 证据：原 diff 用 `java.util.Map` / `java.util.List.of`，但文件已 `import java.util.List; import java.util.Map;`。
- 处置：改为 `Map` / `List.of`。

#### N2 `counts == null` 是死代码（已顺手修）
- 证据：`CounterServiceImpl.getCounts` 所有返回路径都构造 `new LinkedHashMap<>()`，永不返回 null。
- 处置：去掉 `counts == null ? null :` 分支，简化为 `counts.get("like")`。

### suggestion

#### S1 静默吞异常缺可观测性
- 证据：`catch (RuntimeException ignore)` 无 log/metric。counter 整体故障时所有评论 likeCount 静默回 0。
- 对比：帖子基线 `KnowPostServiceImpl:341` 根本不包 try/catch（异常直接抛），评论已比基线更防御。
- 建议（不阻塞）：可加 `log.warn("counter getCounts failed for comment {}, fallback to 0", commentIdStr, ignore)`。本次不加——与基线一致性优先，且加 log 需引入 logger 字段，超出最小修复范围。

### praise

- P1：`commentIdStr` 提取让 liked（位图 `bm:like:comment:{cid}:{chunk}`）与 likeCount（SDS `cnt:v1:comment:{cid}`）共用同一 entityId，收口了 analysis 次根因（两条路径 eid 分叉）。
- P2：改动面极小（单方法），未污染写入路径与聚合链路，回归风险低。

## 4. residual-risk

- **R1 getCounts 重建风暴成本被 fix-note 低估**：SDS 缺失时 getCounts 走重建路径（Redisson 锁 + 限流 `ratePermits:3`/10s + `KEYS bm:like:comment:{eid}:*` 扫描），冷启/flush 后一页 20 条可能触发多次重建 + 限流回 0。fix-note 第 5 节"一页 20 次 Redis GET"描述不准。但帖子基线同样逐条 getCounts，**行为一致**，非本 issue 引入。中期优化走 analysis 方案 C（`getCountsBatch` 管道批量读，缺失直接补 0 不重建）。登记为 follow-up，不在本 issue 范围。
- **R2 replyCount 仍读 MySQL**：fix-note 第 7 节已记。回复功能上线后若回复的回复走 counter 聚合会同样脱节。
- **R3 软删不清理 SDS/位图 key**：`delete()` 只软删 + 删文本，孤儿计数 key 留在 Redis。非本 issue 引入，登记 follow-up。
- **R4 replies 路径 likeCount 未实测**：fix-note 只验 pageComments。代码逻辑上回复有自己的 SDS key（`CommentController.like` 接受回复 id），replies 下 likeCount 应正确，但 review 无法 100% 从代码确认，需 QA/联调对一条回复点赞后调 `/replies` 验证。

## 5. Test And QA Focus

- **截断钳制**：构造 SDS like 段 = 2^31（写 4 字节大端 0x80000000），调 list，确认返回 `2147483647` 而非负数（钳制已加，需造数验证）。
- **replies likeCount（R4）**：对一条回复点赞，调 `/comments/{rootId}/replies`，确认回复行 likeCount 正确。
- **counter 故障回退**：停 redis 后 list 不 500（兜底生效）；当前无日志记录回退（S1）。
- **建议补单测**：`item()` 对 getCounts 抛 RuntimeException 时返回 likeCount=0 且不抛出（现有 31 测试未确认覆盖此分支）。

## 6. Verdict

- Status: **passed**
- 无 blocking；I1（截断）已修，N1/N2 顺手修；S1 不阻塞；R1–R4 登记为 follow-up / QA focus。
- reviewer: `subagent`（环节 A 完成，OCR 不可用）。
- Next：回到 cs-issue-fix 收尾——询问 owner 是否 scoped-commit。
