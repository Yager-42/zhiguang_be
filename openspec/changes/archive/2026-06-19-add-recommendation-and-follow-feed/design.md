# Design: add-recommendation-and-follow-feed

## 关键决策

### 1. Gorse item upsert：异步消费 `content_published`（经 Canal CDC）

发布 pipeline 写 MySQL `outbox` 行后，Canal CDC 转发到 `canal-outbox` Kafka topic；推荐消费者订阅并异步 upsert Gorse item。

- 不在 publish pipeline 内同步调 Gorse，避免外部服务可用性影响发布流程
- 与 ES/RAG 消费者对称，统一经 `canal-outbox` 解耦（`content_published` 无独立 topic，详见「数据流」）
- 失败依赖 `canal-outbox` 重投（at-least-once）+ 幂等 upsert；**不引入 `reconciliation_task`**（`ReconciliationService` 尚不存在，且重投+幂等已覆盖瞬时失败）

### 2. 作者分级阈值：application.yml 配置项（两档）

```yaml
feed:
  fanout:
    push-pull-threshold: 10000   # < 阈值 = 普通作者(全量 push)；≥ 阈值 = 大V(仅写 author_feed，粉丝 pull)
  inbox:
    ttl-days: 30                 # Cassandra feed_inbox / feed_author_feed 的 default_time_to_live（天）
  cache:
    timeline-ttl-seconds: 300    # Redis feed:timeline:{userId} 缓存 TTL
    author-head-ttl-seconds: 120 # Redis feed:author:{authorId}:head 缓存 TTL
```

阈值随平台增长可调，不应硬编码。原三档（普通/大V/超大V）中「超大V」与「大V」行为相同（都 pull），已拉平为两档，`super-large-author-threshold` 移除。

### 3. 关注流存储：Cassandra 持久层 + Redis 活跃缓存（混合），原生 TTL

关注流 inbox/author_feed 是**派生索引**（事实源是 MySQL `know_posts` + ES + Cassandra 正文），不是事实源。原 Redis ZSet 单层、无 TTL、500 条上限导致内存随用户量线性膨胀。改为分层：

- **持久层（Cassandra）**：`feed_inbox`（粉丝维度，push 半边）+ `feed_author_feed`（作者维度，pull 半边），聚簇行 `(publish_ts DESC, content_id DESC)`，`default_time_to_live=2592000`（30 天），`gc_grace_seconds=0`（单节点 RF=1），**TimeWindowCompactionStrategy**（TTL 时间序列专用压缩，整窗口过期直接丢 SSTable，避免墓碑堆积）。
- **活跃缓存（Redis，cache-aside）**：`feed:timeline:{userId}`（合并后有序元组列表，短 TTL）+ `feed:author:{authorId}:head`（作者 pull 头部缓存）。miss 回源 Cassandra。**不另建活跃信号子系统**——「谁读 feed 谁进缓存，LRU 淘汰」，缓存即活跃集。
  - `feed:author:head` 是**真正的热键**（同一大V被多粉同时拉），单航班填充吸收读放大；`feed:timeline` 按 userId 隔离、无击穿，**不做 single-flight**（`ponytail: per-user key，无惊群`）。
  - `ponytail:` 保留 `feed:timeline`（锁定 D1）；需 benchmark `SELECT … FROM feed_inbox WHERE user_id=? LIMIT 20`（30 天填充、RF=1）p99 校验缓存价值——若 p99 < 8ms，后续可评估精简为只留 `feed:author:head`、直读 Cassandra。

**关键约束**：
- `publish_ts` 一次性从 `content_published` 事件捕获（毫秒），重放原样复用，**dispatcher 内禁用 `now()`**——否则 at-least-once 重放会写新行造成重复。`content_id` 是 snowflake（高位毫秒时间戳），作 DESC 权威 tiebreaker。
- **不做主动 DELETE 清理**：删除走读时修复（hydrate 过滤 `status`/`visible`），脏行靠 TTL 过期。TWCS 表上 DELETE 会让墓碑落到原插入窗口之外、无法随窗口整删，严格劣于 TTL。

Schema 见 `db/cassandra/init.cql`。

### 4. 大V 不做活跃子集 push：纯 pull + 缓存吸收读放大

大V（≥ 阈值）只写 author_feed，粉丝读时 pull，靠 `feed:author:{authorId}:head` 热缓存吸收读放大（活跃粉 pull = 1 次缓存命中）。**不上「推活跃子集」**——它解决的读放大已被缓存层解决，再上是给已解决的问题装第二套方案（且需另建活跃信号子系统）。这是正确的非最小实现，不是省略。

### 5. 首页混排：优先级顺序填充，总量固定 20 条

```
1. 取关注流候选（每路切片 LIMIT 20，归并后取 top-20）
2. 不足则用 Gorse 推荐候选补齐
3. 仍不足则用热点内容兜底
4. 去重 + 可见性过滤 + Hydration
```

固定比例在关注少的用户（新用户）体验差，优先级填充更自然地适配不同用户状态。

### 6. 首页接口：替换现有 `GET /api/v1/knowposts/feed`（feature flag）

原 `/feed` 改为返回混排结果（登录用户），旧简单公开列表逻辑降级为热点兜底数据源；新增 `/feed/follow` 返回纯关注流。**feature flag 控制**，可一键回滚到旧行为。客户端无需改接口地址。

---

## 作者分级与 Fanout 策略

| 粉丝数 | 分级 | 发布时行为 |
|--------|------|-----------|
| < 10,000 | 普通作者 | Push：写入所有粉丝 `feed_inbox`（**不写** author_feed） |
| ≥ 10,000 | 大 V | Pull：只写 `feed_author_feed`，粉丝读 feed 时拉取（命中 `feed:author:{authorId}:head` 缓存） |

> 作者分级用 MySQL `countFollowerActive`（fanout 时查一次，无缓存 — `ponytail: 一次发布的值不值得缓存`）。分级错误被读路径多源合并兜住（follow 命中即拉 author_feed），不丢数据。

---

## 存储结构

### Cassandra（持久层，Schema 见 `db/cassandra/init.cql`）

| 表 | 主键 | 说明 | TTL |
|----|------|------|-----|
| `feed_inbox` | `(user_id, publish_ts, content_id)` | 粉丝收件箱，普通作者 fanout push 落点 | 30 天（TWCS） |
| `feed_author_feed` | `(author_id, publish_ts, content_id)` | 作者最近发文，大V pull 源 | 30 天（TWCS） |

> 两表都 `WITH CLUSTERING ORDER BY (publish_ts DESC, content_id DESC)` + `default_time_to_live=2592000` + `gc_grace_seconds=0` + `TimeWindowCompactionStrategy`（`DAYS`/`1`）。

### Redis（活跃缓存，cache-aside，miss 回源 Cassandra）

| Key | 类型 | 说明 | TTL |
|-----|------|------|-----|
| `feed:timeline:{userId}` | String(JSON) | 合并后有序元组 `(publish_ts, content_id, author_id)`，读时填充（无 single-flight） | ~300s |
| `feed:author:{authorId}:head` | String(JSON) | 大V pull 头部，热键单航班填充 | ~120s |

写入路径只写 Cassandra；Redis 在**读时**填充。Fanout 不写 Redis（无活跃信号，写穿无意义）。

> 术语：feed 侧 `inbox`/`author_feed` 与 MySQL 事务型 `outbox` 表是不同概念，见 `CONTEXT.md`。

---

## 数据流

### 发布触发 fanout（经 Canal CDC）

> `content_published` 不是独立 Kafka topic。发布写 MySQL `outbox` 行 → Canal CDC 转发到 `canal-outbox` topic（需 `canal.enabled=true`）。`TimelineDispatcher` 作为 `canal-outbox` 第三个消费者（groupId 如 `feed-timeline-consumer`），过滤 `eventType=content_published`，与现有 `relation-outbox-consumer`/`search-index-consumer` 同模式。

```
publish pipeline → MySQL outbox(content_published) → Canal → canal-outbox Kafka
  ├─ 推荐消费者：upsert Gorse item（失败 → 靠重投+幂等，无 reconciliation_task）
  └─ TimelineDispatcher（过滤 content_published）
       └─ TimelineExecutor（CqlSession.executeAsync，inflight ≤256，每消息截止超时）
              ├─ 普通作者：按 (created_at, from_user_id) keyset 分页取粉丝 → 逐粉 async INSERT feed_inbox（不写 author_feed）
              └─ 大V：仅 INSERT feed_author_feed(author_id, publish_ts=事件publishedAt, content_id)（不写粉丝 inbox）
       失败：任意单粉写失败即整批 no-ack → Kafka 重投（at-least-once）；幂等 upsert（PK 含 content_id）使整批盲重跑安全。无 reconciliation_task（`ponytail: 重投+幂等已覆盖瞬时失败`）。
```

> 粉丝分页必须用 keyset（当前 `RelationMapper` 全是 `LIMIT/OFFSET`，深度分页退化）。

### 行为反馈投递 Gorse

```
用户点赞 / 收藏 / 评论 / 关注
  └─ 异步投递 feedback 到 Gorse
       └─ 失败 → 靠重投+幂等（不引入 reconciliation_task）
```

### 首页 Feed 请求

```
GET /api/v1/knowposts/feed（登录用户；匿名走热点兜底）
  │
  ├─ 0. miss 时执行 1-3 后写 feed:timeline:{userId}（无 single-flight — per-user key）
  ├─ 1. 取关注流候选（每路切片 LIMIT 20，墓碑预算 ≤1000 行/切片）
  │    ├─ 自己 inbox：feed:timeline 缓存 → miss → Cassandra feed_inbox 切片
  │    └─ 关注的大V：feed:author:{authorId}:head 缓存 → miss → Cassandra feed_author_feed 切片
  ├─ 2. 多路归并（publish_ts DESC, content_id DESC），按 content_id 去重
  ├─ 3. Gorse 推荐候选（关注流不足时补）
  │    └─ RecommendationEngine.recommend(userId, count)
  │         └─ Gorse 不可用 → 热点兜底（原 listFeedPublic 逻辑）
  ├─ 4. 热点兜底（仍不足时补）
  ├─ 5. Hydration（feed:item:{id} 片段缓存 → miss Cassandra 正文 + MySQL 元数据）
  ├─ 6. 读时修复：status='published' AND visible∈{public,followers} 过滤（school 不进关注流，本变更不做 viewer-scope）
  └─ 7. 游标 (publish_ts, content_id) 返回
```

---

## RecommendationEngine 接口

```java
// ponytail: 保留接口因 spec 要求该 seam；若第二个实现始终不落地，可塌缩为具体 GorseRecommendationService。
public interface RecommendationEngine {
    List<RecommendationCandidate> recommend(long userId, int count);
}

// ponytail: score/reason 在优先级填充混排里用不到（按来源顺序，不按分排序），已删；只留 contentId + source(便于追踪)。
public record RecommendationCandidate(
    long contentId,
    String source   // "gorse" | "hot" | "follow"
) {}
```

`GorseRecommendationAdapter` 实现该接口，Gorse 不可用时 fallback 返回热点内容候选。

---

## Gorse 集成配置

```yaml
recommendation:
  gorse:
    endpoint: ${GORSE_ENDPOINT:http://localhost:8087}
    api-key: ${GORSE_API_KEY:}
    timeout-ms: 300
    enabled: ${GORSE_ENABLED:false}
```

`enabled=false` 时 Adapter 直接返回热点兜底，本地开发无需启动 Gorse。
