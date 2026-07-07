---
doc_type: issue-fix
issue: 2026-07-02-comment-likecount-not-aggregating
status: fixed
fixed: 2026-07-03
related: [2026-07-02-comment-likecount-not-aggregating.md, 2026-07-02-comment-likecount-not-aggregating-analysis.md]
tags: [counter, likeCount, sds, comment]
---

# 评论 likeCount 不聚合 修复记录

## 1. 问题回顾

评论点赞后 likeCount 一直显示 0，不随点赞数增长。liked 字段正常。

## 2. 根因（analysis 确认）

评论 likeCount 读取路径与 counter 聚合写入路径脱节：
- **写入**：点赞 → counter event → SDS（`cnt:v1:comment:{cid}`），like 段正确累加
- **读取**：`CommentServiceImpl.item()` 读 MySQL `comments.like_count` 字段，该字段全仓库无代码更新，永远 0

帖子 likeCount 正常是因为读 SDS（getCounts），评论读 MySQL 所以是 0。

次根因（snowflake 精度丢失）已由评论点赞 feature id string 修复，并清理了 5 个丢精度的脏 SDS key。

## 3. 修复方案

采用 analysis 方案 A：评论 likeCount 改读 counter SDS，对齐帖子 likeCount 读取方式。

## 4. 改动

### `CommentServiceImpl.java` item()
- likeCount 从 `row.getLikeCount()`（MySQL）改为 `counterService.getCounts("comment", commentIdStr, List.of("like")).get("like")`（读 SDS）
- 复用已注入的 counterService（评论点赞 feature 注入的）
- 异常兜底返回 0（getCounts 永不返回 null，已去掉冗余 null 检查）
- deleted 行也读（无害，前端不显示）
- **review 阶段加钳制**：`if (likeCount > Integer.MAX_VALUE) likeCount = Integer.MAX_VALUE;`。SDS 段是无符号 32 位，DTO `CommentItemResponse.likeCount` 是 Integer（帖子 DTO 是 Long），防 (int) 截断成负数（review I1，本 diff 引入的 correctness gap）。

无其它文件改动。

## 5. 验证

- [x] 后端 mvn test 51 测试全过
- [x] 重启后端，list 返回被赞评论 likeCount 正确：
  - 331035581662498816: likeCount=2 ✓
  - 330980162864812032: likeCount=1 ✓
  - 330980162634125312: likeCount=2 ✓
  - 330980162122420224: likeCount=1 ✓
  - 330980159899439104: likeCount=1（别人赞的，liked=false 但计数可见）✓
- [x] liked 字段仍正常（未受影响）
- [x] 历史脏数据已清理（5 个丢精度 SDS key 已删）

## 6. 影响面回归

- 评论 list likeCount：修复，读 SDS ✓
- 评论 liked 字段：未受影响（仍读位图）✓
- 帖子 likeCount：未改动 ✓
- counter 聚合链路：未改动 ✓
- 性能：item() 每条多一次 getCounts（读 SDS），一页 20 条 20 次 Redis GET，与帖子一致，可接受

## 7. 顺手发现

- `comments.like_count` 和 `comments.reply_count` MySQL 字段现已无人更新（likeCount 改读 SDS），是死字段。可后续清理 schema 或留作兼容。不在本 issue 范围。
- replyCount 仍读 MySQL（`row.getReplyCount()`），若回复功能上线后也有同样问题需同样修复。记待观察。

## 8. 提交

改动文件：CommentServiceImpl.java + analysis.md + fix-note.md + review.md

cs-code-review 已过（reviewer=subagent，passed，round 1）。review I1（截断）已修，N1/N2 顺手修，R1–R4 登记 follow-up。
