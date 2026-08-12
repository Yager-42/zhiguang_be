# Plan: promotion-english-auction-migration-v1 推广位英式升价迁移（GSP → Go 式）

| 字段 | 值 |
|------|-----|
| **plan_id** | `promotion-english-auction-migration-v1` |
| **plan_version** | `0.1.0` |
| **status** | **completed**（T1-T6 全部完成：契约文档、Lua、Java、测试、压测、报告；分 commit 提交，见 git log） |
| **created** | 2026-08-10 |
| **updated** | 2026-08-10 |
| **code baseline** | Git `3081a96`（T1-T4 已提交，工作区干净）；`com.tongji.promotion.bprime.*`、`src/main/resources/redis/lua/promotion-auction-*.lua`、`loadtest/` |
| **Go reference** | `E:\tmp\live-auction-system`（commit `df54abf`）：`apps/lumen/internal/lua/{place_bid,close_auction,freeze_rules,start_auction}.lua`、`apps/lumen/internal/server/ws.go`（Tier C fast-reject）、`apps/lumen/internal/model/model.go`（Rules/Validate/RoomSnapshot）、`apps/lumen/internal/store/store.go`（Snapshot/FastPathPrecheck）、`proto/{error-codes,redis-keys,ws-envelope}.md`、`docs/state-machine.md` |
| **authority** | 本 plan 只解释和执行"完全复刻 Go 英式升价 + 代码翻译"的边界；与既有 enhancement/契约冲突时，以本 plan §3 决策日志为准，先更新契约文档再改代码（AGENTS.md：先改契约文档再改架构） |

## 0. 目的与执行规则

将推广位竞拍从 **GSP（独立出价轴 + 关窗排名）** 迁移为 **Go lumen 式英式升价拍卖（共享当前价 + 实时对抗）**：拍卖物不变（`FEED_TOP_SLOT` / `SEARCH_TOP_SLOT` 窗口的展示权），参与者不变（每个 campaign 由创建者出价，`route.bidderUserId` 绑定保留），**直接替换现有窗口语义，不并存新域**。竞争从"关窗排名"变为"出价时刻的共享价格台阶对抗"。

执行规则：

1. **完全复刻**：Go 语义是翻译目标（§1），逐项抄写；Go 未定义的部分（zhiguang 域特有）按 §3 决策日志处理。
2. **契约文档先行**：先改 `openspec/specs/slot-auction-promotions/spec.md`、`docs/contracts/ARCHITECTURE_CONTRACT.md` D10、enhancement/plan 记录，再改代码。
3. 有界性约束不放松（线程池/队列有界；"unlimited" 只用于诊断）；不引入 Go 的无关业务（无商品实体、无 LLM auctioneer、无 sealed/hybrid 模式）。
4. 对外契约（HTTP API、WS ack 字段、错误码字符串、实时事件名）**只做加性扩展**；业务语义变更（拒绝率形态、终态类型、结算价格）按 Go 语义调整。
5. Lua 每步先在 `wsl docker exec zhiguang-redis redis-cli` 手工 EVAL 验证（HSETEX 教训：Redis 7.4 无 8.0 命令；Lua 5.1 local function 先声明后使用）。
6. 涉及公共符号的改动先 `lsp references` 确认调用方。

## 1. Go 语义快照（翻译目标）

### 1.1 状态字段（`freeze_rules.lua` L18-33 → state Hash）

`status`、`mode`、`startPriceCents`、**`currentPriceCents`（冻结时 = startPrice）**、`incrementCents`、`capPriceCents`（0=无一口价）、`extendWindowSec`、`extendSec`、`maxExtensions`（0=无限，创建期 `boundAntiSnipe` 注入默认 10）、`extendCount`、`winnerId`（''=无）、`sellerId`、`seq`（事件序号，freeze/start **不消费**）、`paused`、`bidCount`。money 字段一律十进制字符串（>2^53 会丢精度，`MAX_MONEY = 9007199254740991`）。

### 1.2 出价裁决（`place_bid.lua`）

校验链（每步在任何写之前）：TYPE 守卫 4 key → dedupe HGET 重放（`DUPLICATE` 返回原 ack，非错误）→ `paused` → `status==LIVE` → seller self-bid → Redis TIME `now < endAtMs`（`>=` 让给 close）→ 金额范围 → **stream/state seq 预检**（`XREVRANGE` last id == state.seq，否则 `ERR_INTERNAL{'seq_stream_mismatch'}` 不脏写）。

金额（L80-86）：`required = min(currentPriceCents + incrementCents, capPriceCents>0 ? capPriceCents : ∞)`；`amount < required` 或 `amount > cap`（cap>0）→ `ERR_TOO_LOW(amount, required)`；`amount > MAX_MONEY` → `ERR_TOO_LOW`。

接受（L112-129）：`HINCRBY seq` → `HINCRBY bidCount` → `HMSET currentPriceCents=amountStr winnerId=userId` → `ZADD leaderboard`（member=userId，只升不降保留每人最高）→ **反狙击**（非 cap-hit 且 `endAtMs-now <= extendWindowSec*1000` 且 `extendCount < maxExtensions` 时 `endAtMs += extendSec*1000`、`HINCRBY extendCount`）→ **cap-hit**（`amount >= cap` 时 `status=SOLD`）。

事件（L139-162）：`XADD <seq>-0 {type=BID_ACCEPTED, seq, payload=bidJson}`（bidJson=ack 载荷）→ `HSET dedupe clientBidId→bidJson`（24h TTL）→ `PUBLISH` 提示；**第二事件消耗独立 seq**：反狙击 → `AUCTION_EXTENDED` @seq+1（`{seq2, endAtMs, extendCount}`）；cap-hit → `AUCTION_SOLD` @seq+1（`{seq2, winnerId, amountCents, status=SOLD}`）。cap-hit 优先于反狙击。

### 1.3 关窗（`close_auction.lua`）

Timer hammer，不依赖下一出价：TYPE 守卫 → `status==LIVE` 否则 `ERR_ALREADY_TERMINAL`（engine no-op）→ Redis TIME `now >= endAtMs` 否则 `ERR_NOT_DUE(endAtMs, now)`（反狙击挪走的窗口在这里被重试）→ seq 预检 → `HINCRBY seq` → 有 winner → `status=SOLD` + `AUCTION_SOLD` @seq；无 winner → `status=NO_BID` + `AUCTION_NO_BID` @seq。

### 1.4 快照与规则（`store.go` Snapshot L901-920 + `model.go` RoomSnapshotRules L460-474）

`HGetAll state` → `{status, currentPriceCents, winnerId, endAtMs, bidCount}` + rules `{stepCents=increment, capCents(null=无), reserveCents=startPrice, maxExtensions, antiSnipeWindowMs}`。singleflight 防 JOIN 雪崩。

### 1.5 创建期校验（`model.go` Rules.Validate L479-513）

`incrementCents > 0`；`capPriceCents == 0 或 > startPriceCents`；`startPrice+increment <= MAX_MONEY`（无 cap 时，否则不可胜）；`durationSec > 0`；`extendWindowSec/extendSec/maxExtensions >= 0`；`boundAntiSnipe`（mode.go L39-44）：反狙击开但 maxExtensions=0 → 注入 10。

### 1.6 网关侧 fast-reject（`ws.go` L1595-1700，V10k Tier C）

roomState 缓存每窗口 `currentPriceCents`（只升不降，从广播事件 ratchet；终态事件清缓存）。`bidN <= cached price` 且守卫全过 → 本地 `ERR_TOO_LOW`，省一次 EVALSHA。守卫（镜像 Lua 检查顺序）：① dedupe 未命中（否则放行 Lua 重放）② `nowMs + 1000ms margin < cached endAtMs`（防时钟偏斜把 ERR_AFTER_END 误判成 ERR_TOO_LOW）③ 一次流水线 `FastPathPrecheck`（status==LIVE、!paused、非 seller self-bid、dedupe 无）④ cap-hit SOLD 后缓存即清（终态放行 Lua）。任何不确定 → 放行 Lua。**fast-reject 不带 required 载荷**（只有 Lua 路径的 ERR_TOO_LOW 带 amount/required）。

## 2. Java 侧迁移映射（代码翻译对应表）

| # | Go 语义 | Java 现状（基线） | 迁移动作 |
|---|---------|-------------------|----------|
| M1 | `startPriceCents`（currentPrice 初值=start） | `reservePrice`（GSP 成交下限）+ `BELOW_RESERVE` 拒绝 | **保留字段名** `reservePrice`（DB/state/route 契约不变），语义改为 start price：`initialize.lua` 写 `currentPriceCents=reservePrice`；`decision.lua` 删除 `BELOW_RESERVE`，并入 required 检查（首出价必须 ≥ reserve+increment） |
| M2 | state 新字段 | 无 | `initialize.lua` 加 `currentPriceCents/winnerCampaignId/incrementCents/capPriceCents/extendWindowSec/extendSec/maxExtensions/extendCount/bidCount`（HSETNX 幂等回填，避免热状态未过期时漏字段） |
| M3 | required = min(current+increment, cap)；`ERR_TOO_LOW(amount, required)` | `BID_NOT_HIGHER`（逐 campaign 对比）、`BELOW_RESERVE` | `decision.lua`：`HMGET state` 增读 `currentPriceCents/incrementCents/capPriceCents/extendWindowSec/extendSec/maxExtensions/extendCount/bidCount`；`BID_NOT_HIGHER` 拒绝载荷加 `requiredAmount`（=required）与 `currentPriceCents`（对外加性字段）；**increment 缺失按 `REDIS_STATE_INCOMPLETE` 拒绝**（防旧热状态 required=0 全收） |
| M4 | `HSET currentPrice/winnerId` + `ZADD keep-max` | `HSET campaign bidAmount` + `ZADD LT -bidAmount campaignId` | campaign key 保留（逐 campaign 最高价展示），state 增写 `currentPriceCents/winnerCampaignId`；ZADD 不变（member=campaignId，负分 LT） |
| M5 | 反狙击 `AUCTION_EXTENDED`（第二事件 + 独立 seq） | 无 | `decision.lua`：capHit/extend 判定后 `endAtMs=windowEndAtEpochMs+extendSec` 写回 state；`AUCTION_EXTENDED` @decisionVersion+1（决策类型新增）；投影器对 AUCTION_EXTENDED 只推进 checkpoint 不落 MySQL（信息事件） |
| M6 | cap-hit `status=SOLD` + `AUCTION_SOLD`（终态，提前结束） | 窗口只能到点关窗 | `decision.lua`：cap-hit 写 `state.status='SOLD'` + `AUCTION_SOLD` 事件（终态决策）；投影器把 `AUCTION_SOLD` 当终态触发 settleWindow；路由/裁决守卫 `status=='OPEN'` 自然拒绝后续出价 |
| M7 | close：`SOLD`（有 winner）/ `NO_BID`（无） | `WINDOW_CLOSED` 单一终态 | `close.lua` 重写：`AUCTION_SOLD`（winner 存在）/`AUCTION_NO_BID`（无）终态事件，载荷含 `winnerCampaignId/winningAmount/actualEndAtEpochMs`；非 OPEN → 幂等 no-op + Java 侧 ZREM closingIndex（防 cap-hit 后每秒误报）；`WINDOW_CLOSED` 决策类型**删除**，投影/fanout/对账同步改 |
| M8 | 结算 = 第一价格（winner 付 currentPriceCents） | GSP clearingPrice=max(下一名, reserve) | `PromotionDecisionProjectionService.settleWindow`：winner = 终态决策 `winnerCampaignId`（=排名首位，不变量校验）；付 `winningAmount`；其余全部 release + markLost；allocation `slot_index=0`、`clearing_price=winningAmount`；NO_BID → 全 release、无 allocation。`PromotionAuctionSettlementPlanner`/对账重放同步改 |
| M9 | 快照含 currentPrice/winner/endAt/rules | `snapshot.lua` 只有 ranking + decisionVersion | `snapshot.lua` 增读 state 新字段 → `PromotionAuctionHotSnapshot` 加字段（加性）；rules 含 step/cap/reserve/maxExtensions/antiSnipeWindowMs |
| M10 | 网关 fast-reject：`bidN <= cached currentPrice`（窗口级） | `PromotionBidPriceCache` key=`windowId:campaignId` | 缓存 key 改 `windowId`，更新源仍为投影器（`BID_ACCEPTED` 的 bidAmount 即新 currentPrice，单调）；**终态事件（AUCTION_SOLD/AUCTION_NO_BID）→ `priceCache.invalidate(windowId)`**（Go `updateRoomStateFromEvent` L674-678 同构：cap-hit 后缓存即清，下一出价放行 Lua 返回 `WINDOW_CLOSED`，避免 route 仍 OPEN 时误拒成 `BID_NOT_HIGHER`）；`fastReject` 条件不变（`bidAmount <= cached`），守卫不变（OPEN + margin + commandBucket HGET）；拒绝仍 `BID_NOT_HIGHER`、不带 required（Go 同构） |
| M11 | dedupe 24h、per-user | T4 单 key `commands` HSET+HEXPIRE 360s | **保留 T4**（语义等价：确定性 commandId + 原裁决重放；360s 是既有契约）。不照抄 per-user key |
| M12 | `seq`（事件序号，第二事件 +1） | `decisionVersion`（XADD `<v>-0`） | 保留 `decisionVersion` 命名；AUCTION_EXTENDED/SOLD 各消耗独立版本，Stream ID 连续无洞（现有 requireNextVersion/requireMatchingStreamId 校验继续生效） |
| M13 | `MAX_MONEY=2^53-1`、金额字符串 | long 传参，无上限 | Lua 增 `MAX_MONEY` 守卫（超限 `BID_NOT_HIGHER(required=0)` 或 REDIS 拒绝）；gateway 增范围校验（`bidAmount > MAX_MONEY → BAD_REQUEST`） |
| M14 | `bidCount` | 无 | state `HINCRBY bidCount`（每接受 +1），快照展示 |
| M15 | `Rules.Validate` + `boundAntiSnipe` | 无 | `PromotionBPrimeProperties.validate` 增规则校验；`boundAntiSnipe` 同构（反狙击开但 maxExtensions=0 → 注入 10） |
| M16 | seller self-bid / paused | 不适用 | 跳过（zhiguang 无卖家实体；UNAVAILABLE 路径已覆盖 Redis 异常） |
| M17 | 实时事件：BID_ACCEPTED/AUCTION_EXTENDED/AUCTION_SOLD/AUCTION_NO_BID | RANKING_DELTA/WINDOW_CLOSED | 客户端事件名**保留**（RANKING_DELTA + WINDOW_CLOSED，加性扩展 payload：terminalStatus/winner/winningAmount/actualEndAtMs）；**新增** AUCTION_EXTENDED 实时事件（新 endAtMs + extendCount） |
| M18 | Timer：`auction:active` ZSET score=endAtMs，close 后 ZREM | T3 `closingIndex` + 1s 扫描 | 保留 T3；反狙击延长后 ZSET score 陈旧 → close.lua `NOT_DUE` 幂等重试自愈（1s 粒度；30s MySQL 兜底同构），无需扫描器改动 |
| M19 | 参数来自创建期 rules | 窗口参数来自 `promotion.slot-auction.*`（per-resource） | 新增 per-resource 英式参数配置（见 Q14，§3.2），经 `initialize.lua` ARGV 注入（route 透传写入 state）；**实现修正：`decision.lua` 不扩展 ARGV（M3 表格行为准：HMGET state 增读，Go place_bid.lua L48-51 同构，state 单一权威；route/command 透传链只服务初始化）** |

## 3. 决策日志（Q&A）

### 3.1 Go 已解答（记录问题与答案；不需要用户确认）

| ID | 问题 | 答案（依据） |
|----|------|--------------|
| Q1 | 结算价格用哪种？ | **第一价格**：winner 付自己最后一次出价 = `currentPriceCents`（place_bid.lua L114、close_auction.lua L48）。GSP 第二价格概念在英式拍卖中不存在（Vickrey 变体需额外存次高价，本 plan 不做） |
| Q2 | 加不加反狙击？ | **加**：`extendWindowSec/extendSec/maxExtensions` 配置化（place_bid.lua L108-126），cap-hit 不延长；超过 maxExtensions 后照常接受但不延长（防无限延长） |
| Q3 | 加不加一口价？ | **加**：`capPriceCents>0` 时出价达到 cap 立即 `SOLD`（L104/L127-129），cap 感知的 required 允许一步到位（L83-85）；cap=0 禁用 |
| Q4 | 保留价（reservePrice）怎么办？ | Go 无 reserve，有 startPrice（freeze_rules.lua L23 `currentPriceCents = startPriceCents`）。映射：**Java `reservePrice` 字段保留，语义 = startPrice**——首出价必须 ≥ reserve+increment，最终价恒 ≥ reserve+increment，GSP 的"结算 reserve 地板"被起点价吸收；`BELOW_RESERVE` 拒绝并入 `BID_NOT_HIGHER(+requiredAmount)` |
| Q5 | 终态类型怎么命名？ | **内部 Stream 决策类型照抄 Go**：`AUCTION_SOLD`（cap-hit 或到期有 winner）/ `AUCTION_NO_BID`（到期无 winner），删除 `WINDOW_CLOSED` 类型（close_auction.lua L46-59）；**zhiguang 客户端实时事件名 `WINDOW_CLOSED` 保留**（既有 WS 契约），payload 加性扩展 |
| Q6 | slotCount > 1 的窗口怎么处理？ | 英式拍卖每窗口**恰好一个赢家**（close_auction.lua 单 winner 字段）。生产配置两资源 slotCount=1（application.yml L131-132）；`slot_count` 列保留（既有 schema 契约），结算只产出 1 个 allocation（slot_index=0）；压测 seed `slot_count=10` 需改 1 |
| Q7 | 幂等存储照抄 Go per-user dedupe？ | **不照抄，保留 Java T4 单 key**：语义等价（确定性 commandId + 原裁决重放），360s TTL 是既有契约（`commandIdempotencyTtlSeconds=300+60`）；Go 24h 是为缓存 ack JSON 的网关重试，Java 重放的是完整裁决，无此需求 |
| Q8 | 拒绝原因字符串怎么映射？ | **保留 Java 字符串**：`BID_NOT_HIGHER` 覆盖 Go `ERR_TOO_LOW`（含 required 载荷加性字段）、`WINDOW_CLOSED` 覆盖 `ERR_AFTER_END`；新增 `ESCROW_INSUFFICIENT` 保留（zhiguang 域要求）；不引入 `ERR_NOT_LIVE/ERR_NOT_ALLOWED/ERR_AUCTION_PAUSED`（Java 已有等价语义） |
| Q9 | 谁付钱、释放谁？ | 结算不变式：winner 扣 `winningAmount`（第一价格），释放 `authorizedAmount - winningAmount`；其余 campaign 全释放 `authorizedAmount`（escrow currentHold 语义不变） |
| Q10 | 窗口内 SOLD（cap-hit）后关窗扫描会不会误报？ | 会，需适配：close.lua 对非 OPEN 状态返回幂等 no-op（Go `ERR_ALREADY_TERMINAL` 同构），Java 侧成功后 ZREM closingIndex；cap-hit 后无重复结算（settleWindow 由 AUCTION_SOLD 事件触发一次，allocation 已存在守卫兜底） |
| Q11 | 反狙击延长后 MySQL `window_end_at` 要不要改？ | **不改**（窗口周期是调度契约，且延长 ≤50s 无调度价值）；实际结束时间只存在 Redis state + AUCTION_EXTENDED/AUCTION_SOLD 事件载荷；MySQL 30s 兜底扫描照旧（close.lua NOT_DUE 幂等） |
| Q12 | 实时事件怎么播？ | `RANKING_DELTA` 保留（每 BID_ACCEPTED，合并器 100-250ms 不变）；**新增 `AUCTION_EXTENDED`**（新 endAtMs + extendCount，Go 同构）；`WINDOW_CLOSED` 事件保留名、payload 加 `terminalStatus/winnerCampaignId/winningAmount/actualEndAtEpochMs` |

### 3.2 用户确认的边界决策（Go 无法解答，grill 逐项确认）

| ID | 问题 | 推荐 | 用户答复 |
|----|------|------|----------|
| Q13 | 反狙击延长后，展示分配期起点用实际结束时间还是原窗口结束时间？ | **原窗口结束时间**（`window_end_at` 不变）：资源"固定分配窗"契约保持原样，settleWindow 零改动；延长 ≤50s 内槽位空置，赢家展示时长被自身触发的延长压缩（最后出价者通常即赢家，公平）；分配期终点不超出公告 deadline | **已确认（2026-08-10）：原窗口结束时间** |
| Q14 | 英式参数（increment/cap/extend）的配置位置与业务默认值？ | `promotion.bprime.auction-rules.*` 按资源位配置，随 route/command 透传 Lua；increment=100、cap=0（禁用）、extend 10s/10s/5 次 | **已确认（2026-08-10）：increment 100 / cap 0 / extend 10-10-5，配置放 `promotion.bprime.auction-rules.*`** |

## 4. 任务定义（翻译工单，依赖序）

### T1（P0）：契约文档先行
1. 重写 `openspec/specs/slot-auction-promotions/spec.md`：GSP → 英式升价（共享价格台阶、步长、反狙击、一口价、第一价格结算、SOLD/NO_BID 终态、单赢家）。
2. 更新 `openspec/specs/bprime-position-auctions/spec.md` 裁决场景（creator 出价 / current bidder amount 保留）。
3. 更新 `docs/contracts/ARCHITECTURE_CONTRACT.md` D10（"GSP 结算不变" → 英式第一价格）+ 幂等/投影小节（终态类型改名）。
4. 更新 `docs/enhancements/promotion_bprime_hotpath_throughput_v1.md`（T1 缓存结构变更记录）+ 本 plan 状态。

### T2（P1）：Lua 热路径翻译
1. `promotion-auction-initialize.lua`：state 增字段（M2），HSETNX 回填；ARGV 增 increment/cap/extend。
2. `promotion-auction-decision.lua`：M3-M6、M13、M14——required 计算、BID_NOT_HIGHER+requiredAmount、winner/currentPrice 写、反狙击延长 + AUCTION_EXTENDED（独立版本）、cap-hit SOLD + AUCTION_SOLD（独立版本）、bidCount、MAX_MONEY。
3. `promotion-auction-close.lua`：M7——AUCTION_SOLD/AUCTION_NO_BID 终态、actualEndAtEpochMs、非 OPEN no-op。
4. `promotion-auction-snapshot.lua`：M9。
5. 每步 `wsl docker exec zhiguang-redis redis-cli` 构造 state/escrow 后手工 EVAL 验证（接受/拒绝+required/延长/SOLD/幂等重放/双终态竞争）。

### T3（P1）：Java 侧翻译
1. `PromotionAuctionHotStateRepository` / `PromotionAuctionWindowRedisInitializer` / `PromotionBidEscrowService` 路由构建：透传英式参数（M19）。
2. `PromotionRedisDecisionAdapter`：ARGV 扩展 + requiredAmount 解析。
3. `PromotionDecisionProjectionService`：settleWindow 第一价格 + AUCTION_SOLD/AUCTION_NO_BID 触发 + AUCTION_EXTENDED 仅推进 checkpoint（M8）。
4. `PromotionDecisionFanoutService` / `PromotionPublicUpdateCoalescer` / 实时事件：AUCTION_EXTENDED 新增、WINDOW_CLOSED payload 扩展（M17）。
5. `PromotionBidPriceCache`：key 改 `windowId` + 终态 `invalidate`（M10）；`PromotionCommandSubmissionService.fastReject` 相应调整。
6. `PromotionBidRoute` / `PromotionAuctionCommand` / DTO：加性字段（requiredAmount、英式参数、终态载荷）。
7. `PromotionBPrimeProperties` + `application.yml`：英式参数 + `Rules.Validate`/`boundAntiSnipe` 同构校验（M15）。
8. `PromotionAuctionSettlementPlanner` / 对账 `PromotionAuctionCompensationService` / `PromotionEscrowRedisProjectionReconciler`：第一价格重放对齐（M8）。

### T4（P1）：测试
1. 更新 `PromotionCommandSubmissionServiceTest`（fast-reject 缓存语义 5 个测试）、`PromotionRedisDecisionAdapterTest`、`PromotionRedisDecisionAdapterRedisIntegrationTest`、`PromotionAuctionWindowCloserTest`、`PromotionRedisSnapshotAdapterTest`、`PromotionDecisionProjectionServiceTest`、`PromotionDecisionFanoutServiceTest`、`PromotionBidWebSocketProtocolServiceTest`、`PromotionAuctionSchedulerTest`。
2. 新增行为测试：required=current+increment、cap 一步到位、反狙击延长/超限不延长、cap-hit 提前 SOLD、双终态竞争、第一价格结算、NO_BID 全释放。
3. Lua luaparser 语法 + EVAL 行为验证（T2 已含）。

### T5（P2）：压测
1. 改造 `loadtest/scripts/promotion-ws-realistic.js`：知情对抗行为模型（VU 从 RANKING_DELTA/快照读当前价，出 `current + random(1..N)`；保留 stale/retry/double-submit/insufficient 混合）。
2. seed 改 `slot_count=1`；清库 + 重启 app（priceCache 陈旧边界）。
3. WSL 部署 → 对比基线（预拒率 99%+ 形态、ack p95 保持低值、missing 0、cap/延长事件计数）。

### T6（P2）：收尾
1. 压测报告 `loadtest/reports/`。
2. 分 commit：契约文档、Lua、Java、测试、压测报告。
3. 全量 `mvn test-compile` + 目标测试类全绿。

### 4.1 任务 × 决策边界映射（每个任务的适用决策、边界与验收锚点）

| 任务 | 应用的决策（§2 M / §3 Q） | 边界（不做 / 保持） | 验收锚点 |
|------|---------------------------|---------------------|----------|
| T1 契约文档 | Q1（第一价格）、Q2（反狙击）、Q3（一口价）、Q4（reserve→startPrice）、Q5（终态类型）、Q6（单赢家）、Q8（拒绝原因映射）、Q11（`window_end_at` 不改）、Q12（实时事件）、Q13（分配期起点）、Q14（参数配置） | 只改文档不改代码；**直接重写现有 spec.md，不新建 spec 文件**；GSP 术语（top M bids / next ranked bid / GSP settlement）清零；bprime-position spec 只更新裁决场景，不动参与者模型 | grep spec 无 GSP 残留；D10 表述为英式第一价格；本 plan §3 决策与文档一致 |
| T2 Lua 热路径 | Q1-Q5 的机制部分 + M2（initialize 字段/HSETNX 回填）、M3（required/requiredAmount/`REDIS_STATE_INCOMPLETE`）、M4（共享价写）、M5（反狙击+AUCTION_EXTENDED）、M6（cap-hit SOLD+AUCTION_SOLD）、M7（close.lua SOLD/NO_BID/no-op）、M9（快照）、M12（版本连续）、M13（MAX_MONEY）、M14（bidCount）；Q14 参数经 ARGV 注入 | **8 KEYS 结构不变**（state/commands/ranking/campaign/escrow/events/pub/wakeup）；Lua 返回 JSON `{status,...}` 形状不变；拒绝原因字符串不变（Q8）；**不引入 per-user dedupe**（Q7）；无 WS/HTTP 契约面 | EVAL 用例矩阵全过：接受/拒绝+required/延长/超限不延长/SOLD/幂等重放/双终态竞争；luaparser 语法过 |
| T3 Java 侧 | M7 调用方迁移（closer ZREM、`WINDOW_CLOSED` 类型删除）、M8（settleWindow 第一价格）、M10（priceCache `windowId` + 终态 invalidate）、M11（T4 幂等保留）、M15（Rules.Validate/boundAntiSnipe）、M17（fanout/实时事件）、M19（route/command 透传）；Q13（settleWindow 分配期零改动）、Q14（配置结构） | WS ack 与实时事件**只加性扩展**（requiredAmount、终态载荷、AUCTION_EXTENDED 新事件名）；**schema 零改动**（`slot_count`/`reserve_price` 列保留）；线程池/队列有界配置不动；`WINDOW_CLOSED` 字符串在投影/实时/对账/指标/测试内**一次性同步迁移**，不留兼容别名 | 目标单测类全绿；投影对 AUCTION_EXTENDED 只推进 checkpoint 不落 MySQL；cap-hit 后下一出价返回 `WINDOW_CLOSED` 而非误拒 |
| T4 测试 | 全部 Q/M 的可观察行为（回归锚点 = §5 验收 1-5） | 测试暴露的缺陷**回 T2/T3 修**，不在 T4 内打补丁；不新增容器依赖（Redis 集成测试沿用既有 WSL 环境） | 9 个既有测试类更新后全绿 + 新增行为测试（required=current+increment、cap 一步到位、反狙击边界、cap-hit 提前 SOLD、双终态竞争、第一价格结算、NO_BID 全释放）全绿 |
| T5 压测 | Q6（seed `slot_count=1`）、Q14（参数默认值即压测配置）、M10（预拒率形态）、知情对抗行为模型（T2 语义的流量复现） | 每轮**重启 app**（priceCache 陈旧边界，真实生产窗口不可重置）；WSL 容器化 k6，**不新拉镜像**；不改任何契约 | ack p95 ≤ 基线 10ms、missing 0、预拒率 99%+ 形态、cap/延长事件计数可观测 |
| T6 收尾 | 无新决策；落地 §0 规则（有界性、契约文档先行、分 commit） | 未通过验收的任务**不合并**；commit 按域拆分（契约文档/Lua/Java/测试/压测报告） | `git log` 4 个以上 commit、工作区干净、`mvn test-compile` 通过 |

## 5. 验收标准

1. `decision.lua` 裁决与 Go `place_bid.lua` 逐行语义等价：required/cap/extend/cap-hit/双事件/幂等重放（EVAL 用例矩阵）。
2. `close.lua` 终态与 Go `close_auction.lua` 等价：SOLD/NO_BID、NOT_DUE、ALREADY_TERMINAL。
3. 结算：winner 付第一价格（=终态 winningAmount），其余释放；NO_BID 无 allocation；cap-hit 提前结算一次。
4. fast-reject 缓存窗口级：`bidAmount <= currentPrice` 本地拒，预拒率形态 99%+；幂等重试仍放行 Lua 重放。
5. WS ack / 实时事件：既有字段不变（加性扩展）；`BID_NOT_HIGHER` 带 requiredAmount。
6. 单元测试全绿；压测 ack p95 ≤ 基线（10ms）、missing 0。

## 6. 风险与边界

- **WINDOW_CLOSED 类型删除的 blast radius**：投影/实时/对账/指标/集成测试都引用该字符串（grep 已确认 ~10 处），T1 文档与 T3 代码必须同步，避免中间态不一致。
- **priceCache 陈旧**：压测每轮重启 app（真实生产窗口不可重置，可接受）。
- **旧热状态缺新字段**：initialize.lua HSETNX 回填 + decision.lua 缺 increment 即 `REDIS_STATE_INCOMPLETE`，双保险。
- **Redis 7.4 无 HSETEX**：新命令先在容器验证；**Lua 5.1 local function 先声明后使用**。
- **反狙击与分配期**：已确认分配期起点 = 原 `windowEndAt`（Q13），settleWindow 无需改动；实际结束时间仅存在于 Redis state 与终态事件载荷（`actualEndAtEpochMs` 仅供展示/审计，不落 allocation）。
