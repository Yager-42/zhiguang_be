# Plan: promotion-bprime-hotpath-throughput-v1 竞价热链路吞吐增强

| 字段 | 值 |
|------|-----|
| **plan_id** | `promotion-bprime-hotpath-throughput-v1` |
| **plan_version** | `0.1.0` |
| **status** | **in-progress**（T4/T2/T1 已实现并压测验证；T3 已实现待端到端验证；压测报告见 `loadtest/reports/promotion-bprime-throughput-20260810.md`） |
| **created** | 2026-08-10 |
| **updated** | 2026-08-10 |
| **enhancement** | [`promotion_bprime_hotpath_throughput_v1.md`](../enhancements/promotion_bprime_hotpath_throughput_v1.md) draft |
| **code baseline** | Git `e3de07f`；`com.tongji.promotion.bprime`、`com.tongji.promotion.schedule`、`src/main/resources/redis/lua/promotion-auction-*.lua`、`application.yml`、`loadtest/` |
| **deployment boundary** | 1 Spring Boot App + 1 Redis 7.4 + 1 MySQL；不得用扩容获得验收结果 |
| **authority** | 本 plan 只解释和执行 enhancement 边界；若冲突，以 enhancement 为准，停止实现并更新契约/plan，禁止实现者自行扩大边界 |

## 0. Purpose and operating rule

本 plan 按依赖顺序实施 5 项增强（T4→T2→T1→T3→T5），全部未决决策已在本 plan §1 收敛（**均取高性能选项**）。执行规则：

1. 严格按 §2 任务顺序执行；每个任务通过自己的验收后才能进入依赖它的任务。
2. 所有缓存、executor、队列保持有界；"unlimited" 配置只用于诊断，不进入生产默认值。
3. 不改对外契约：HTTP API、STOMP/原生 WS 协议、`PromotionWebSocketBidAck` 字段、错误码（含 `BID_NOT_HIGHER` 语义）、`UNAVAILABLE` 语义。
4. 每个任务收尾记录：改动文件、测试命令、结果、尚存风险、配置最终值。不能只以"编译通过"收尾。
5. 涉及公共符号的改动先 `lsp references` 确认调用方；文本定位用 `grep`。

## 1. 决策收敛（grill 替代：全部取高性能选项）

| ID | 决策点 | 选择 | 理由 |
|----|--------|------|------|
| D1 | T4 幂等存储 | **HSETEX 单 key**（字段级 TTL，Redis 7.4 支持；在 `decision.lua` 脚本内 `redis.call('HSETEX', ...)` 调用，不经 Spring Data Redis API） | 每出价 1 次 HGET + 1 次 HSETEX，桶方案（2 桶）仍需 2 次 HGET |
| D2 | T3 关窗索引 | **Redis ZSET `promotion:auction:closing`** + `@Scheduled` 1s 扫描 `ZRANGEBYSCORE` | Go 同构；内存扫描毫秒级，不占 MySQL；MySQL 30s 扫描保留为幂等兜底 |
| D3 | T1 守卫 1（幂等优先） | 预拒前 **1 次 HGET** commandKey（D1 后单 key）检查 commandId，命中则放行 Lua | Go `FastPathPrecheck` 同构；不做本地 commandId 集合——缓存驱逐会误拒重试，破坏幂等 |
| D4 | T2 L3 排名写 | member 改 **纯 campaignId** + **`ZADD LT`**（score 为 `-bidAmount` 负数，加价时新 score 更小，GT 不成立） | `snapshot.lua` 的 `match(...) or rankingMember` 已兼容纯 member；ZREM 可删，单写 |
| D5 | T2 L2 TYPE 守卫 | 7 次 → **5 次**（去掉 campaign、wakeup） | campaign 是第一个写目标（其 WRONGTYPE 在写前爆，无脏写）；wakeup 用 `SETNX` 不覆盖；其余 5 个（state/ranking/escrow/events/command）写序靠后，必须保留防脏写 |
| D6 | T1 预拒边界 | 仅当 `route.windowStatus()==OPEN` 且 `now < windowEndAt - 2s` 且 `bidAmount <= cachedBidAmount` 且 HGET 未命中时本地拒 | 终态由 route 状态守卫；时间边界让 Lua TIME 裁决；缓存只升不降，投影 lag 方向安全（更保守） |
| D7 | 执行顺序 | **T5a 基线 → T4 → T2 → T1 → T3 → T5b 复测** | T1 预检依赖 T4 单 key；T2 的 L5（桶循环删除）由 T4 完成；T3 独立但共享 Redis keys 文件，串行安全 |

---

### P0：前置检查（执行任何任务前）

1. **Redis 版本 >= 7.4**（HSETEX 依赖；ZADD LT 需 6.2+）：docker-compose 已固定 `redis:7.4-alpine`，但本地直跑环境须验证——`docker exec zhiguang-redis redis-cli INFO server` 或等价的 `INFO server` 检查 `redis_version`；低于 7.4 先升级镜像/容器再继续。
2. **测试基线**：改动前运行 promotion 相关测试快照全绿：
   `mvn test -Dtest='PromotionCommandSubmissionServiceTest,PromotionRedisDecisionAdapterTest,PromotionRedisDecisionAdapterRedisIntegrationTest,PromotionAuctionHotStateRepositoryTest,PromotionAuctionWindowCloserTest,PromotionRedisStreamProjectorRedisIntegrationTest'`
   （依赖 Redis 容器的集成测试需先 `docker compose up -d redis`。）
3. **压测环境**（T5a 前置）：`docker compose up -d mysql redis` + 应用构建启动 + `loadtest/seed/seed_promotion*.sql` 落库；确认 k6 可用（`k6 version`）或按 `loadtest/run.sh` 既有方式执行。
4. **Git 基线**：确认 `git status` 干净（当前 `e3de07f`），T5a 基线数据与代码版本一并记录。

## 2. 任务定义

### T5a（P2）：洪峰基线采集

**目标**：固定配置下复跑 3 个场景，产出对照锚点；归因 `fast-reject-20k` 与 `native-unlimited-60k` 矛盾。

**动作**：
1. 固定配置：线程池 32/16384、原生会话队列 4096、合并器 100/250ms（application.yml 默认值，不覆盖）。
2. 跑 `promotion-ws-realistic.js`（500 VU）、`promotion-multi-accepted-heavy`（40 VU）、`promotion-ws-fast-reject-20k`（100 VU），记录：ack p95/p99、missing ack 数、服务端 `promotion.bprime.decision` / `ingress` 计数、backpressure close、stream lag。
3. 产出报告 `loadtest/reports/promotion-bprime-throughput-baseline-20260810.md`（表格 + 原始 summary 文件路径）。

**验收**：三场景数据齐全，与 2026-08-09 结果可比（同脚本同配置）；确认 missing ack 与线程池队列拒绝（`UNAVAILABLE` 计数）的相关性。

### T4（P2）：幂等存储改 HSETEX 单 key

**目标**：每出价幂等读写从"当前桶 + 前 5 桶（最多 6 次 HGET）"降为 **1 次 HGET + 1 次 HSETEX**；重放窗口保持 300-360s。

**改动**：
1. `PromotionAuctionRedisKeys.java`
   - `commandBucket(long, long)` 保留（兼容），新增 `commandBucket(long auctionWindowId)` 返回 `prefix + ":commands"`（无桶号）。
   - `decisionKeys(...)`：删除历史桶循环，返回 8 个 key：`state, command(单), ranking, campaign, escrow, events, pub, wakeup`。
2. `promotion-auction-decision.lua`
   - KEYS 解析：删除 `commandBucketKeys` 收集循环（KEYS[9..N]），`commandKey = KEYS[2]`。
   - 重放查询：`HGET commandKey commandId`（1 次）。
   - 写：`redis.call('HSETEX', commandKey, ARGV[9], commandId, record)`（ARGV[9] = `commandBucketTtlSeconds`，值改为 `ttl + bucket` = 360s，保持原桶有效窗口）。
   - `expectedTypes` 中 bucket 项类型保持 `hash`（HSETEX 创建 hash）。
3. `PromotionRedisDecisionAdapter.java`
   - `commandBucketTtlSeconds` 计算改为 `ttl + bucketSeconds`（保持 360s 有效窗口，语义不变）；`previousCommandBucketCount` 相关计算删除。
4. `PromotionBPrimeProperties.java` + `application.yml`
   - `commandIdempotencyBucketSeconds` **保留**（默认 60），语义改为"幂等字段 TTL 余量"（HSETEX TTL = `commandIdempotencyTtlSeconds + commandIdempotencyBucketSeconds`，保持原桶 300-360s 有效窗口）；字段名不改（避免配置/文档漂移），注释更新。
   - 校验项更新：保留 `ttl > 0` 与 `bucket >= 0`；删除 `bucket <= ttl`、`ceilDiv(ttl, bucket) <= 60` 两项（无桶后无意义）。
   - `application.yml` 对应行保留（值不变）。
5. 全仓库 grep `commandIdempotencyBucketSeconds` 确认引用处仅 Adapter/Properties/Lua 参数名，无其他消费者。

**验收**：
- 幂等专项：重放返回原裁决（含 version/prevVersion/decidedAt/authorizedAmount 还原）、hash 不符 → `IDEMPOTENCY_CONFLICT`、字段 TTL 过期（300-360s）后同 commandId 可重新裁决——用现有测试 + 手工 redis-cli 验证。
- 运行 P0.2 测试命令全绿（重点：`PromotionRedisDecisionAdapterRedisIntegrationTest`、`PromotionCommandSubmissionServiceTest`）。
- 记录 Lua 指令数变化（HSETEX 替换 HSET + 桶循环删除）。

### T2（P1）：Lua 裁决脚本瘦身

**目标**：按 D4/D5 与 L1/L4 减指令；裁决语义、错误码、Stream ID `<version>-0`、decisionVersion 递进不变。

**改动**（`promotion-auction-decision.lua`）：
1. **L1**：`XINFO STREAM eventsKey` 段（取 last-generated-id）替换为：
   ```lua
   local lastEntry = redis.call('XREVRANGE', eventsKey, '+', '-', 'COUNT', 1)
   local streamVersion = 0
   if lastEntry[1] then
       local sep = string.find(lastEntry[1][1], '-', 1, true)
       if sep then streamVersion = tonumber(string.sub(lastEntry[1][1], 1, sep - 1)) end
   end
   ```
2. **L2**：`expectedTypes` 从 7 项减为 5 项（删 campaign、wakeup 的 TYPE 检查；wakeup 的 `SETNX` 保持原样）。
3. **L3**：排名写：
   - 删除 `rankingMember` 版本前缀拼接（`string.rep('0', ...) .. versionText .. ':' .. campaignId`），`rankingMember = tostring(campaignId)`。
   - 删除 `ZREM(rankingKey, campaignId)`。
   - `ZADD` 改为 `redis.call('ZADD', rankingKey, 'LT', -bidAmount, rankingMember)`。
   - `HSET campaign ... 'rankingMember', rankingMember` 保留（成本可忽略，防其他读取方）；实施前 grep Java 代码确认 `rankingMember` 无消费者，无则同时删除该字段写入。
   - `promotion-auction-snapshot.lua` **不改**（`match(...) or rankingMember` 已兼容）。
4. **L4**：确认条件 EXPIRE（仅 missing 时设置）已是最优路径，**不做改动**，在收尾记录中说明原因（高频窗口内 missing=false，每出价 0 次 EXPIRE）。
5. **L5**：已由 T4 完成（桶循环删除）。

**验收**：
- `multi-accepted-heavy` 形态（40 VU）复测：p99 较 T5a 基线改善；接受数/拒绝数语义不变（对照 T5a 数字）。
- 排名正确性：快照返回的 ranking 按价格降序、每 campaign 一条、无旧版本残留（压测后 redis-cli `ZRANGE ranking 0 -1 WITHSCORES` 检查）。
- 运行 P0.2 测试命令全绿（重点：`PromotionRedisDecisionAdapterRedisIntegrationTest` 的 Lua 语义断言、`PromotionSnapshotServiceTest` 的排名断言）。

### T1（P0）：网关侧每 campaign 价格缓存 fast-reject

**目标**：`BID_NOT_HIGHER` 类出价在网关本地拦截（零 Lua 调用）；正确性守卫按 enhancement §2 T1。

**改动**：
1. **新增** `PromotionBidPriceCache.java`（`com.tongji.promotion.bprime.redis`）：
   - Caffeine `Cache<String, Long>`，key = `windowId + ":" + campaignId`（**不用 `windowId << 32 | campaignId` 复合 long**——snowflake/大数 campaignId 可能超过 32 位导致冲突），`maximumSize = properties.fastRejectPriceCacheMaximumSize`，`expireAfterAccess(5min)`（与 route 缓存一致；窗口关闭后残留无害——预拒条件有 `windowStatus==OPEN` 守卫）。
   - `void update(long windowId, long campaignId, long bidAmount)`（只升不降：`compute` 仅当现值 < 新值时替换）。
   - `Long get(long windowId, long campaignId)`。
2. `PromotionRedisStreamProjector.projectAvailable`：对每个 `BID_ACCEPTED` 且 `accepted()` 的决策调用 `priceCache.update(...)`（单消费者、按版本序，天然单调）。
3. `PromotionCommandSubmissionService.submitAuthoritative`：在 `decisionAdapter.decide(command)` 前插入预拒：
   ```text
   条件（全满足才预拒）：
     route != null
     && "REDIS_STREAM".equals(route.decisionPath())
     && "OPEN".equals(route.windowStatus())
     && now < route.windowEndAt() - fastRejectMarginSeconds(2s)
     && cached = priceCache.get(windowId, campaignId); cached != null
     && bidAmount <= cached
     && redis HGET commandKey commandId == null   // 幂等重试放行 Lua（D3）
   动作：返回 response(status=REJECTED, rejectionReason=BID_NOT_HIGHER,
         decisionId/decisionVersion/decidedAt=null)；metrics.recordFastRejected()
   任何异常/Redis 错误 → 放行 Lua（保守）
   ```
   - 预拒响应字段：`SubmitPromotionBidCommandResponse(status=REJECTED, accepted=false, rejectionReason=BID_NOT_HIGHER, commandId, 其余 null)`——与 Lua 拒绝的差异仅为 decisionId/version 为 null；loadtest 脚本只断言 `status`/`rejectionReason`（已核对 `promotion-ws-realistic.js` recordAck），客户端协议兼容。
4. `PromotionPerformanceMetrics`：新增 `fastRejected` 计数器（`promotion.bprime.ingress.fast-rejected`，tag `reason=BID_NOT_HIGHER`）。
5. `PromotionBPrimeProperties` + `application.yml`：新增 `fastRejectEnabled=true`、`fastRejectMarginSeconds=2`、`fastRejectPriceCacheMaximumSize=1_000_000`（校验 > 0）。
6. `PromotionBidFastRejectionReason`：由死代码转为实际使用（`BID_NOT_HIGHER` 作为预拒原因常量）；删除或保留取决于引用（实施时定，倾向保留并在预拒路径引用）。

**验收**：
- 正确性专项（新测试或脚本）：幂等重试（同 commandId 二次提交）放行 Lua 且重放原 ACK；margin 内（窗口结束前 2s）出价放行 Lua；窗口关闭后（route 状态 CLOSED）不预拒；缓存单调性（更低出价不降缓存）；预拒 ACK 与 Lua 拒绝语义一致。
- realistic 场景：服务端 `decision` 计数较 T5a 下降 ≈ stale+retry+double 比例（约 40%）；ack p95 较 T5a 可重复改善。
- `promotion.bprime.ingress.fast-rejected` 计数 > 0 且与 missing ack 无相关。

### T3（P1）：关窗调度秒级化

**目标**：`WINDOW_CLOSED` 终态延迟从 0-30s 收敛到 < 2s（D2）。

**改动**：
1. `PromotionAuctionRedisKeys`：新增 `closingIndex()` = `"promotion:auction:closing"`（ZSET，member=windowId，score=windowEndAtEpochMs）。
2. `PromotionAuctionHotStateRepository.initialize`（两处调用统一受益）：成功后 `ZADD closingIndex windowEndAtEpochMs windowId`。
3. `PromotionRedisWindowCloser.close`：返回非空 decision（关窗成功）后 `ZREM closingIndex windowId`；返回 `NOT_DUE` 时重新 `ZADD`（刷新 score，Go 的 `ERR_NOT_DUE` 刷新索引做法）。
4. **新增** `PromotionRedisClosingScanner.java`（`com.tongji.promotion.schedule`）：
   - `@Scheduled(fixedDelayString = "${promotion.bprime.closing-scan-interval-ms:1000}")`
   - `ZRANGEBYSCORE closingIndex 0 now LIMIT 0 batchSize`（默认 100）→ 逐个 `redisWindowCloser.close(windowId)`，`NOT_DUE` 由步骤 3 自动重排。
   - 现有 `PromotionAuctionWindowCloser.closeDueWindows`（30s MySQL 扫描）**保留**为兜底——close.lua 幂等（`closeResult` 重放）保证无双重裁决。
5. `PromotionRedisStreamProjector.recoverRegistryOnce`：恢复 active-streams 的同时从 MySQL 窗口表 `ZADD` closingIndex（窗口存在且未 SETTLED）。
6. `application.yml`：`promotion.bprime.closing-scan-interval-ms: 1000`。

**验收**：构造 `windowEndAt = now + 5s` 的窗口（seed SQL 或接口），记录从 endAt 到 `WINDOW_CLOSED` 广播完成时间，5 轮均 < 2s；MySQL 兜底扫描路径仍可关窗（停掉 Redis 扫描器场景手工验证 close.lua 幂等）。

### T5b（P2）：复测对照

**动作**：与 T5a 完全相同的固定配置和场景复跑（realistic / multi-accepted-heavy / fast-reject-20k），对照 T5a 基线记录：ack p95/p99、missing ack、decision 计数、fast-rejected 计数、关窗延迟。

**验收**：
- realistic：ack p95 可重复改善；decision 计数下降约 40%；missing ack 不劣于基线。
- multi-accepted-heavy：p99 可重复改善（T2 生效证据）。
- fast-reject-20k：missing ack 较基线改善或归因明确（若瓶颈在队列配置，记录并给出配置建议，不改默认值）。
- 正确性：决策版本连续（投影无 gap 日志）、reconciliation 无回归。
- 产出报告 `loadtest/reports/promotion-bprime-throughput-after-20260810.md`，汇总 T1-T4 每项改动文件、测试命令、结果。

---

## 3. 验收总标准

1. T5a/T5b 同机同数据同负载 5 轮对比，正向提升可重复（不以绝对 QPS 宣称完成）。
2. 功能正确性不回退：决策版本连续（投影 `requireNextVersion` 不报 gap）、幂等专项、对账无回归。
3. T1 预拒正确性专项全绿（重试重放、margin 放行、终态禁用、缓存单调、错误码一致）。
4. 关窗延迟 < 2s（5 轮）。
5. 每个任务收尾记录：改动文件、测试命令、结果、尚存风险、配置最终值。

## 4. 变更记录

| 版本 | 日期 | 变更 |
|------|------|------|
| `0.1.0` | 2026-08-10 | 初稿：enhancement 决策全部收敛为高性能选项（D1-D7），定义 T5a→T4→T2→T1→T3→T5b 执行顺序与文件级改动清单 |
| `0.1.1` | 2026-08-10 | 执行记录：P0 完成（Maven 3.9.11 wrapper + JDK 21，修复 2 个既有损坏测试）；T4/T2/T1/T3 全部实现（详见会话记录），单元测试 29/29 绿；T5a/T5b 阻塞：本机无 docker/k6，Redis 7.4 集成测试与压测需在有环境的主机执行 |
| `0.1.2` | 2026-08-10 | 压测记录：WSL docker 全栈 + k6 容器化；realistic 500 VU 对比——ack p95 10ms→3ms、预拒 70-97%、missing 0；修复 HSETEX(8.0)→HSET+HEXPIRE、Lua 函数作用域；发现 priceCache 重置边界（压测需重启）；multi-accepted-heavy 脚本缺失未复跑 |
