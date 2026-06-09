# eventize-publish-pipeline

## Why

当前发布流程已有草稿、预签名直传、上传确认和同步发布，但 ES 索引、RAG 索引、Feed 缓存、推荐事件等派生处理分散。发布应成为可追踪、可重试、可补偿的 pipeline。

## What

- 新增 `publish_attempt`，发布接口进入 `publishing` 状态并返回 `202 Accepted`。
- pipeline 完成基础校验、对象验收、Cassandra 正文验收、内容解析、安全预留、正式发布。
- 正式发布后触发 ES 索引、RAG 预索引、Feed 缓存失效、计数初始化、推荐事件。
- 关键动作失败阻止发布，派生动作失败只重试补偿。
- 新增状态：`publishing`、`publish_failed`，保留 `rejected`。

## Impact

- `know_posts` 需要记录发布状态、attempt ID 和失败原因。
- 发布接口语义从同步 `204` 调整为异步接受。
- 搜索、RAG、Feed、推荐、对账需要消费或补偿发布派生任务。
- 发布流程需要使用 Cassandra 作为文字正文事实源。

## Non-goals

- 不实现完整内容审核系统。
- 不因 ES/RAG/推荐失败回滚已发布内容。
- 不拆微服务。
