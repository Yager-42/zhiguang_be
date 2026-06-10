# OpenSpec Changes 执行顺序

## 决策原则

- **Foundation-first**：先建基础层，避免后期返工
- **最大并行**：无依赖关系的变更同时推进
- **对账尽早介入**：数据对账系统越晚上线，积压的不一致越难排查

## 执行阶段

### 阶段 0 — 架构指导（最先阅读）

| 变更 | 说明 |
|------|------|
| `split-to-microservices` | 纯架构指导，明确接口边界和解耦原则。所有后续变更都必须遵循其约束，**先读再动手**。 |

### 阶段 1 — 基础层（并行）

| 变更 | 说明 |
|------|------|
| `add-leaf-id-service` | 统一 ID 生成接口，comment、publish pipeline、reconciliation 均依赖它 |
| `add-cassandra-text-storage` | 文本事实存储，comment system 和 publish pipeline 均依赖它 |

两者互不依赖，可并行实现。单人开发时建议先做 `add-leaf-id-service`（更小，能快速为后续变更提供 ID 接口）。

### 阶段 2 — 核心服务层（并行，等阶段 1 完成）

| 变更 | 说明 |
|------|------|
| `eventize-publish-pipeline` | 依赖 Cassandra（读取/校验文本）和 Leaf ID（生成 attempt ID） |
| `add-comment-system` | 依赖 Cassandra（存储评论文本）和 Leaf ID（生成 comment ID） |
| `add-data-reconciliation` | **同步开始**，先覆盖 publish pipeline + Cassandra 的对账逻辑；随着其他变更完成逐步扩展覆盖范围 |

`eventize-publish-pipeline` 和 `add-comment-system` 互不依赖，可并行。

### 阶段 3 — 功能层（等阶段 2 全部完成）

| 变更 | 说明 |
|------|------|
| `add-recommendation-and-follow-feed` | 消费 `content_published` 事件（来自 publish pipeline）和评论反馈事件（来自 comment system），两者都需就绪 |
| `add-data-reconciliation`（续） | 扩展覆盖 comment 和 recommendation 的对账逻辑 |

## 依赖关系图

```
split-to-microservices (阶段0，架构约束)
         │
         ▼
add-leaf-id-service ──┐
                      ├──▶ eventize-publish-pipeline ──┐
add-cassandra-        │                                 ├──▶ add-recommendation-and-follow-feed
  text-storage ───────┤                                 │
                      └──▶ add-comment-system ──────────┘
                                    │
                                    ▼
                         add-data-reconciliation（贯穿阶段2-3）
```

## 前置检查

开始实现某个变更前，确认其前置变更已完成：

| 变更 | 前置 |
|------|------|
| `add-leaf-id-service` | 无 |
| `add-cassandra-text-storage` | 无 |
| `eventize-publish-pipeline` | `add-leaf-id-service`, `add-cassandra-text-storage` |
| `add-comment-system` | `add-leaf-id-service`, `add-cassandra-text-storage` |
| `add-data-reconciliation` | `eventize-publish-pipeline`（最低要求，可增量扩展） |
| `add-recommendation-and-follow-feed` | `eventize-publish-pipeline`, `add-comment-system` |
