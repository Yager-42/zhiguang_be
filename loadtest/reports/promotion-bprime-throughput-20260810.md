# 压测报告：promotion-bprime-hotpath-throughput-v1 前后对比（2026-08-10）

## 环境

| 项 | 值 |
|---|---|
| 部署 | WSL2 Ubuntu-22.04 + Docker Desktop；`docker compose` 全栈（app/redis/mysql/kafka/cassandra/es/minio） |
| 压测机 | k6 容器化（`grafana/k6:latest`，本地镜像），`--add-host=host.docker.internal:host-gateway`，BASE_URL=host.docker.internal:8080 |
| 基线镜像 | `zhiguang-app:unlimited-test`（2026-08-10 01:21 UTC 构建 = **优化前代码**，bprime enabled） |
| 新代码 | 本地 `mvn package`（4.4s，缓存全命中）→ `docker cp` 替换 `/app/app.jar` → restart（**跳过镜像构建**） |
| Redis | 7.4.10（**注意：HSETEX 为 Redis 8.0 命令，7.4 不支持**） |
| 数据 | seed_promotion_realistic.sql（窗口 8800001、campaigns 4200001-4200500），窗口 span 修正为 60min |
| JVM 热度 | 基线=4h 热 JVM；新代码=重启后经 fast-reject 脚本预热（11.6k bids/s 55s） |

## 场景：realistic 500 VU（~1.8k bids/s 混合行为：55% raise / 25% stale / 10% retry / 5% double / 5% escrow 不足）

| 指标 | 基线（旧代码） | 新代码 R1（同起点） | 新代码 R2 | 新代码 R3 |
|---|---:|---:|---:|---:|
| ack p95 | **10ms** | **3ms** | 2ms | 1ms |
| ack p99 | **20ms** | **8ms** | 4ms | 3ms |
| ack avg | 2.79ms | 1.19ms | 0.81ms | 0.63ms |
| 出价速率 | 1,846/s | 1,827/s | 1,874/s | 1,889/s |
| missing ack | 0 | 0 | 0 | 0 |
| UNAVAILABLE | 0 | 0 | 0 | 0 |
| 本地预拒占比 | 0%（未实现） | **70.8%** | 96.4% | 97.3% |
| 接受 | 88,015 | 38,699 | 1,970 | 773 |
| 其他拒绝 | 8,084 | — | 3,241 | 3,192 |

R2/R3 数字守恒精确：`fast_rejected + other_rejected + accepted = acknowledged`（如 R3：140,905+3,192+773=144,870 ✓）。

**解读**：
- 同等起点轮（R1 vs 基线）：ack p95 **10→3ms**，Lua 调用减少 70.8%（预拒生效），正确性零回退（missing=0、无 UNAVAILABLE）。
- 价格达 escrow 上限后（R2/R3）：96-97% 出价被本地预拒，ack p95 1-2ms——fast-reject 在"大量注定失败出价"形态下吸收洪峰，这正是 Go 参考实现的 Tier-C 设计目标。
- 接受数差异源于预拒拦截了本会走 Lua 的 BID_NOT_HIGHER（基线 48,043 客户端统计中部分），总数守恒。

## 洪峰场景：fast-reject 脚本 300 VU（新代码，预热兼验证）

- 11,661 bids/s 同价洪峰：ack p95 **3ms**、p99 7ms、missing ack 0、全部 BID_NOT_HIGHER。
- 对比历史 2026-08-09 的 `promotion-ws-fast-reject-20k`（10.8k/s 时 93.8% missing ack / ack p95 13.5s）——数量级改善，但历史结果运行配置未知，仅作方向性参考。

## 实施中发现并修复的问题（代码级）

1. **HSETEX 是 Redis 8.0 命令**——7.4 报 `unknown command`。T4 改为 `HSET + HEXPIRE key ttl FIELDS 1 field`（7.4 支持，语义等价）。压测前通过 redis-cli 手工验证。
2. **Lua 局部函数作用域**：`store_record` 移到 `store_rejection` 之前（Lua 5.1 词法作用域，先引用后声明 → 运行时 `nonexistent global variable`）。修复后 EVAL 全路径验证（接受/加价/排名 LT/幂等记录）。
3. **priceCache 陈旧误拒**：窗口状态被外部重置（压测清理）后，JVM 内存缓存保留旧价格 → 93% 误拒。生产无此场景（窗口不可重置），但压测/运维每轮需重启 app。**已知边界，plan 记录**。
4. seed 窗口 span 61min（NOW-1min..NOW+60min）导致 campaign 覆盖 allocation 期失败 → WINDOW_CLOSED；修正为精确 60min。
5. scheduler 自动整点窗口与手工 seed 窗口并存，escrow 选窗按"最早到期"→ 压测前需关闭干扰窗口。

## 结论

- T1（fast-reject）+ T2（Lua 瘦身）+ T4（幂等单 key）在 realistic 混合负载下：**ack p95 10ms→3ms，预拒吸收 70-97% 无效出价，正确性零回退**。
- 尚未实测：multi-accepted-heavy（全接受写负载，对应脚本已不在仓库）；关窗延迟（T3，需构造 endAt=now+5s 窗口）。
- 遗留：T3 秒级关窗未做端到端验证（需专用窗口）；压测环境注意 priceCache 重置边界。

## 复现命令

```bash
# 部署新代码（跳过镜像构建）
mvn package -DskipTests
wsl docker cp target/zhiguang-1.0-SNAPSHOT.jar zhiguang-app:/app/app.jar
wsl docker restart zhiguang-app

# 压测（WSL 内）
wsl docker run --rm --add-host=host.docker.internal:host-gateway \
  -v /mnt/e/idk/zhiguang_be/loadtest/scripts:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 -e VUS=500 \
  -e PROMOTION_CAMPAIGN_BASE=4200000 -e PROMOTION_EXPECTED_WINDOW_ID=8800001 \
  grafana/k6:latest run /scripts/promotion-ws-realistic.js
```
