# 压测报告：promotion-window-flat-combining-v1（2026-08-12）

## 环境

| 项 | 值 |
|---|---|
| 部署 | WSL2 + Docker Desktop；app/Redis/MySQL/Kafka/Cassandra/Elasticsearch |
| 应用资源 | `zhiguang-app` 4 CPU、JDK 21、G1、`-Xmx2g` |
| Redis | 7.4.10 |
| 压测机 | `grafana/k6:latest` 容器，宿主机回环访问 app:8080 |
| 数据 | 窗口 8800001、campaign 4200001-4200500、单槽、reserve=1；每轮清理 Redis 热状态、checkpoint、bid、allocation、escrow、wallet 压测 businessRef 并重启 app |
| 最终参数 | batch size=256、ARGV=262144 bytes、drainer=8、window pending=8192、global pending=65536 |

## 验证结果

### 同价/低价单窗口洪峰

300 VU，目标 20k/s，15 秒发送，全部命中确定性 `BID_NOT_HIGHER`：

| 指标 | 结果 |
|---|---:|
| sent / ack | 298,675 / 298,675 |
| 实际 ACK 吞吐 | 8,269/s |
| ACK p95 / p99 | 76ms / 168ms |
| missing ACK | 0 |
| protocol error | 0 |
| connection failure | 0 |

该场景由版本化 admission state 在 Java 内零 Redis 拒绝；Redis `EVALSHA` 仅增加 1,104 次（包含初始化、投影和 300 个 prime/probe），未随 298,675 个洪峰请求线性增长。

### 随机金额、持续抬价、无效价混合

300 VU，25 秒发送，峰值目标 20k/s；预算 500..50,000；raise/stale/retry/double/escrow-insufficient 混合：

| 指标 | 结果 |
|---|---:|
| sent / ack | 232,336 / 232,336 |
| 实际 ACK 吞吐 | 5,551/s |
| accepted / fast rejected / other rejected | 142 / 223,716 / 8,478 |
| ACK avg / p95 / p99 | 3.31ms / 16ms / 38ms |
| public delta p95 / p99 | 212.35ms / 276.83ms |
| missing / pending overflow / connection failure | 0 / 0 / 0 |
| protocol error | 0 |
| JVM allocated since restart | 2.746GB |
| batch calls / commands | 5,469 / 10,300 |
| commands per batch avg / max | 1.88 / 124 |
| batch duration avg / max | 1.16ms / 95.71ms |
| local backpressure | 0 |

批次指标证明进入 Redis 的 10,300 个命令合并为 5,469 次 Lua 调用；其余请求由本地 committed floor 拒绝。实际批次平均值受大量低并发尾流影响，峰值批次达到 124。

## 参数选择

保留默认 `256 / 262144 bytes / 8 drainers / 8192 per-window / 65536 global`：

- 峰值实际批次 124，未触及 256 上限；继续增大上限不会改善本轮吞吐，却会提高 Redis event-loop 最坏占用。
- 8 drainer 在单窗口保持唯一 drainer 不变量，在多窗口时仍保留并行度。
- 两轮均无背压、missing ACK 或协议错误；无需扩大 pending 水位。
- ACK p99 38ms（混合）与 168ms（20k/s 同价目标）均低于对应 500ms/300ms 门禁。

## 正确性与发布证据

- `mvn test`：603 tests，0 failures，0 errors，1 skipped。
- 专项服务/Redis/实时测试：24、13、14 tests 分别通过。
- `mvn -DskipTests package` 成功，生成可执行 Spring Boot jar。
- jar 已复制到 `zhiguang-app:/app/app.jar` 并重启；`GET /actuator/health` 返回 `{"status":"UP"}`。
- 真实 Native WebSocket 两轮均完成每请求最终 ACK；混合场景同时验证公共 `RANKING_DELTA`。

## 已知环境噪声

固定窗口 8800001 曾有历史 wallet businessRef；压测复用窗口前必须清理对应 ledger/ref，否则投影按设计报重复账务。生产窗口 id 唯一，不存在该复用条件。
