# zhiguang_be 压测使用指南（操作手册）

> 面向执行者：从零开始到产出压测报告的完整操作流程。所有命令在 **Git Bash / WSL / Linux** 终端执行（`run.sh` 是 bash 脚本，Windows CMD/PowerShell 不可用）。
> 设计原理、场景定义、指标口径见 [`压测方案.md`](./压测方案.md)；本文档只讲怎么跑、怎么读结果。

---

## 0. 快速开始（TL;DR）

```bash
# 1. 准备（一次性）
docker compose up -d                                    # 在仓库根目录启动中间件全栈
# 安装 k6（见 1.1），启动应用（见 2.2）

# 2. 灌数据 + 校验 + 冒烟（每轮压测前）
cd loadtest
./run.sh seed                                           # 幂等，可重复执行
./run.sh verify                                         # 所有 violations = 0 才算合规
# 重启应用（触发 ES 索引自回填）：容器模式 docker compose restart app；宿主直跑重跑 java 进程
k6 run -e VUS=1 -e HOLD=1m scripts/mixed.js             # 冒烟：确认登录/Feed/计数链路通

# 3. 正式压测
./run.sh baseline                                       # 单链路分级加压（8 脚本 × 4 档，约 2 小时）
./run.sh mixed                                          # 混合常态基线（300 VU × 30 分钟）
./run.sh mixed --high                                   # 混合高压（逐级加到拐点）
./run.sh soak                                           # 长稳（300 VU × 2 小时，可选）

# 4. 结果在 results/ 目录（k6 JSON + 摘要 log + 双端指标 TSV）
```


---

## 1. 环境准备

### 1.1 安装 k6

| 系统 | 命令 |
|---|---|
| Windows | `winget install k6 --source winget` 或 `choco install k6`，或从 https://github.com/grafana/k6/releases 下载 zip 解压加入 PATH |
| macOS | `brew install k6` |
| Linux | `sudo gpg -k && sudo apt-key adv ... `（官方源，见 https://k6.io/docs/getting-started/installation/） |

验证：`k6 version`。

### 1.2 其他依赖

- **Docker Desktop**（中间件全栈：mysql/redis/kafka/rocketmq/cassandra/es/minio）
- **JDK 21 + Maven**（应用本体；本机已备 `E:\idk\zhiguang_be\jdk-21`）
- **Git Bash 或 WSL**（执行 run.sh 必需）
- `loadtest/seed/node_modules` 已含 bcryptjs（种子密码哈希生成用），无需安装

## 2. 启动被测环境

> 从零部署（中间件全栈 + 应用构建/启动/验证）见 **[`DEPLOY.md`](./DEPLOY.md)**；本章只给压测视角的最小命令。

### 2.1 一键部署（推荐：中间件 + 应用全容器化）

```bash
docker compose up -d --build    # 在仓库根目录；首次构建 10~30 分钟（拉镜像 + Maven 依赖）
docker compose ps               # 12 个服务，app 显示 healthy 即就绪（等 "Started ZhiGuangApplication" 日志）
curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

依赖顺序自动处理（app 等全栈 healthy + 初始化容器完成）；Kafka 双 listener 已配（容器内 `kafka:9092`，宿主工具 `localhost:9094`）。详见 `DEPLOY.md`。

### 2.2 宿主机直跑（备选：应用不在 Docker 里）

```bash
docker compose up -d            # 只起中间件
MAVEN_OPTS="-Xmx2g -XX:+UseG1GC" mvn spring-boot:run   # JDK21 + Maven；或 IDE 直跑
```

注意：宿主直跑时应用连 `localhost`（application.yml 默认值），Kafka 用 `localhost:9094`。

### 2.3 环境开关（影响压测内容）

| 场景 | 需要开启 |
|---|---|
| 竞价 B' 全链路（scripts/promotion.js） | `PROMOTION_BPRIME_ENABLED=true docker compose up -d`（容器）或启动参数（宿主直跑）；需 RocketMQ |
| 推荐混排（Gorse） | `docker compose --profile recommendation up -d gorse` + `GORSE_ENABLED=true` 重启 app（可选，默认关） |
| fanout 全链路 | 需 Canal 开启的测试环境（本地 `canal.enabled=false`，只能压到写入 outbox 为止） |

## 3. 灌数据（每轮压测前）

```bash
cd loadtest
./run.sh seed          # 默认：1000 用户 + 500 帖子 + 每用户关注 20 人 + 推广种子 + Cassandra 正文/inbox + Redis SDS 预热
```

常用规模覆盖：

```bash
USER_N=2000 POST_N=1000 ./run.sh seed                      # 扩规模
LARGE_FOLLOWER_N=10000 USER_N=11000 ./run.sh seed          # 大V 场景（≥1 万粉触发 fanout pull + author_head 读路径）
./run.sh seed --force                                      # 先清理全部种子再灌（重灌）
```

**灌数后的硬性步骤：**

```bash
./run.sh verify        # 输出 12 组合规检查：violations 必须全 0；actual 列人工核对
# 重启应用一次（容器: docker compose restart app），触发 ES 索引自回填（SearchIndexInitializer）
curl "http://localhost:9200/zhiguang_content_index/_count"   # count > 0 才算搜索数据就绪
```

跳过 `./run.sh verify` 直接压测 = 数据不合规风险自担（自关注/孤儿外键会让结果失真）。

> 说明：种子用户走 SQL 直插 + 密码登录（`Loadtest@123`），因为注册验证码只进服务端日志无法自动化。种子数据全部为合成值（手机号 `139` 号段序号、邮箱 `.local` 保留域），不指向真实个人。

## 4. 执行压测

### 4.1 冒烟（必做，1 分钟）

```bash
k6 run -e VUS=1 -e HOLD=1m scripts/mixed.js
```

通过标准：`checks` 全部通过、`http_req_failed` ≈ 0、无 `login failed` 日志。失败先查第 6 节 FAQ。

### 4.2 单链路分级加压（找各链路容量拐点）

```bash
./run.sh baseline
# 等价于：对 auth feed counter comment relation search wallet promotion 每个脚本跑 VUS_LEVELS="100 300 600 1000" 四档
```

- 每档持压 `HOLD=4m`，8 脚本 × 4 档 ≈ 2 小时
- 只想压重点链路：`BASELINE_SCRIPTS="feed counter" ./run.sh baseline`
- 调档位：`VUS_LEVELS="100 300 500" HOLD=3m ./run.sh baseline`
- 竞价链路需要 bprime 开启，否则单独跳过：`BASELINE_SCRIPTS="auth feed counter comment relation search wallet" ./run.sh baseline`

### 4.3 混合基线 + 高压

```bash
./run.sh mixed                 # 常态基线：MIXED_VUS=300 × MIXED_HOLD=30m
./run.sh mixed --high          # 高压：按 VUS_LEVELS 逐级加压至拐点
```

常态基线更推荐**固定速率模式**（压测机开销小、结果可复现）：

```bash
# 手动跑固定 QPS 的混合负载（RATE 模式，VU 只做资源上限）
k6 run -e RATE=100 -e VUS=300 -e HOLD=10m scripts/mixed.js
k6 run -e RATE=200 -e VUS=400 -e HOLD=10m scripts/mixed.js
```

### 4.4 单机模式（只有一台机器）

```bash
K6_DOCKER=1 ./run.sh baseline        # k6 容器化，默认限 2 核 + 1GB；BASE_URL 自动切 host.docker.internal
K6_CPUS=1 K6_MEM=512m K6_DOCKER=1 ./run.sh mixed    # 更紧配额，CPU 让给 JVM
```

单机结论分级：**瓶颈定位（哪条链路差）可信；绝对 QPS/P99 是保守值**，对外宣称前需双机复测（详见压测方案 10.7）。

### 4.5 长稳

```bash
./run.sh soak              # SOAK_VUS=300 × SOAK_HOLD=2h
```

长稳观察点（压测方案 S10）：通知桶 30s KEYS 扫描是否引起 Redis 延迟抖动、计数聚合桶积压、`feed:public:pages` 键空间增长、对账扫描资源竞争。

### 4.6 手动单脚本（调试用）

```bash
k6 run -e VUS=300 -e HOLD=5m -e P95=1500 scripts/feed.js      # 放宽阈值防误报
k6 run -e VUS=200 -e HOLD=5m -e HOT_POST_ID=2000002 scripts/counter.js   # 换热帖
k6 run -e VUS=100 -e HOLD=5m -e PROMO_CAMPAIGN_ID=3000001 -e PROMO_WINDOW_ID=3000002 scripts/promotion.js
```

## 5. 结果解读

### 5.1 产出文件（results/）

```
results/
├── baseline-feed-100.json        # k6 完整指标（可导入 Grafana/k6 分析）
├── baseline-feed-100.log         # k6 终端摘要（最快读取方式）
├── sut-baseline-feed-100.tsv     # SUT 侧采样（Redis/MySQL/Kafka/ES/应用健康）
├── loadgen-baseline-feed-100.tsv # 压测机侧采样（CPU/内存/网络/TCP/FD）
└── metrics/                      # 同上的汇总目录
```

### 5.2 看摘要（.log 尾部）

```text
http_reqs:        123456, 512/s, 512/s
http_req_duration:{ avg: 45ms, min: 2ms, med: 30ms, max: 900ms, p(90): 88ms, p(95): 120ms, p(99): 300ms }
http_req_failed:  0.12%
checks:           99.88%
```

每档记录：**QPS（http_reqs 的 /s）、P95/P99、错误率** → 填压测方案第 7 节模板。

### 5.3 评论单机吞吐专项

`comment-throughput.sh` 会在 k6 `setup` 阶段预先登录 `TOKEN_POOL` 个用户；加压阶段只复用 token，不把登录请求混入评论链路。除 drain 场景外，每轮先按目标 QPS 预热 `WARMUP_SECONDS`，随后再运行 `HOLD` 指定的稳态测量窗口。专项汇总中的 QPS、评论读 P95/P99 和业务错误率只统计稳态窗口。

脚本每次启动时默认暂停 app，将四个 `comment-events` 压测消费组推进到 Topic 最新 offset，再启动 app 并确认各分区 offset 已提交且 lag 为 0；校验失败会拒绝开跑。该行为只用于隔离压测环境中的历史副作用事件，可用 `RESET_EVENT_GROUP_OFFSETS=0` 关闭，不能用于生产环境。

```bash
RATE=2000 HOLD=30s WARMUP_SECONDS=10 TOKEN_POOL=32 ./comment-throughput.sh mixed
```

每次脚本启动默认先清理压测用户产生的旧评论、待处理记录、对应 outbox 和评论缓存，然后将四个
`comment-events` 消费组推进到最新 offset。应用恢复健康后，Kafka lag 与 Hikari pending 必须连续
三个采样周期保持为 0 才会开压。可分别用 `RESET_COMMENT_DATA=0`、
`RESET_EVENT_GROUP_OFFSETS=0` 关闭清理或 offset 重置。
清理评论数据后必须保留 `WARMUP_SECONDS>0`，否则冷缓存、连接池和 JVM 初始化延迟会混入稳态
P95/P99；只有明确测试冷启动时才设置 `ALLOW_COLD_MEASUREMENT=1`。产物中的
`measurement_mode` 会标记本轮是 `steady-after-warmup` 还是 `cold-start`。
`BASE_URL` 供 k6 容器访问应用，`SUT_BASE_URL`（默认 `http://localhost:8080`）供 WSL
侧的 Actuator 门禁和指标采样使用，两者不要混用。
压测后可运行 `./comment-throughput.sh idle`，等待事件自然消费至 lag=0 且 Hikari pending=0；
该命令不会清数据或重置 offset。

`VUS` 是最大 VU，默认等于 `RATE`；`PREALLOCATED_VUS` 默认等于 `VUS`，在场景开始前完成 VU 初始化，避免测量中动态扩容。VU 不执行登录，统一复用 setup 阶段创建的 token 池。SUT 采样默认每 10 秒一次，读取 Redis/MySQL 的合并状态和应用已缓存的 Actuator 指标；`COLLECT_KAFKA_LAG=1` 才会额外调用单个消费组的 Kafka CLI。容量测试默认不实时写入体积很大的 k6 原始 JSON；需要逐请求时序诊断时显式设置 `RAW_JSON=1`。

### 5.4 判定（写报告时按此口径）

| 信号 | 含义 |
|---|---|
| QPS 不再随 VU 增长 | 容量拐点（先排除压测机瓶颈，见下行） |
| `loadgen-*.tsv` 的 cpu_pct > 80 | 压测机先到顶，本级无效，加压测机资源后重测 |
| `sut-*.tsv` 的 redis_ops 峰值 / mysql_threads=10 打满 / kafka_lag 上涨 | SUT 侧瓶颈归属 |
| 常态标准 | 错误率 < 0.5% 且 P99 稳定 → 该档为常态指标 |

## 6. 常见问题（FAQ）

| 症状 | 原因 | 处理 |
|---|---|---|
| 日志出现 `login failed` | 种子用户没灌 | `./run.sh seed` 后重试；确认 BASE_URL 正确 |
| 全部 401 | access token 过期/种子密码不符 | 脚本会自动重登；确认 PASSWORD 与 seed 一致（默认 Loadtest@123） |
| `relation.follow accepted` 检查失败、body=false | 令牌桶限流（100/1s/用户） | 正常现象，脚本已节流；看 `relation_rate_limited` 计数 |
| 竞价 500 / commandId 空 | `PROMOTION_BPRIME_ENABLED` 未开、窗口过期或 broker 广播地址不可达 | 开启后重启应用；执行 `./run.sh seed` 刷新窗口（有效期 50 分钟）；确认 broker 广播 `rocketmq-broker:10911` |
| 搜索返回空 | ES 未回填或 IK 镜像未构建 | `docker compose up -d --build elasticsearch` 后重启应用；用 `curl :9200/zhiguang_content_index/_count` 验证 |
| 详情 P99 异常高 | Cassandra 正文没灌 → 回源 content_url 外网 | 确保 `./run.sh seed` 的 cassandra 步骤执行成功 |
| 计数读全为 0 且慢 | SDS 缺失触发重建（限速+单飞） | 正常路径；想压重建风暴就保持这样，想压稳态读先跑 `seed/warm_sds.sh` |
| `./run.sh` 报 command not found | Windows 下 exec 位丢失 | 用 `bash run.sh ...` 执行 |
| `K6_DOCKER=1` 时连不上应用 | BASE_URL 用了 localhost | 容器模式自动切 host.docker.internal；手动覆盖 `BASE_URL=http://host.docker.internal:8080` |
| `loadgen-*.tsv` cpu_pct 高 | 压测机吃紧 | 降 VU / 用 RATE 模式 / 收紧 K6_CPUS 后重测 |
| 帖子翻页结果不变 | Feed 三级缓存生效中 | 正常；看 feed.public 命中场景的 QPS 即为缓存命中上限 |
| k6 出现负 rate、约 49s 假延迟或 Snowflake `ClockBackwardException` | Windows Time 服务未同步；WSL 先继承主机快时钟，再被 Linux NTP 阶跃回拨 | 管理员 PowerShell 执行 `sc.exe config W32Time start= auto`、`sc.exe start W32Time`、`w32tm /config /manualpeerlist:"ntp.aliyun.com,0x9 time.windows.com,0x9" /syncfromflags:manual /update`、`w32tm /resync /rediscover`，随后 `wsl --shutdown`；`comment-throughput.sh` 会在偏差超过 1 秒时拒绝开跑 |

## 7. 参数速查表（全部可选，均有默认值）

### k6 脚本通用（-e 传入）

| 参数 | 默认 | 说明 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080`（K6_DOCKER=1 时 `http://host.docker.internal:8080`） | 被测应用地址 |
| `VUS` | 100 | 峰值 VU（ramping-vus / constant-vus）或 maxVUs（RATE 模式） |
| `HOLD` | 5m | 持压时长（RATE/SOAK 模式 = 总时长） |
| `RAMP` / `RAMP_OUT` | 30s / 30s | 爬升/退出时长 |
| `RATE` | 0（不启用） | >0 时切 constant-arrival-rate，固定 N 次迭代/秒 |
| `SOAK` | 0 | 1 时切 constant-vus（长稳） |
| `P95` | 800 | 阈值：http_req_duration p95 超过即标红 |
| `USER_POOL` | 1000 | 登录用户池大小（须 ≤ 种子 USER_N） |
| `PASSWORD` | Loadtest@123 | 种子密码 |
| `POST_COUNT` | 500 | 随机帖范围 |
| `HOT_POST_ID` | POST_ID_BASE+1 | 热帖（计数/混排场景） |
| `PROMO_CAMPAIGN_ID` / `PROMO_WINDOW_ID` | 3000001 / 3000002 | 竞价场景 |
| `HOT_TERMS` | java,spring,算法,数据库,压测,知光 | 搜索热词池 |

### run.sh 编排（环境变量）

| 参数 | 默认 | 说明 |
|---|---|---|
| `K6_DOCKER` | 空 | 1 = k6 容器模式（单机限核） |
| `K6_CPUS` / `K6_MEM` / `K6_IMAGE` | 2 / 1024m / grafana/k6 | 容器配额 |
| `BASELINE_SCRIPTS` | auth feed counter comment relation search wallet promotion | 单链路集合 |
| `VUS_LEVELS` | 100 300 600 1000 | 分级加压档位 |
| `HOLD` | 4m | baseline/mixed--high 每档持压 |
| `MIXED_VUS` / `MIXED_HOLD` | 300 / 30m | 混合常态 |
| `SOAK_VUS` / `SOAK_HOLD` | 300 / 2h | 长稳 |
| `USER_N` / `POST_N` / `FOLLOW_PER_USER` | 1000 / 500 / 20 | 种子规模（seed 子命令） |
| `LARGE_FOLLOWER_N` / `LARGE_AUTHOR_ID` | 0 / 1000001 | 大V 场景 |
| `USER_ID_BASE` / `POST_ID_BASE` | 1000000 / 2000000 | 种子 ID 起始（改前先 `seed --force`） |
| `SAMPLE_INTERVAL` / `SAMPLE_DURATION` | 15 / 21600 | 采样器节奏 |

### 参数边界（违反会怎样）

| 参数 | 约束 | 违规后果 |
|---|---|---|
| `USER_N` | ≥ 2 | run.sh seed 直接退出 |
| `FOLLOW_PER_USER` | < `USER_N` | run.sh seed 直接退出 |
| `LARGE_FOLLOWER_N` | ≤ `USER_N - 1` | run.sh seed 直接退出 |
| `USER_POOL`（k6） | ≤ 种子 `USER_N` | 超出部分 VU 共用最后一个用户，登录不失败但并发分布失真 |

## 8. 与方案文档的分工

| 文档 | 内容 |
|---|---|
| `压测方案.md` | 为什么压、压什么、指标口径、瓶颈清单、单机/双机判定方法论 |
| `README.md`（本文） | 怎么装、怎么跑、怎么读结果、参数速查、FAQ |
