# Design: add-recommendation-and-follow-feed

## 关键决策

### 1. Gorse item upsert：异步消费 `content_published` 事件

发布 pipeline 发出 `content_published` Kafka 事件后，推荐消费者订阅并异步 upsert Gorse item。失败时写 `reconciliation_task` 补偿。

- 不在 publish pipeline 内同步调 Gorse，避免外部服务可用性影响发布流程
- 与 ES/RAG 消费者对称，统一通过 `content-published` 主题解耦

### 2. 作者分级阈值：application.yml 配置项

```yaml
feed:
  fanout:
    large-author-threshold: 10000     # 普通作者 → 大V 分界
    super-large-author-threshold: 500000  # 大V → 超级大V 分界
    inbox-max-size: 500               # 关注流 inbox 最大条数
```

阈值是运营决策，随平台增长可能调整，不应硬编码。

### 3. 关注流 inbox：固定条数上限，不设 TTL

- Redis ZSet Key：`feed:inbox:{userId}`，score = publish_time 毫秒时间戳
- 每次 push 后执行 `ZREMRANGEBYRANK feed:inbox:{userId} 0 -(maxSize+1)` 裁剪，保留最新 500 条
- ZSet 本身**不设 TTL**，避免活跃用户 inbox 频繁重建

作者 posts 集合 Key：`feed:author:posts:{authorId}`，同样保留最新 500 条，不设 TTL。

### 4. 活跃粉丝优先 push：v1 跳过，普通作者全量 push

普通作者（< 10k 粉丝）直接 push 给所有粉丝，不做活跃过滤。粉丝数 < 10k 时 Redis 写操作完全可接受，活跃过滤的收益不足以抵消复杂度。有压力时再加。

### 5. 首页混排：优先级顺序填充，总量固定 20 条

```
1. 取关注流候选（最多 20 条）
2. 不足则用 Gorse 推荐候选补齐
3. 仍不足则用热点内容兜底
4. 去重 + 可见性过滤 + Hydration
```

固定比例在关注少的用户（新用户）体验差，优先级填充更自然地适配不同用户状态。

### 6. 首页接口：替换现有 `GET /api/v1/knowposts/feed`

原 `/feed` 改为返回混排结果，旧简单公开列表逻辑降级为热点兜底的数据源，不单独暴露。客户端无需改接口地址。

---

## 作者分级与 Fanout 策略

| 粉丝数 | 分级 | 发布时行为 |
|--------|------|-----------|
| < 10,000 | 普通作者 | Push：写入所有粉丝 `feed:inbox:{userId}` |
| 10,000 – 499,999 | 大 V | Pull：写入 `feed:author:posts:{authorId}`，粉丝读 Feed 时主动拉取 |
| ≥ 500,000 | 超级大 V | 不 push 到粉丝 inbox；写入 `feed:author:posts:{authorId}` 或等价 pull 索引，供关注流 pull，同时可进入热点和 Gorse 推荐 |

---

## Redis 数据结构

| Key | 类型 | 说明 | 上限 |
|-----|------|------|------|
| `feed:inbox:{userId}` | ZSet | 普通作者 fanout push 收件箱，score=publish_time | 500 条 |
| `feed:author:posts:{authorId}` | ZSet | 大V/超级大V 最近发布集合，供粉丝 pull，score=publish_time | 500 条 |

---

## 数据流

### 发布触发推荐更新

```
publish pipeline → content-published Kafka
  └─ 推荐消费者
       ├─ upsert Gorse item（失败 → reconciliation_task）
       └─ fanout push / 写 author posts（按作者分级）
              ├─ 普通作者：ZADD feed:inbox:{粉丝id} + ZREMRANGEBYRANK 裁剪
              ├─ 大V：ZADD feed:author:posts:{authorId} + 裁剪
              └─ 超级大V：不写粉丝 inbox；ZADD feed:author:posts:{authorId} + 裁剪，并继续进入热点/Gorse 推荐
```

### 行为反馈投递 Gorse

```
用户点赞 / 收藏 / 评论 / 关注
  └─ 异步投递 feedback 到 Gorse
       └─ 失败 → reconciliation_task
```

### 首页 Feed 请求

```
GET /api/v1/knowposts/feed
  │
  ├─ 1. 取关注流候选
  │    ├─ 普通作者内容：读 feed:inbox:{userId} ZSet
  │    └─ 大V/超级大V内容：读 feed:author:posts:{authorId} 或等价 pull 索引（遍历关注的大V/超级大V）
  │
  ├─ 2. Gorse 推荐候选（不足时补）
  │    └─ RecommendationEngine.recommend(userId, count)
  │         └─ Gorse 不可用 → 热点兜底（原 listFeedPublic 逻辑）
  │
  ├─ 3. 热点兜底（仍不足时补）
  │    └─ published + public 按 publish_time DESC
  │
  ├─ 4. 去重（by postId）
  ├─ 5. 可见性 & 删除状态过滤
  └─ 6. Hydration（帖子详情 + 作者信息 + 计数 + liked/faved 状态）
```

---

## RecommendationEngine 接口

```java
public interface RecommendationEngine {
    List<RecommendationCandidate> recommend(long userId, int count);
}

public record RecommendationCandidate(
    long contentId,
    double score,
    String reason,
    String source   // "gorse", "hot", "follow"
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
