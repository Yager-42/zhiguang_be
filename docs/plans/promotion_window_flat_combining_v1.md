# Plan：promotion-window-flat-combining-v1 单窗口最高价合并裁决

| 字段 | 值 |
|---|---|
| **plan_id** | `promotion-window-flat-combining-v1` |
| **plan_version** | `1.0.0` |
| **status** | **completed（实现、全量测试、部署与专项压测均完成）** |
| **created** | 2026-08-12 |
| **updated** | 2026-08-12 |
| **性质** | 改变并发线性化顺序和确定性低价拒绝幂等边界的性能架构变更 |
| **目标** | 单窗口洪峰下，以每窗口 flat combining、金额降序线性化和批量原子 Lua，将 Redis 调用复杂度从逐请求 $O(N)$ 降为按批次 $O(\lceil N/B\rceil)$ |
| **代码基线** | `PromotionCommandSubmissionService`、`PromotionWindowBidCombiner`、`PromotionRedisDecisionAdapter`、`promotion-auction-decision-batch.lua`、`PromotionBidAdmissionState`、`PromotionRedisStreamProjector`、`ThreadPoolConfig`、原生/STOMP WebSocket ACK 链路 |
| **上位约束** | `docs/contracts/ARCHITECTURE_CONTRACT.md` v0.6.0；Redis 是唯一实时裁决权威；禁止网关接受、双路径裁决和 Redis→MySQL 降级裁决 |

---

## 0. 状态与效力

1. 本文记录已冻结的架构、从代码和业务性质推导出的决定、grilling 结论及实现计划。
2. grilling 仅询问会直接推翻核心方案、显著改变性能上限或改变领域语义的问题；参数和值域由实现按性能测试选择。
3. 所有颠覆性问题已经关闭并记录在 §8；任何推翻 §8 决策的变更必须重新 grilling 并提升 `plan_version`。
4. 本方案明确不采用逐请求轻量 Redis 预检查、普通 FIFO 线程池、Kafka 入口排队、令牌桶和“仅加背压但不消除无效工作”的方案。
5. 实施前必须先更新 `ARCHITECTURE_CONTRACT.md` D10/D15/D17 和相关 active OpenSpec；本文不能单独覆盖现行架构契约。

---

## 1. 问题

当前每个请求都创建一个 `Runnable`，进入 `promotionBidSubmissionExecutor` 的 16384 有界 FIFO 队列，出队后才执行 route、fast-reject 和完整 Redis Lua。单窗口内即使配置 32 个 Java 线程，Redis 同一 `{windowId}` slot 上的 Lua 仍严格串行，线程数只增加 JVM 分配、上下文切换、客户端 in-flight 和 Redis 排队。

当前 fast-reject 还有四个性能缺口：

1. 位于提交队列之后，无法阻止低价请求占据队列并阻塞高价请求。
2. 判据是 `bidAmount <= cachedPrice`，没有使用真实台阶 `required=min(current+increment, cap)`。
3. 为保护幂等重放，每个候选低价请求仍执行一次 Redis `HEXISTS`。
4. 本地价格仅由 Stream projector 异步推进，完整 Lua 已返回接受后仍存在本实例可避免的缓存滞后。

完整 Lua 对每个低价请求重复执行 `TYPE×5 + HMGET state(14) + XREVRANGE + TIME + HGET command + HSET/HEXPIRE rejection`。虽然低价分支不会执行 escrow/ranking/Stream 接受写入，但公共前置成本仍按请求线性增长。

---

## 2. 性能目标与不变量

### 2.1 性能目标

1. 单窗口洪峰下，多个同时未完成请求自然形成批次；一次 Lua 处理一批，而非一请求一次 Lua。
2. 同一批次公共状态、Redis TIME 和 Stream/version 校验只执行一次。
3. 每批最多产生一个新的 `BID_ACCEPTED`，其余请求批量重放、冲突或拒绝。
4. 高价请求不得被先进入 JVM FIFO 的低价请求阻塞。
5. 低流量时批次自然退化为 1，不增加固定等待窗口。
6. 已被 Redis 确认价格支配的全新低价请求走零 Redis、零 drainer queue 的无状态本地拒绝路径。
7. 不追求“调大线程/队列”形成的表面吞吐；验收必须同时观察 ACK、Lua 调用数、Redis script duration、JVM allocation、pending 数和恢复时间。

### 2.2 正确性不变量

1. Redis Lua 仍是唯一允许返回新 `ACCEPTED` 的路径；Java 只能拒绝或调度。
2. state、ranking、escrow currentHold、decisionVersion、Stream 和反狙击/cap-hit 必须由一次 Lua 原子提交。
3. 已经完成并向客户端返回的请求先于其后才到达的请求；仅允许重排彼此执行时间重叠且均未完成的请求。
4. Stream 只记录改变拍卖事实的接受/延长/终态事件；普通拒绝不进入 Stream、不推进 decisionVersion。
5. Redis 故障返回可重试不可用，不降级到 MySQL，不把“可能有效”误判为本地接受。
6. 所有 JVM 队列、批次、窗口状态和 Future 数量有界。

---

## 3. 核心架构

### 3.1 每窗口 flat combiner

```text
WebSocket/STOMP request
        │
        ▼
prepare + route L1 lookup + authorization identity check
        │
        ▼
committed admission state
  ├─ definitely dominated new bid ──→ stateless local REJECTED
  └─ uncertain / replay candidate
                │
                ▼
WindowBidCombiner[windowId]
  ├─ bounded pending requests
  ├─ AtomicBoolean draining
  ├─ committedPrice / status / actualEndAt / version
  └─ monotonically increasing ingressSequence
                │
                ▼
shared bounded drainer executor
                │
                ▼
drain available requests up to batch limit
sort by bidAmount DESC, ingressSequence ASC
                │
                ▼
promotion-auction-decision-batch.lua
                │
                ▼
complete each request Future with final ACK
```

同一窗口最多有一个本地 drainer。不同窗口可以并行，保持 Redis Cluster 未来按 `{windowId}` 分片的扩展能力。多应用实例各自拥有本地 combiner；Redis 以批量 Lua 为跨实例最终线性化点，实例间不加分布式锁。

### 3.2 自然批处理，不设置固定等待

1. 第一个请求从 idle→draining 后立即调度，不等待凑批。
2. Redis 执行期间到达的请求积累为下一批。
3. 当前批返回后，drainer 立即抓取当时所有可用请求，受最大批大小和最大 ARGV 字节数约束。
4. pending 为空时通过 CAS 安全退出；必须处理“检查为空与新请求入队并发”的 lost-wakeup 竞态。
5. 低流量批次大小为 1；洪峰下批次自动增长。

### 3.3 金额降序线性化

批次排序：

```text
bidAmount DESC, ingressSequence ASC
```

Lua 依次处理：

1. 先按金额降序检查所有候选，跳过超 cap、escrow 不足和其他确定失败项，选出批次结束时的唯一最高有效请求。
2. 若存在比当前价更高的有效请求，原子接受该请求，并用其 commandId/requestHash/ACK 覆盖当前赢家幂等槽；包括旧赢家重试在内的其余请求全部按接受后的最终状态返回。
3. 只有当本批没有更高请求被接受时，commandId 与当前赢家槽相同且 requestHash 相同的请求才重放当前 `ACCEPTED`；hash 不同返回 `IDEMPOTENCY_CONFLICT`。
4. 最终赢家 commandId 在同批出现多个相同副本时共享 `ACCEPTED`；其他金额不高于最终价的请求返回 `BID_NOT_HIGHER`，cap-hit 后返回 `WINDOW_CLOSED`。
5. 若没有新请求可接受，除仍为当前赢家的精确重放外，其余请求各自得到当前状态下的最终拒绝。

该顺序利用并发请求可任意线性化的性质。它会改变“按到达顺序逐个接受中间价”的历史，因此被列为 §8 的首个颠覆性业务决策。

### 3.4 本地状态分层

每窗口状态必须区分：

- `committedPrice`：Redis 已确认的当前价，只升不降；可用于确定性拒绝。
- `pendingCeiling`：本 JVM 尚未裁决的最高候选；只用于排序和指标，禁止据此拒绝，因为候选可能 escrow 不足或其他业务条件失败。
- `status`：`OPEN` 或终态；终态只用于避免错误地返回价格拒绝，最终关闭语义由 Lua/已确认终态结果决定。
- `actualEndAtEpochMs`：随反狙击事件推进；临近终场时本地拒绝禁用，交给 Redis TIME。
- `decisionVersion`：用于拒绝陈旧的本地状态更新，不参与 Java 接受判断。

完整批量 Lua 返回接受后，必须在完成客户端 Future 前同步推进本实例 `committedPrice/status/actualEndAt/version`。其他实例继续由 Pub/Sub 唤醒后读取 Stream 推进。

---

## 4. 批量 Lua 合约

### 4.1 输入

所有请求属于同一 `auctionWindowId` 和 Redis hash slot。每项至少包含：

```text
inputIndex, commandId, requestHash, bidderUserId, bidAmount,
campaignId, postId, resourceType, submittedAt
```

公共参数包含 hot-state TTL 和 wakeup TTL。Java 在入 Lua 前完成 route 用户绑定和静态参数校验；Lua 不信任影响权威状态的 route 副本，窗口规则仍从 Redis state 读取。

为避免 Redis Cluster 对动态 key 的隐式访问，`KEYS` 布局冻结为：公共 6 key（state/ranking/escrow/events/pub/wakeup）在前，批内去重后的 campaign key 在后；每项 ARGV 携带对应的 campaign-key 索引。所有 key 均含相同 `{windowId}` hash tag。批项使用固定宽度扁平 ARGV，不用 JSON/cjson 解码输入。

### 4.2 每批一次的公共读取

1. key type/存在性守卫；campaign keys 只对候选项按需检查。
2. `HMGET state` 权威字段。
3. `XREVRANGE ... COUNT 1` 校验 `state.decisionVersion == Stream last version`。
4. `TIME` 获取统一裁决时刻。
5. 读取 state 中唯一的当前赢家 `winnerCommandId/winnerRequestHash/winnerAck`；不批量查询历史 command Hash。
6. 批量 `HMGET escrow campaignId:authorizedAmount...`。

禁止“先跑轻量 Lua，再让可能有效请求跑完整 Lua”的双 RTT 预裁决。

### 4.3 接受写入

每批至多一次：

- campaign `HSET`；
- ranking `ZADD LT`；
- escrow `currentHold`；
- state 当前价、赢家、bidCount、decisionVersion；
- `XADD BID_ACCEPTED`；
- 可选 `AUCTION_EXTENDED` 或 `AUCTION_SOLD` 第二事件；
- wakeup `SET NX EX` 与必要时 `PUBLISH`。

### 4.4 仅当前最高价强幂等

本方案把幂等范围压缩为每窗口 $O(1)$：

1. state 保存当前最高价对应的 `winnerCommandId`、`winnerRequestHash` 和紧凑 `winnerAck`。
2. 当前赢家以相同 commandId+requestHash 重试时，精确重放当前 `ACCEPTED`；即使第一次 ACK 丢失也能恢复。
3. 当前赢家使用相同 commandId 但不同 requestHash 时返回 `IDEMPOTENCY_CONFLICT`，防止同一命令身份篡改金额或主体。
4. 一旦更高有效价被接受，新的赢家幂等槽原子覆盖旧槽。旧命令此时已不再是最高价，重试按当前价格返回 `BID_NOT_HIGHER`，不重放历史 `ACCEPTED`。
5. 所有未成为当前最高价的拒绝都是当前权威状态的纯函数结果：不写 command record，不精确重放历史拒绝，不要求客户端提供 `attempt`。
6. 同一批或跨实例并发的相同 commandId 由 Redis Lua 串行处理：首次若成为当前赢家，后续副本命中当前赢家槽并共享 `ACCEPTED`；若未成为赢家，所有副本都按当前状态拒绝。

批量结果以脚本结束时的最终赢家为准：若当前赢家的重试与更高有效价处于同一批，先接受更高价，旧赢家重试返回 `BID_NOT_HIGHER`，不得先返回随后立刻失效的历史 `ACCEPTED`。

`winnerAck` 的紧凑内容固定覆盖：decisionId、decisionVersion、previousVersion、decidedAtEpochMs、submittedAt、authorizedAmount、bidAmount、campaignId 和 bidderUserId；这些字段足以重建当前赢家原 `ACCEPTED`，不保存排名或公共事件。

客户端仍应在改变金额时生成新的 idempotencyKey；服务端不依赖客户端正确执行这一建议来保证拍卖状态安全。当前 command Hash 与字段 TTL 机制在 clean cutover 后删除。

### 4.5 返回编码

1. Lua 返回紧凑数组，不为每项构造完整重复 JSON；Java 利用原命令字段组装 `PromotionAuctionDecision`/response。
2. 批量返回必须保持 `inputIndex`，不能依赖排序后位置与 Future 的隐含对应。
3. 当前赢家 `winnerAck` 只保存精确重放所需的紧凑字段；禁止把完整排名或公共事件复制进 state。
4. 单次脚本有最大批量和最大输入字节双上限，避免长 Lua 阻塞同 Redis 节点其他窗口。

---

## 5. 过载与公平性

1. pending 达窗口或全局上限时，不接受更多内存工作；返回明确可重试背压 ACK。不能无界等待。
2. 背压发生在本地确定性拒绝之后，低价垃圾请求不消耗 admission 配额。
3. drainer executor 的任务单位是“窗口 drain”，不是“请求”；同一窗口不会重复提交多个 Runnable。
4. 活跃窗口之间采用 ready-window 公平调度；单个热点窗口每轮最多处理一个批次后重新入 ready queue，防止一个窗口霸占所有 drainer。
5. 单窗口纯性能测试与多窗口公平测试分开；参数自动以 Redis script P99、ACK P99 和吞吐拐点选择，不把参数交给业务决策。

---

## 6. 多实例和故障语义

1. 每实例本地降序排序只对该实例当前批次生效；多个实例的批次由 Redis 到达顺序串行化。无需跨实例全局排序，最终结果仍合法线性一致。
2. 同一 commandId 并发落到不同实例时，Redis state 中的当前赢家幂等槽是唯一仲裁点；非赢家拒绝不保留历史。
3. Lua 失败或响应解析失败时，整批返回 `UNAVAILABLE`，不得猜测部分结果。客户端重试时：仍为当前赢家则精确重放，已被超过则按当前状态返回 `BID_NOT_HIGHER`。
4. Java 在 Redis 已接受后、完成 Future 前崩溃：只要该命令仍是当前赢家即可恢复原 `ACCEPTED`；若已被更高价超过，历史接受不再具有业务效力。
5. 本地无状态拒绝在进程崩溃后无需恢复，因为其依据是单调 committed floor；缓存丢失只会少拒绝并回到 Lua，不会错误接受。
6. 终态缓存失效不能简单删除后让旧接受事件重新建成 OPEN 状态；窗口状态更新必须携带 version 并以终态吸收或重新从 Redis state/Stream 恢复。

---

## 7. 不采用的方案

| 方案 | 不采用原因 |
|---|---|
| 逐请求 Redis HGET/轻量 Lua 预查 | 仍是 $N$ 次 RTT；有效请求还要第二次 RTT；不消除队头阻塞 |
| 仅把 fast-reject 移到 FIFO 入队前 | 能减少部分积压，但缓存落后时仍逐请求进入 Lua |
| 普通金额优先队列 + 单请求 Lua | 高价可越过低价，但 Redis 调用仍接近 $N$ |
| Kafka 入口削峰 | 改变同步裁决，排队位置转移，时间敏感判断变错 |
| 令牌桶 | 与金额无关地丢弃，可能误伤最高有效出价 |
| pending 候选直接作为拒绝阈值 | 候选可能 escrow 不足或冲突，会误拒真正有效的次高价 |
| 无限队列/无限批次 | 把过载转成内存或 Redis event-loop 长停顿 |
| 仅做背压 | 不减少注定失败请求的计算量，性能上限不变 |

---

## 8. Grilling 边界契约决策

### 8.1 用户确认的颠覆性问题

| ID | 问题 | 推荐答案 | 状态 |
|---|---|---|---|
| Q1 | 是否允许对彼此重叠且尚未返回的请求按金额降序线性化，使原本可能短暂接受的中间价直接 `BID_NOT_HIGHER`？ | 允许。这是每批最多一次接受和数量级降本的前提；最终最高赢家与第一价格不降低。 | **已确认（2026-08-12）：允许金额降序线性化** |
| Q2 | 首次出价已被 Redis 接受但 ACK 丢失时，同 key 重试是否必须精确重放原 `ACCEPTED`？ | 仅当它仍是当前最高价时精确重放；一旦被更高价超过，它已不合法，重试返回 `BID_NOT_HIGHER`。 | **已确认（2026-08-12）：仅当前最高价强幂等** |
| Q3 | 幂等是否需要覆盖所有拒绝和历史接受？ | 不需要。只保存当前最高价 commandId/requestHash/ACK；拒绝和已被超过的历史接受不持久化。 | **已确认（2026-08-12）：幂等状态每窗口 O(1)** |
| Q4 | 同一 commandId 并发重复如何处理？ | 若首次成为当前赢家，Redis 当前赢家槽使副本共享 `ACCEPTED`；若未成为赢家，副本按当前状态拒绝。不新增 `attempt` 或复杂并发首发协议。 | **已确认（2026-08-12）：按最高价幂等处理** |

### 8.2 用户方向直接确认

| ID | 决策 | 结论 |
|---|---|---|
| D1 | 是否采用次优“普通优先队列 + 单请求 Lua” | 不采用 |
| D2 | 是否允许改变确定性拒绝与历史接受的幂等契约 | 允许；采用 §4.4 的“仅当前最高价强幂等”，拒绝和已被超过的历史接受均按当前状态重新裁决 |
| D3 | 性能与参数取舍 | 性能优先；非架构参数由基准和压测自动选择，不询问用户 |

### 8.3 代码与架构已解答的冻结决策

| ID | 决策 | 结论与依据 |
|---|---|---|
| D4 | 是否需要逐请求轻量预查 Lua | 不需要；批量 Lua 一次读取 state/escrow，避免双 RTT |
| D5 | pending 最高价能否本地拒绝其他请求 | 不能；尚未提交候选可能失败，只能用于排序 |
| D6 | 是否需要跨实例分布式 combiner/锁 | 不需要；Redis 批量 Lua 是全局线性化点，分布式锁只增加 RTT 和故障面 |
| D7 | 是否保留拒绝 Stream 事件 | 不写；拒绝不推进 decisionVersion、不进 Stream，投影只消费事实变化 |
| D8 | 是否固定等待凑批 | 不等待；自然 batching 在低负载零附加等待、洪峰自动合并 |
| D9 | 是否继续每请求提交 Runnable | 不继续；任务粒度改为窗口 drainer |
| D10 | 本地接受是否合法 | 永远不合法；Java 只做确定拒绝和调度，接受仅由 Redis Lua 返回 |
| D11 | 批量 Lua 是否可无界 | 不可；数量和编码字节双有界，以 script P99 门禁调参 |
| D12 | route 的 authorizedAmount 能否用于本地拒绝 | 不使用；追加授权会使 route L1 陈旧，escrow 在批量 Lua 内批读 |
| D13 | 如何处理临近结束和反狙击 | 本地拒绝在安全 margin 内禁用；Lua 用 Redis TIME 与 state 实际 endAt 裁决 |
| D14 | 是否保留当前单请求 decision Lua | clean cutover；批量 Lua 支持 batch size=1，所有调用方迁移后删除旧脚本和 adapter 签名，不留双路径 |

---

## 9. 实现计划

### T1：契约先行

1. 更新 `docs/contracts/ARCHITECTURE_CONTRACT.md`：
   - D10 加入并发请求金额降序线性化、批量 Lua、每批最多一次新接受；
   - D15 改为每窗口只保存当前最高价 commandId/requestHash/ACK，删除拒绝和历史接受的 command Hash；
   - D17 把逐请求 executor 改为每窗口 flat combiner 与共享有界 drainer。
2. 更新 `openspec/specs/slot-auction-promotions/spec.md`：新增并发批次、中间价不保证短暂接受、仅当前最高价强幂等、最终 ACK 和最高价不变量。
3. 更新 `openspec/specs/bprime-position-auctions/spec.md` 与 `promotion-auction-realtime/spec.md` 的当前赢家 ACK 边界。
4. 将旧 throughput enhancement 中与新契约冲突的 T1/T2/线程池描述标记为被本文取代，避免两套 active 性能方案。

### T2：批量协议与 Lua 行为矩阵

1. 定义内部 `PromotionAuctionCommandBatch`、紧凑 `PromotionAuctionBatchResult` 和稳定 outcome code。
2. 编写批量 Lua 行为矩阵，再实现 `promotion-auction-decision-batch.lua`：
   - batch size 1 与除旧历史幂等外的当前裁决语义等价；
   - 最高候选有效；最高候选超 cap/escrow 不足后次高候选接受；全部低于 required；
   - 当前赢家 commandId+requestHash 精确重放与冲突；
   - 已被更高价超过的旧 command 重试返回 `BID_NOT_HIGHER`；
   - cap-hit 后同批其余项关闭；anti-snipe 每批至多触发一次；
   - endAt、state/Stream version mismatch、key type mismatch；
   - 输入重复 campaign、相同金额稳定 tie-break；
   - 当前赢家重试与更高有效价同批时，旧赢家返回 `BID_NOT_HIGHER`；
3. 返回紧凑结果并实现 Java 解码；不得为每项重复 Jackson parse 完整 decision JSON。
4. 以 batch size=1 对当前 Lua 行为矩阵做差分，再增加批次与并发矩阵。

### T3：每窗口 flat combiner

1. 新增 `PromotionWindowBidCombiner`：有界 per-window pending、draining CAS、ingress sequence、自然 drain 和 lost-wakeup 安全退出。
2. 新增全局有界 ready-window 调度器；每窗口每轮一个批次后重新排队，兼顾热点性能和跨窗口公平。
3. `submitAsync` 在入 pending 前完成参数、route L1 和用户绑定；不再每请求提交 executor Runnable。
4. 同一 commandId 在本实例 pending 中先合并，减少 Future/ARGV；最终结果仍以 Redis 当前赢家槽或当前价格为准。
5. pending/global capacity 满时返回明确背压结果，不关闭连接、不静默丢 ACK。
6. 窗口 idle 且无 pending 后从 window map 安全回收，防止删除正被新请求复用的 combiner。

### T4：本地 admission state 与零 Redis 拒绝

1. 将 `PromotionBidPriceCache` 替换为带 version、当前赢家 commandId 和实际 endAt 的 `PromotionBidAdmissionState`。
2. 使用 `required=min(committedPrice+increment, cap)`，处理金额上限和加法溢出。
3. committed floor 支配的请求在进入 combiner 前零 Redis 拒绝；当前赢家 commandId 命中时进入 Lua 精确重放，不能被本地价格拒绝。
4. 临近实际 endAt、终态不确定或缓存缺失时进入 Lua。
5. 批量 Lua 返回后、完成 Future 前同步更新本实例 state；Stream projector 用 version 单调更新其他实例。
6. 删除 `HEXISTS` 快拒；增加本地拒绝、pending 合并、当前赢家 replay/conflict、已被超过重试、Lua 批量拒绝指标。

### T5：用户领先状态反馈契约

1. 私有 `SubmitPromotionBidCommandResponse` / `PromotionWebSocketBidAck` 加性增加 `leadingAtDecision`、`winnerCampaignId` 和 `currentPriceCents`；`ACCEPTED` 固定表示该请求在 `decisionVersion` 原子裁决点成为最高价，`leadingAtDecision=true`。
2. 私有 ACK 不承诺到达客户端的物理时刻仍领先；客户端不得把 `leadingAtDecision` 命名或解释为 `isLeadingNow`。
3. `BID_ACCEPTED` Stream decision payload 增加接受后的 `winnerCampaignId/currentPriceCents/nextRequiredAmount`，供 projector/fanout 使用，不额外读取 Redis state。
4. `PromotionPublicUpdateCoalescer.WindowUpdates` 保存最新赢家、当前价和下一 required；每次 `RANKING_DELTA` 的 `details` 明确携带这三个字段及 `decisionVersion`。当前实现的空 `details` 不满足新契约。
5. 客户端仅以已见最大 `decisionVersion` 的公共事件或 snapshot 判断“当前领先”：`winnerCampaignId == myCampaignId`。较旧的私有 `ACCEPTED` 到达时只能确认历史裁决，不得覆盖较新公共状态。
6. `PromotionAuctionHotSnapshot` 已有 `winnerCampaignId/currentPriceCents/decisionVersion/rules`，继续作为断线和版本缺口恢复权威；snapshot 可直接计算下一 required，无需新增读取 RTT。
7. 公共事件区间合并语义不变：客户端按 `[fromDecisionVersion,toDecisionVersion]` 判断缺口；缺口恢复完成前不得展示确定的“当前领先”。

8. 当前仓库没有前端实现；本计划的客户端版本归并规则属于协议验收要求。后端通过 DTO、事件、snapshot 与服务端乱序模型测试固定该契约，真实前端接入时必须实现同一状态机。

### T6：调用方 clean cutover

1. `PromotionRedisDecisionAdapter` 改为唯一批量 adapter；batch size=1 覆盖普通流量。
2. 删除旧逐请求 Lua、旧 adapter overload、command Hash/字段 TTL 和 fast-reject `HEXISTS` 路径。
3. `ThreadPoolConfig` 将逐请求 executor 改为 drainer executor；配置一次性迁移。
4. STOMP 和原生 WebSocket 继续共用提交协议，每请求仍收到恰好一个最终 ACK。
5. 新增 batch size/duration、commands/batch、accepted/batch、local dominated、pending depth、active windows、drainer utilization、backpressure、recovery latency 指标。

### T7：正确性与并发验证

1. 单元测试：排序、tie-break、pending command 合并、capacity、lost wakeup、idle 回收、失败整批完成。
2. Redis 集成测试：批次原子性、当前赢家槽覆盖、version/Stream 连续、cap/extend、当前赢家重放、旧赢家被超过后拒绝。
3. 并发测试：同窗口多生产者只存在一个本地 drainer；完成请求不可被后到请求越过；多实例相同 commandId 仍只有当前赢家精确重放。
4. 反馈乱序测试：公共 v102 先于私有 v101 到达时，客户端状态保持 v102 赢家；事件区间缺口触发 snapshot，恢复前不误报领先。
5. 投影/结算回归：每批最多一个接受事件，SOLD/NO_BID、第一价格、wallet capture/release 不变。
6. 协议回归：STOMP/Native 每请求一 ACK，无 missing，无错误连接关闭；ACCEPTED ACK、RANKING_DELTA 和 snapshot 的赢家/价格/版本一致。

### T8：性能验证与参数选择

1. 固定硬件、seed、窗口、客户端实例和 fast-reject 状态，保存旧架构 baseline。
2. 场景：单窗口 1 秒 10 万同价/低价洪峰；随机金额且最高候选部分 escrow 不足；连续递增；多窗口公平；重复 command 风暴；终场/反狙击/cap-hit 洪峰。
3. 搜索批次上限、ARGV 字节上限、drainer 数和 pending 水位，选择吞吐最高且 Redis script P99/ACK P99 不越门禁的组合。
4. 对比 Redis EVAL 次数、commands/EVAL、Lua CPU/耗时、ACK p50/p95/p99、missing、JVM allocation/GC、pending 峰值和恢复时间。
5. 必须证明所有请求得到最终 ACK 或明确背压 ACK，Stream/version/结算完整。

### T9：收尾

1. 全量测试和真实 WebSocket smoke。
2. 新增专项压测报告，记录基线、参数搜索和最终结果。
3. 删除旧配置、旧脚本、旧测试假设和失效注释；不留兼容双路径。
4. 文档状态改为 completed，并记录最终配置、测试与压测证据。

---

## 10. 验收标准

### 10.1 功能

1. 每请求恰好一个最终 ACK；无静默丢失。
2. Redis 是唯一接受权威；无 Java 接受、无 MySQL 降级、无双裁决路径。
3. batch size=1 覆盖所有非历史幂等的现有英式裁决行为。
4. 批次金额降序且 tie-break 稳定；每批至多一个新的 `BID_ACCEPTED`。
5. 当前最高价 command 精确重放；被超过的旧 command 和所有拒绝按当前状态重新裁决。
6. decisionVersion 与 Stream ID 连续；投影、实时、结算和钱包不回退。
7. cap-hit、反狙击、关窗竞态在批次内保持原子且错误码明确。

### 10.2 性能

1. 单窗口洪峰 Redis Lua 调用数接近 $\lceil N/B\rceil$，而非 $N$；以实际 metrics 证明。
2. 同价/低价洪峰优先被零 Redis admission 或批量拒绝吸收。
3. 最高有效候选的 ACK 不再排在大量低价 FIFO 之后。
4. 记录吞吐、ACK P95/P99、Redis script P99、JVM allocation 和恢复时间；显著退化必须解释或回退。
5. 多窗口场景无长期饥饿；热点窗口不能垄断全部 drainer。

---

## 11. 已知风险

1. 金额降序线性化改变中间接受历史、bidCount 和反狙击触发次数；用户已接受该业务边界。
2. 大批 Lua 会提高单次 Redis event-loop 占用；必须用数量+字节双上限和 script P99 门禁。
3. 批量 ARGV 和结果解码可能转移瓶颈到 JVM 序列化；紧凑编码和 pending command 合并必须进入基准。
4. 多实例无法形成全局最优大批次；正确性不受影响，但单窗口性能仍受单 Redis shard 串行上限约束。
5. 仅当前最高价强幂等意味着历史 `ACCEPTED` 被超过后不再重放；私有 ACK 的 `leadingAtDecision` 表示裁决点领先，当前领先以最大版本公共状态或 snapshot 为准。
6. 每请求 Future 仍是 $O(N)$ 协议成本；同步最终 ACK 要求决定了该下限。

---

## 12. 完成证据

1. 全量测试：603 tests，0 failures，0 errors，1 skipped；专项服务、Redis 与实时反馈测试通过。
2. 部署：可执行 jar 已部署至 `zhiguang-app`，`/actuator/health` 返回 `UP`。
3. 单窗口同价洪峰：298,675/298,675 ACK，missing=0，p95=76ms，p99=168ms。
4. 混合抬价洪峰：232,336/232,336 ACK，accepted=142，missing/backpressure=0，p95=16ms，p99=38ms；10,300 个 Redis 命令合并为 5,469 批，最大批次 124。
5. 最终参数：batch=256、ARGV=262144 bytes、drainer=8、window pending=8192、global pending=65536；详细报告见 `loadtest/reports/promotion-window-flat-combining-20260812.md`。

---

## 13. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| `0.1.0` | 2026-08-12 | 初稿：每窗口 flat combining、金额降序线性化、批量 Lua、零 Redis 价格拒绝和实现计划。 |
| `0.2.0` | 2026-08-12 | grilling 收敛：允许并发金额降序；幂等简化为仅当前最高价每窗口 $O(1)$，删除 `attempt`、拒绝记录和历史接受重放；冻结实现边界。 |
| `0.3.0` | 2026-08-12 | 实现就绪复核：冻结 Redis Cluster 动态 KEYS/ARGV 布局、winnerAck 最小字段、私有 ACK 领先语义、公共赢家状态和版本乱序恢复契约；任务扩展为 T1-T9。 |
| `1.0.0` | 2026-08-12 | 完成实现、clean cutover、全量测试、真实 WebSocket 压测和部署；冻结最终参数与完成证据。 |
