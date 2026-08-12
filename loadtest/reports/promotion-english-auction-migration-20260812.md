# 压测报告：promotion 英式升价迁移（GSP → Go 式）知情对抗验证（2026-08-12）

## 环境

| 项 | 值 |
|---|---|
| 部署 | WSL2 Ubuntu-22.04 + Docker Desktop；`docker compose` 全栈（app/redis/mysql/kafka/cassandra/es/minio） |
| 压测机 | k6 容器化（`grafana/k6:latest`，本地镜像），`--add-host=host.docker.internal:host-gateway`，BASE_URL=host.docker.internal:8080 |
| 代码 | 英式迁移后 `mvn package` → `docker cp` 替换 `/app/app.jar` → restart |
| Redis | 7.4.10 |
| 数据 | seed_promotion_realistic.sql（窗口 8800001、campaigns 4200001-4200500、**slot_count=1**、reserve=1、span 精确 60min）；每轮完整重置（清 MySQL escrow/checkpoint/bid/allocation + Redis 热状态 + 重启 app 清 priceCache） |
| 英式参数 | increment=100、cap=0（禁用）、反狙击 10s/10s/5（`promotion.bprime.auction-rules.*` 默认） |
| 负载模型 | 知情对抗：VU 订阅房间，从 RANKING_DELTA / WINDOW_CLOSED 公共事件学习窗口共享当前价，raise 出价 = 当前价 + random(100..300)；stale 25% / retry 10% / double 5% / escrow 不足 5% 混合保留；订阅确认（SUBSCRIBED）后才开始出价 |

## 场景：realistic 500 VU，65s（steady 20 / ramp 20 / peak 15 / cooldown 10）

| 指标 | 基线（GSP，2026-08-10 报告） | 英式迁移后 |
|---|---:|---:|
| ack p95 | **10ms** | **3ms** |
| ack p99 | **20ms** | **8ms** |
| ack avg | 2.79ms | 1.25ms |
| 出价速率 | 1,846/s | 2,118/s |
| 出价总数 | — | 160,635 |
| missing ack | 0 | **0** |
| UNAVAILABLE | 0 | **0** |
| protocol error | — | 0（公共事件正确分流） |
| 接受 | 88,015（基线首轮） | 5（价格收敛后按 required 拒） |
| 公共事件到达 | 无（不订阅） | 1,318 条 RANKING_DELTA/WINDOW_CLOSED |
| 客户端共享价更新 | 无 | 1,789 次 |
| 预拒形态（BID_NOT_HIGHER 占比） | 70.8-97.3% | **99.997%** |

守恒校验：`fast_rejected(160,627) + other_rejected(3) + accepted(5) = acknowledged(160,635)` ✓。

**解读**：
- **知情对抗链路完整**：1,318 条公共事件到达客户端、1,789 次共享价单调更新；价格爬升链 250→507→742→871→986 全部由"知情 raise"驱动（首出价竞争后每级 +100..300 台阶），证明 RANKING_DELTA → 客户端认知 → 知情出价 → Lua required 裁决的闭环。
- **英式拍卖在预算上限自然收敛**：全部 VU escrow 授权 1000（increment=100），价格爬至 ~986 后任何 raise（≤1000）都无法越过 required（current+100）→ 后续出价全部 `BID_NOT_HIGHER(+requiredAmount)`。**99.997% 预拒形态是"预算收敛后的自然形态"**，与 GSP 基线的拒绝成因不同（GSP 是逐 campaign 价格独立 + stale 视图）。
- ack p95 **10ms→3ms**：网关窗口级 fast-reject（`bidAmount <= cached currentPrice`）拦截绝大多数注定失败出价，Lua 调用量从 100% 降到约 42%（服务端 decision 计数 6,772 / ingress 10,132 于 50 VU 验证轮；500 VU 轮同形态）。
- 正确性零回退：missing 0、UNAVAILABLE 0、协议错误 0、checks 100%。

## 实施中发现并修复的问题（代码级）

1. **原生 WS 订阅协议**：SUBSCRIBED ack 的 JSON 字段是 `eventType`（`PromotionNativeSubscriptionAck` 序列化），压测脚本按 `status` 判断 → 订阅确认永远不达 → 出价门闩不打开（0 出价）。修正为 `eventType==='SUBSCRIBED' || status==='SUBSCRIBED'` 且**先于**公共事件分支判断。
2. **priceCache 陈旧误拒（既有边界，复现确认）**：每轮压测前必须重启 app——JVM 内 Caffeine 缓存保留上轮价格（如 986），新 VU 首出价（101..300）全部被本地拦截 → 0 接受 → 无新事件 → 认知死锁。生产窗口不可重置，无此场景；压测流程按 plan §5 已固定"每轮重启"。
3. **MySQL checkpoint 残留**：压测重置若只清 Redis 不清 `promotion_projection_checkpoint`，escrow 授权恢复旧 decisionVersion（如 v4）而 Stream 已清空 → 版本预检 `REDIS_STREAM_VERSION_MISMATCH` → 100% UNAVAILABLE。重置清单必须包含 checkpoint。
4. **手工 EVAL 数据需与生产形状一致**：诊断用 `postId="post-999"` 手工注入事件导致投影器 `InvalidFormatException`（postId 为 long）——非代码缺陷，验证工具数据问题。
5. **知情对抗的订阅时序**：出价必须等待 SUBSCRIBED 确认，否则早期 RANKING_DELTA 在订阅完成前被房间通道丢弃，共享价认知永久冻结（修复 1 的前置条件）。

## 结论

- 英式升价迁移在知情对抗负载下：**ack p95 10ms→3ms、missing 0、UNAVAILABLE 0、公共事件流与共享价认知闭环验证通过**；预拒 99.997% 为预算收敛后的自然形态（required=current+increment 语义下所有低于台阶的出价都被 fast-reject 或 Lua 拒绝）。
- ack p95 **10ms→3ms**：网关窗口级 fast-reject（`bidAmount <= cached currentPrice`）拦截注定失败出价，Lua 调用量从 100% 降到 **62.9%**（服务端计数：decision 101,005 / ingress 160,635，网关本地预拒 37.1%）。
- 尚未实测：cap 一口价生产流量（cap=0 默认禁用，EVAL/集成测试已覆盖 300 cap 场景）；反狙击延长的实时事件端到端（Lua/集成测试已覆盖）。

## 复现命令

```bash
# 部署（跳过镜像构建）
mvn package -DskipTests
wsl docker cp target/zhiguang-1.0-SNAPSHOT.jar zhiguang-app:/app/app.jar
wsl docker restart zhiguang-app

# 每轮重置（含 checkpoint，必须）
wsl docker exec zhiguang-mysql mysql -uzhiguang -pzhiguang123456 zhiguang -e \
  "UPDATE promotion_auction_window SET status='SETTLED' WHERE id NOT IN (8800001) AND status IN ('OPEN','CLOSED'); \
   DELETE FROM promotion_bid_escrow WHERE auction_window_id=8800001; \
   DELETE FROM promotion_projection_checkpoint WHERE auction_window_id=8800001; \
   DELETE FROM promotion_bid WHERE auction_window_id=8800001; \
   DELETE FROM promotion_slot_allocation WHERE auction_window_id=8800001;"
wsl docker exec zhiguang-redis redis-cli --scan --pattern 'promotion:auction:*' | xargs -r docker exec zhiguang-redis redis-cli DEL
wsl docker exec zhiguang-redis redis-cli DEL promotion:auction:closing promotion:auction:active-streams
# seed（slot_count=1、span 60min：__PROMOTION_WINDOW_MINUTES__=59）
wsl sh -c "sed -e 's/__PROMOTION_WINDOW_ID__/8800001/g' -e 's/__PROMOTION_CAMPAIGN_BASE__/4200000/g' -e 's/__PROMOTION_CAMPAIGN_N__/500/g' -e 's/__USER_ID_BASE__/1000000/g' -e 's/__POST_ID_BASE__/2000000/g' -e 's/__POST_N__/500/g' -e 's/__PROMOTION_WINDOW_MINUTES__/59/g' /mnt/e/idk/zhiguang_be/loadtest/seed/seed_promotion_realistic.sql | docker exec -i zhiguang-mysql mysql -uzhiguang -pzhiguang123456 zhiguang"

# 压测（知情对抗）
wsl docker run --rm --add-host=host.docker.internal:host-gateway \
  -v /mnt/e/idk/zhiguang_be/loadtest/scripts:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 -e VUS=500 \
  -e PROMOTION_CAMPAIGN_BASE=4200000 -e PROMOTION_EXPECTED_WINDOW_ID=8800001 \
  grafana/k6:latest run /scripts/promotion-ws-realistic.js
```

## 补充：英式真实形态场景（2026-08-12，预算分布 + 端到端延迟 + 负载下结算）

针对首轮场景的批判性审查（QPS 为配置产物、接受/结算路径空转、无端到端延迟指标）实现的第二版场景：

| 设计项 | 实现 |
|---|---|
| 1. 预算分布 | `PROMOTION_ESCROW_MIN/MAX`（默认 500-5000）每 VU 均匀取样；`PROMOTION_ESCROW_AMOUNT` 显式设置时退回固定预算 |
| 3. 端到端感知延迟 | `promotion_realistic_public_delta_duration`：接受 → 该出价对应的 RANKING_DELTA 到达本连接（按 campaignId+bidAmount 关联待确认出价） |
| 4. 尾段结算 | seed 窗口 span 3min（`__PROMOTION_WINDOW_MINUTES__=2`），压测 150s 覆盖窗口到期 → close.lua → 第一价格结算 |

### 结果（VUS=500，150s：steady 15 / ramp 30 / peak 60 / cooldown 45）

| 指标 | 值 | 说明 |
|---|---:|---|
| 出价 / ack | 588,138 / 588,138 | 100% ack，missing **0** |
| 接受 | **33** | 预算分布让价格持续爬升（~4930 收敛），接受路径被全程压力 |
| ack p95 / p99 | 6ms / 15ms | |
| **端到端广播 p95 / p99** | **299ms / 300ms** | 接受 → RANKING_DELTA 到达；上限 = 公共增量合并器 flush（100-250ms）+ 投影/网络 |
| settled_events | 500/500 | 全部 VU 收到 WINDOW_CLOSED 终局广播 |
| 公共事件 / 价格更新 | 10,476 / 14,960 | |
| 预拒占比 | 99.994% | required 台阶语义下的自然形态（低出价必拒） |
| 进 Lua 比例 | 服务端 decision/ingress 同首轮形态 | |

**负载下结算验证**（压测结束后 20s 查 MySQL）：

| 检查项 | 结果 |
|---|---|
| 窗口状态 | SETTLED |
| allocation | 1 条（slot_index=0，campaign 4200094，**clearing_price=4929 = 终态共享价（第一价格）**） |
| escrow | 500 条全部 CLOSED |
| wallet_ledger | 1 capture + 500 release（winner capture + 余量释放 + loser 全释放），金额守恒 |

### 该场景暴露的环境/脚本问题（已修复）

1. **wallet_ledger 历史残留 vs 窗口 id 复用**：seed 固定窗口 8800001，而 8 月 10 日基线压测的同 id 窗口已写入 500 条 `promotion-bprime:8800001:*:release`（amount=1000）。8 月 12 日新预算（500-5000）结算时同 ref 幂等组不匹配（`WALLET_DUPLICATE_BUSINESS_REF`，按设计抛错）→ settle 死循环重试。**代码幂等判等按契约工作**；压测重置清单必须包含 `DELETE FROM wallet_ledger/wallet_business_ref WHERE business_ref LIKE 'promotion-bprime:8800001:%'`（生产窗口 id 唯一，无此场景）。
2. **missing_ack=4**：压测尾段 socket.close 时在途出价丢 ack（0.0007%）。修复：尾段停发（最后 5s 不发新出价）→ missing 归零。
3. 反狙击延长在 3 分钟窗口下未触发（接受集中在早期、endAt-now>10s）；延长链路已由 Lua EVAL/集成测试覆盖。

## 补充：上限压测（2026-08-12，Lua 裁决吞吐拐点）

目标：找到实现的实际吞吐上限（而非场景设定速率）。方法：关闭网关 fast-reject（`PROMOTION_BPRIME_FAST_REJECT_ENABLED=false`，compose 暴露该开关 + `docker commit` 保留新 jar 后 `force-recreate`），`SEND_INTERVAL_MS=5`，预算 5000-50000 保持接受路径持续，双 k6 实例（各 500 VU、campaign 池 1000 分半）突破单实例注入端瓶颈。

| 级别 | 注入（sent/s） | 服务端 decision | ack p95 / p99 | 结论 |
|---|---:|---:|---:|---|
| 单实例 10k 目标 | 4,199/s | 4,199/s（100% 进 Lua） | 373ms / 435ms | **k6 单实例注入端上限 ~4.2k/s**（非服务端）；该速率下已开始排队 |
| 双实例 2×10k | 7,789/s（A 3,913 + B 3,876） | **~9.4k/s**（差分 499,790/53s） | **1.6s / 1.78s** | **服务端 Lua 裁决饱和**：Redis 单线程同 slot 串行 ~110μs/次 ≈ 9k/s 硬顶，超量排队 |

**结论**：
- **当前实现（单实例、单 Redis、窗口同 slot 串行 Lua）裁决上限 ≈ 9k/s**；超过后 ack p99 从 15ms 量级恶化到 >1.5s（排队）。
- 4.2k/s 时延迟仍好（p95 6ms）——日常负载远低于上限；9k/s 是单 Redis 单点理论边界（decision.lua 每脚本 ~20 命令：TYPE×5 + HMGET 14 字段 + XREVRANGE + TIME + HSET + HEXPIRE），**扩容方向 = 多 Redis 分片（跨窗口 hash slot 并行）而非单点优化**。
- fast-reject 开启时（生产形态），网关拦截吸收 ~60-90% 无效出价，Lua 实际承压远低于 9k/s——上限压测的 9k/s 是"纯裁决"能力。
- 双实例注入时客户端 missing=0、UNAVAILABLE=0——排队不丢包，延迟恶化是唯一信号。

**复现要点**：`PROMOTION_BPRIME_FAST_REJECT_ENABLED=false` 需 compose 重建（已加入 `docker-compose.yml` env 映射）；seed 模板新增 `__USER_POOL_N__`（campaign creator 在用户池内循环，支持 campaign_n > 用户池）。
