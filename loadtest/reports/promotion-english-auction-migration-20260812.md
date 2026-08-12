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
- 迁移正确性由 601 个单测/集成测试（全绿）+ 65 用例 Lua EVAL 矩阵 + 13 用例 Redis 集成测试背书。
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
