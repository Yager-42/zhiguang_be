# Design: add-leaf-id-service

## 关键决策

### 1. 现有 SnowflakeIdGenerator：替换

删除现有 `SnowflakeIdGenerator` 具体类，统一由 `IdService` 内部实现 Snowflake 逻辑。

- `KnowPostServiceImpl` 改注入 `IdService`，使用 `IdNamespace.POST` / `IdNamespace.OUTBOX_EVENT`
- `RelationServiceImpl` 关系行 ID（例如 `following.id`）从 `ThreadLocalRandom` 迁移到 `IdService.nextId(IdNamespace.RELATION)`；关系 outbox ID 使用 `IdNamespace.OUTBOX_EVENT`，不得复用 `OUTBOX_EVENT` 生成关系行 ID。
- 不保留原类，不做包装层，彻底清理硬编码配置

### 2. workerId / datacenterId：application.yml 静态配置

```yaml
id:
  snowflake:
    worker-id: ${SNOWFLAKE_WORKER_ID:1}
    datacenter-id: ${SNOWFLAKE_DATACENTER_ID:1}
```

本地默认值 `1`，与现有 MinIO/MySQL 配置风格一致。当前单实例部署，无需动态分配。

### 3. Namespace 路由：代码枚举

定义 `IdNamespace` 枚举，每个值静态绑定 ID 模式，路由关系在编译期确定：

```java
public enum IdNamespace {
    // Snowflake：高频业务实体
    POST(IdMode.SNOWFLAKE),
    COMMENT(IdMode.SNOWFLAKE),
    PENDING_COMMENT(IdMode.SNOWFLAKE),
    PUBLISH_ATTEMPT(IdMode.SNOWFLAKE),
    RELATION(IdMode.SNOWFLAKE),
    OUTBOX_EVENT(IdMode.SNOWFLAKE),

    // Segment：低频后台任务
    RECONCILIATION_TASK(IdMode.SEGMENT),
    ADMIN_OPERATION(IdMode.SEGMENT),
    AUDIT_LOG(IdMode.SEGMENT);

    public final IdMode mode;
    IdNamespace(IdMode mode) { this.mode = mode; }
}
```

### 4. Segment 号段步长：固定 1000

`leaf_alloc` 表中所有 Segment biz tag 的 `step = 1000`。`reconciliation_task`、`admin_operation`、`audit_log` 均为低频操作，动态步长无实际收益。

### 5. 时钟回拨策略：保持现有逻辑

| 回拨幅度 | 处理方式 |
|---------|---------|
| ≤ 5ms | 等待时钟追上后继续 |
| > 5ms | 抛出 `ClockBackwardException`，调用方感知 |

单实例本地部署，NTP 引起的回拨通常 < 1ms，5ms 容忍已足够。

### 6. Segment 号段加载失败：双 Buffer 兜底

双 Buffer 工作机制：
- **主 Buffer**：当前消费中的号段
- **备用 Buffer**：后台异步预加载的下一个号段

```
主 Buffer 消耗到 threshold（50%）
    └─ 后台异步加载备用 Buffer
           ├─ 加载成功 → 备用 Buffer 就绪
           └─ 加载失败 → 记录日志，继续重试

主 Buffer 耗尽
    ├─ 备用 Buffer 就绪 → 切换，后台加载新备用
    └─ 备用 Buffer 未就绪 → 阻塞等待（最多 timeout ms）→ 超时则抛异常
```

DB 短暂抖动不影响服务，备用 Buffer 耗尽后才对外报错。

---

## IdService 接口

```java
public interface IdService {
    long nextId(IdNamespace namespace);
}
```

调用示例：

```java
long commentId = idService.nextId(IdNamespace.COMMENT);
long taskId    = idService.nextId(IdNamespace.RECONCILIATION_TASK);
```

---

## leaf_alloc 表结构

```sql
CREATE TABLE leaf_alloc (
  biz_tag     VARCHAR(128) NOT NULL,
  max_id      BIGINT       NOT NULL DEFAULT 1,
  step        INT          NOT NULL DEFAULT 1000,
  description VARCHAR(256),
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_tag)
);

INSERT INTO leaf_alloc (biz_tag, max_id, step, description) VALUES
  ('reconciliation_task', 1, 1000, '对账任务 ID'),
  ('admin_operation',     1, 1000, '管理操作 ID'),
  ('audit_log',           1, 1000, '审计日志 ID');
```

---

## 迁移范围

| 模块 | 旧方式 | 新方式 |
|------|--------|--------|
| `KnowPostServiceImpl` | `SnowflakeIdGenerator.nextId()` | `idService.nextId(POST / OUTBOX_EVENT)` |
| `RelationServiceImpl` 关系行（如 `following.id`） | `ThreadLocalRandom.current().nextLong(...)` | `idService.nextId(RELATION)` |
| `RelationServiceImpl` 关系 outbox | 随机 / 本地生成 | `idService.nextId(OUTBOX_EVENT)` |
| 评论系统 | 无 | `idService.nextId(COMMENT / PENDING_COMMENT)` |
| Publish Pipeline | 无 | `idService.nextId(PUBLISH_ATTEMPT)` |
| 对账系统 | 无 | `idService.nextId(RECONCILIATION_TASK)` |
| 用户注册 | MySQL 自增 | **不变** |
