# Enhancement 边界契约：promotion-bprime-hotpath-throughput-v1

| 字段 | 值 |
|------|-----|
| **enhancement_contract_version** | `0.1.0` |
| **status** | **draft**（待 grill 收敛；收敛后冻结为 frozen） |
| **updated** | 2026-08-10 |
| **enhancement_id** | `promotion-bprime-hotpath-throughput-v1` |
| **性质** | **enhancement（性能/可靠性增强）**——不改任何对外契约（HTTP API、WS 协议、ACK 字段、错误码均保持），不引入新用户可见能力 |
| **范围** | 单机、单 Spring Boot 实例下的 `promotion.bprime`（REDIS_STREAM 决策路径）竞价热链路吞吐与洪峰稳定性优化 |
| **代码基线** | `com.tongji.promotion.bprime`（service/redis/realtime）、`com.tongji.promotion.schedule`、`src/main/resources/redis/lua/promotion-auction-*.lua`、`application.yml`、`loadtest/` |
| **参考源** | `live-auction-system`（Go）竞价链路逐行对比（Redis Lua 权威裁决 + Stream 事件日志 + Pub/Sub 唤醒 + 网关侧 fast-reject）；仅吸收可映射到当前代码的设计，不复制其业务语义（单物品升价拍卖） |

---

## 0. 效力与状态

1. 本文是推广竞价热链路性能改造的 **draft enhancement 边界**。标为 **locked** 的决定不得由实现者自行改写；重大变更须重新 grill 并提升 `enhancement_contract_version`。
2. 本文只以当前生产代码、运行配置、数据库结构和已观察压测结果为基线；README、CONTEXT、历史架构文档不构成本 enhancement 的规范源。
3. 本 enhancement 覆盖：出价入站预拒（T1）、Lua 裁决脚本瘦身（T2）、关窗调度（T3）、幂等桶瘦身（T4）、洪峰配置基线验证（T5）。
4. 本 enhancement **不承诺固定绝对 QPS**。完成标准是相同单机、相同数据、相同负载下取得可重复的正向提升，且功能正确性（版本连续性、幂等、对账）不回退。
5. 单机持续过载时的行为边界保持不变：线程池队列满后按现有 `UNAVAILABLE` 语义返回；本 enhancement 不引入应用层 429/503。

---

## 1. 动机与基线（grill 待收敛）

### 1.1 背景：与 Go 参考实现的差异

对 `live-auction-system`（Go + Redis Lua）逐行对比后，确认以下性能相关差异（详见会话分析，此处仅列与本 enhancement 相关的）：

| # | Go 实现 | 当前 Java 实现 | 影响 |
|---|---------|---------------|------|
| D1 | 网关侧 fast-reject：缓存最新广播价，`出价 <= 缓存价` 本地拒，省 EVALSHA；实测 20k bids/s 下吸收 99.97% 无效出价，ack p95 3.5ms | 无实现（`PromotionBidFastRejectionReason` 为死代码，零引用；metrics 无 fast-reject 计数） | 无效出价全部进 Lua 裁决链路 |
| D2 | `place_bid.lua` 更轻：4 次 TYPE、1 次 HMGET、1 次 XREVRANGE、ZADD GT 单写 | `decision.lua` 更重：7 次 TYPE、XINFO STREAM、桶 HGET 最多 6 次、ZREM+ZADD 双写 | Redis 单线程指令数 = 单窗口吞吐上限 |
| D3 | Timer 100ms tick + `auction:active` ZSET 按 endAt 排序，只取到期窗口 | `@Scheduled(fixedDelay=30s)` + MySQL `listClosableWindows` 扫描 | 终态广播/结算延迟 0-30s（300 倍差距） |
| D4 | dedupe 每用户 Hash，1 次 HGET | command 时间桶（当前 + 前 5 桶），最多 6 次 HGET | 每出价 5 次多余指令 |
| D5 | `load` 命令内置 p95 门禁 + 退出码，回归可重复 | 独立 k6 脚本，结果受配置漂移影响 | 20k/60k 结果矛盾无法归因 |

### 1.2 已观察基线（`loadtest/results/`，2026-08-09）

下列数字仅用于确定改进方向，正式验收必须在同一环境重新采集 baseline：

| 场景 | 出价速率 | ACK 完整性 | ACK 延迟 | 解读 |
|------|---------|-----------|---------|------|
| `promotion-multi-accepted-heavy`（40 VU 全加价） | 1,376 accepts/s | 100% | p95 65ms / p99 269ms | 纯写负载下裁决层能力上限 |
| `promotion-ws-realistic-9k`（500 VU 行为混合：54.8% 加价 / 25% stale / 10% retry / 5% double / 5% escrow 不足） | 1,032/s | 97.2%（missing 2,242） | p95 442ms / p99 536ms | **约 40% 出价注定失败却全部进 Lua** |
| `promotion-ws-fast-reject-20k`（100 VU 同价洪峰） | 10,756/s | **6.2%**（missing 280,718） | p95 13.5s | 洪峰下 ACK 通道打爆 |
| `promotion-ws-native-unlimited-60k`（300 VU） | 27,745/s | 100% | p95 14ms / p99 19ms | 与上行矛盾，配置漂移待归因（T5） |

### 1.3 目标

1. **T1 完成后**：realistic 场景 Lua 裁决调用减少约 40%（stale/retry/double 出价本地拦截），ack p95 从 442ms 量级显著下降。
2. **T2 完成后**：单窗口接受吞吐（multi-accepted-heavy 形态）p99 改善，Lua 指令数下降 30%+（对照记录）。
3. **T3 完成后**：窗口结束到 `WINDOW_CLOSED` 终态广播/结算延迟从 0-30s 收敛到 **< 2s**。
4. **T4 完成后**：幂等语义（TTL 300s、冲突检测、重放还原）完全不变，Lua 每出价指令数下降。
5. **T5 完成后**：产出固定配置下的洪峰对照基线，明确 20k 崩溃的瓶颈归属（线程池队列 vs Lua vs 广播）。

---

## 2. 任务定义

### T1（P0）：网关侧每 campaign 价格缓存 fast-reject

**目标**：把注定失败的出价（`BID_NOT_HIGHER` 类）在网关本地拦截，不进入 Lua 裁决链路。

**设计**：

```text
Caffeine 缓存：{auctionWindowId, campaignId} -> currentBidAmount（只升不降）
更新源：PromotionRedisStreamProjector 读 Stream 时（单消费者、天然有序）
判据：bidAmount <= cachedBidAmount -> 本地返回 REJECTED(BID_NOT_HIGHER)，零 Redis 往返
```

**locked 守卫**（正确性不变量）：

1. **幂等优先**：`commandId` 曾在桶中出现过（重试）必须放行进 Lua 重放原裁决——本地缓存无法判断幂等，守卫是"只在缓存命中且该 commandId 未被本进程处理过时预拒"；实现时以"预拒前检查最近 commandId 集合"或"Lua 重放优先级"为准，**禁止预拒任何重试**。
2. **时间边界**：`windowEndAt` 前 `margin`（默认 2s，配置化）内禁用预拒，让 Lua 的 Redis TIME 做最终裁决。
3. **终态禁用**：收到 `WINDOW_CLOSED` 或窗口 `SETTLED` 后清空/禁用该窗口缓存。
4. **错误码一致**：本地预拒返回的 ACK 必须与 Lua 返回**字节级语义一致**（`status=REJECTED, rejectionReason=BID_NOT_HIGHER`），客户端无感。
5. **只升不降**：缓存值随接受的决策单调上升；投影失败/回滚时不得下降缓存（宁可陈旧误拒低频，不可接受后缓存落后——陈旧由时间边界守卫兜底）。
6. **明确不做**：escrow 本地预拒（route 缓存 `authorizedAmount` 追加授权后旧值会误拒新出价，风险 > 收益）；多 campaign 无全局价格，不做 Go 式"全局当前价"预拒。

**配套**：metrics 新增 `promotion.bprime.ingress.fast-rejected`（计数，按 rejectionReason 标签）；loadtest 脚本的 `*_bid_fast_rejected` 计数器语义改为"服务端本地预拒"（当前按 ACK 的 reason 统计，无法区分来源，需加服务端指标或 ack 字段区分——**不改 WS 协议字段**，用服务端 metrics 区分）。

**验收**：
- realistic 场景 Lua 调用量下降约 40%（对照服务端 metrics：`ingress.accepted` 与 `decision` 计数之差）；
- 正确性测试全绿：幂等重试重放、窗口关闭前 margin 内出价放行、终态后预拒禁用、缓存单调性；
- 压测 ack p95 较 baseline 可重复改善。

### T2（P1）：Lua 裁决脚本瘦身

**目标**：降低 `promotion-auction-decision.lua` 指令数（Redis 单线程，指令数 = 吞吐上限）。

**改动清单**（逐项对照 Go `place_bid.lua`）：

| 项 | 现状 | 改为 | 依据 |
|----|------|------|------|
| L1 | `XINFO STREAM` 取 last-generated-id | `XREVRANGE events + - COUNT 1` | Go 同款，更轻 |
| L2 | 7 次 `TYPE` 守卫 | 合并/减少（如 state/escrow 的 TYPE 判断合并到 HMGET 结果校验） | Go 仅 4 次 |
| L3 | `ZREM` + `ZADD` 双写排名 | `ZADD GT`（Redis 6.2+，仓库 Redis 7.4 满足）单写 | Go 同款；注意 `GT` 语义下 member 不变的场景 | 
| L4 | 条件 `EXPIRE` 逐 key | 合并到写路径固定 TTL 或 `PEXPIRE` 批量 | 指令减 |
| L5 | 桶 HGET 最多 6 次 | 随 T4 减少桶数 | 联动 |

**locked**：不改裁决语义（校验顺序、错误码、Stream ID `<version>-0`、decisionVersion 递进）；不改 Lua 返回的 JSON 结构。

**验收**：`multi-accepted-heavy` 形态复测，p99 较 baseline 改善；记录改造前后脚本指令数（Redis `SCRIPT` 不可直接度量，用 EVAL 前后耗时分布 + 压测对比佐证）。

### T3（P1）：关窗调度秒级化

**目标**：`WINDOW_CLOSED` 终态广播与结算延迟从 0-30s 收敛到 < 2s。

**设计**（二选一，grill 定夺）：
- A. 复用 `active-streams` 注册表：新增 Redis ZSET `promotion:auction:closing`（member=windowId, score=windowEndAtEpochMs），关窗调度改为秒级扫描 `ZRANGEBYSCORE` 取到期窗口；close 的 Lua 内 Redis TIME 二次确认保持不变（`NOT_DUE` 语义不变）。
- B. 保持 MySQL 扫描但把 `@Scheduled(fixedDelay=30s)` 降到秒级 + 复用 `idx_promotion_window_status_time` 索引，批大小受控。

**locked**：裁决正确性不受影响（Lua 内 `now >= windowEndAtEpochMs` 拒绝兜底不变）；`PromotionAuctionWindowCloser` 的 LEGACY_BROKER 分支行为不变。

**验收**：构造 `windowEndAt = now + 5s` 的窗口，记录从 endAt 到 `WINDOW_CLOSED` 广播/结算完成的时间，5 轮均 < 2s。

### T4（P2）：幂等桶瘦身

**目标**：减少每出价的桶 HGET 次数，幂等语义完全不变。

**设计**：当前 `commandIdempotencyTtlSeconds=300`、`bucketSeconds=60` → 查当前桶 + 前 5 桶。方案：
- A. 桶数减为 2（当前 + 前 1，覆盖 120s 重放窗口），TTL 语义保持 300s（桶内记录 TTL 不变）；
- B. 单 key + `HSETEX` 滑动窗口（需确认 Redis 版本支持 HSETEX，7.4 支持）。

**locked**：`IDEMPOTENCY_CONFLICT` 检测、重放还原（version/prevVersion/decidedAt/authorizedAmount）、桶 TTL 语义不变；`PromotionBPrimeProperties` 校验（bucket <= ttl、ceilDiv <= 60）同步调整。

**验收**：幂等专项测试全绿（重放、冲突、桶滚动过期）；Lua 指令数下降（随 T2 记录）。

### T5（P2）：洪峰配置基线验证

**目标**：归因 `fast-reject-20k`（93.8% missing ack）与 `native-unlimited-60k`（100% ACK）的矛盾，确立回归基线。

**内容**：
1. 固定配置矩阵（线程池 32/16384、原生会话队列 4096、合并器 100/250ms）跑两个场景，记录服务端 metrics（decision/ingress/backpressure close/stream lag）与客户端 missing ack；
2. 确认 20k 场景 missing ack 的瓶颈归属：线程池队列满（`promotionBidSubmissionExecutor` 拒绝 → UNAVAILABLE → 客户端 pending 超时）vs WS 出站通道队列 vs Lua；
3. 产出基线报告（`loadtest/reports/` 或本文附录），作为 T1-T4 的前后对照锚点。

**locked**：不做无限队列（"unlimited" 配置只用于诊断，不进入生产默认值）；有界性约束（仓库开发标准 §5）不放松。

---

## 3. 边界（locked 候选，待 grill）

1. **不改对外契约**：HTTP API、STOMP/原生 WS 协议、`PromotionWebSocketBidAck` 字段、错误码（含 `BID_NOT_HIGHER` 语义）、`UNAVAILABLE` 语义全部保持。
2. **不引入**：多网关 fanout、网关侧 escrow 预拒、应用层 429/503、新增中间件/节点、无限队列。
3. **不复制 Go 业务语义**：不引入 increment 步长、anti-snipe 延长、cap 一口价、卖家自购拦截、sealed/hybrid 模式——本 enhancement 仅吸收性能机制，业务模型保持现状（每 campaign 独立价格、统一清算价）。
4. **单机验收**：1 Spring Boot App + 1 Redis + 1 MySQL，不得用扩容获得验收结果。
5. T1 的本地预拒**只针对价格判据**；escrow、窗口状态、资源类型等校验仍全部由 Lua 裁决。

## 4. 验收总标准

1. 同机、同数据、同负载下 T1-T4 各 5 轮对比，正向提升可重复（不以绝对 QPS 宣称完成）。
2. 功能正确性不回退：决策版本连续（投影 `requireNextVersion` 不报 gap）、幂等专项、对账（reconciliation）无回归。
3. T1 预拒正确性专项：幂等重试重放、margin 内放行、终态禁用、缓存单调性、错误码一致性。
4. T5 产出基线报告，且 T1-T4 每个任务在其完成后记录改动文件、测试命令、结果、尚存风险、配置最终值。

## 5. 变更记录

| 版本 | 日期 | 变更 |
|------|------|------|
| `0.1.0` | 2026-08-10 | 初稿：基于 Go 参考实现逐行对比 + 2026-08-09 压测基线，定义 T1-T5 五项 enhancement 任务 |
