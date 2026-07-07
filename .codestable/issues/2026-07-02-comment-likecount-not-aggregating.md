# Issue: 评论 likeCount 不聚合（counter 系统问题）

> 来源：feature 2026-07-02-frontend-comment-like-delete 联调时发现
> 类型：bug（counter 系统既有问题，非评论 feature 引入）

## 现象

评论点赞后：
- `liked` 字段正确（位图置位，isLiked=true，刷新保持）✓
- `likeCount` 一直是 0，不随点赞数增长 ✗

对比：帖子（knowpost）点赞的 likeCount 聚合正常（detail 接口返回准确值）。

## 证据

- 评论点赞 event 已发到 Kafka `counter-events` topic（topic 里有 24 条 entityType=comment 的 event）
- CounterAggregationConsumer（groupId=counter-agg）已消费到 offset=52
- 但评论的 agg 桶 `agg:v1:comment:{eid}` 为空（可能已 flush）
- SDS 快照 `cnt:v1:comment:{eid}`：部分评论有 key，但**被赞的评论（如 330980162634125312）没有 SDS key**
- 帖子 agg/SDS 链路正常（likeCount 准）

## 根因方向（待 issue 分析确认）

counter 聚合链路对 comment entityType 不工作。可能：
1. CounterAggregationConsumer.onMessage 消费 comment event 时 increment 异常被 catch 吞掉
2. flush 把 agg 刷到 SDS 时 eid 解析/写入失败
3. comment 的 entityId（snowflake long）在某处丢精度

## 影响范围

所有评论的 likeCount 显示为 0（或不变）。不影响 liked 状态、点赞/取消/删除功能。

## 建议

走 `cs-issue-analyze` 深挖根因。不在评论点赞 feature 内修（超出范围）。
