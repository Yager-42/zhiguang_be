# 部署指南（一键部署 zhiguang_be）

> 目的：`docker compose up -d` 一条命令把「中间件全栈 + 应用」全部跑起来，直接进入压测。
> 配套阅读：压测操作手册 [`README.md`](./README.md)，压测方案 [`压测方案.md`](./压测方案.md)。
> 环境假设：Windows + Docker Desktop（WSL2 后端）或 WSL 内 Docker Engine；Git Bash 或 WSL。

---

## 1. 架构总览

```
docker compose（单命令，12 个服务）
├── app:8080            ← 应用（多阶段 Dockerfile 构建，非 root，健康检查自动就绪）
├── mysql:3306          主存储 + outbox（首启自动灌 db/schema.sql）
├── redis:6379          计数 SDS/位图、Feed 缓存、单飞协调
├── kafka:9092/9094     canal-outbox / counter-events / comment-write / decision
│                        （9092=容器内 app 用，9094=宿主机工具直连）
├── rocketmq-namesrv:9876 + rocketmq-broker:10911   B' 竞价命令
├── cassandra:9042      feed_inbox / author_feed / 正文文本（首启自动灌 init.cql）
├── elasticsearch:9200  搜索索引（app 启动时自动建索引/回填）
├── minio:9000/9001     对象存储（控制台 9001，minioadmin/minioadmin）
└── gorse:8088          推荐（profile 开关，默认不启动）
```

关键事实（已核对源码/配置）：
- 应用容器内通过**服务名**访问中间件（compose 的 `environment` 已覆盖 `application.yml` 的 localhost 默认值），无需改代码。
- `docker-compose.yml` 的 `app` 服务 `depends_on` 全栈 healthy（含 cassandra-init/minio-init 完成），不会出现"应用先起、中间件没就绪"的竞态。
- Kafka 双 listener：容器内 app 连 `kafka:9092`；宿主机工具（`collect_metrics.sh` 等）仍走 `docker exec` 或 `localhost:9094`。

## 2. 前置检查

| 项 | 要求 | 说明 |
|---|---|---|
| Docker Desktop（WSL2 后端）或 WSL 内 Docker Engine | 运行中 | `docker version` 验证；`docker compose version` 需 ≥ 2.17（支持 service_completed_successfully） |
| 内存 | ≥ 8GB（推荐 16GB） | ES 512M + Cassandra 512M + RocketMQ 4G 起 + MySQL/Kafka + app 2G |
| Git Bash / WSL | 有 | 压测脚本（run.sh）依赖 bash |
| Node.js（可选） | ≥ 18 | 仅灌种子数据（`gen_seed_cassandra.mjs`）需要；压测本身不需要 |
| 端口空闲 | 8080/3306/6379/9092/9094/9876/10911/9042/9200/9000 | `netstat -an \| findstr "8080"` 检查 |

## 3. 一键部署

```bash
# 在仓库根目录（zhiguang_be/）
docker compose up -d --build
```

- 首次执行：拉取 8 个中间件镜像 + 构建应用镜像（Maven 下载依赖）→ 约 10~30 分钟，取决于网络。
- 之后执行：秒级（镜像已缓存，`--build` 可省略为 `docker compose up -d`）。
- **依赖顺序自动处理**：compose 按健康检查等待——mysql/redis/kafka/rocketmq/cassandra/es/minio healthy → cassandra-init/minio-init 跑完建表/建桶 → app 启动。

**等全部就绪：**

```bash
docker compose ps
# 期望：12 个服务，mysql/redis/kafka/rocketmq-*/cassandra/elasticsearch/minio/app 全部 healthy
#       cassandra-init / minio-init 显示 Exited (0)（一次性初始化，属正常）
docker compose logs -f app | tail -20   # 应用日志出现 "Started ZhiGuangApplication" 即完成
```

## 4. 验证（冒烟清单）

```bash
# 1) 健康检查
curl http://localhost:8080/actuator/health          # {"status":"UP"}

# 2) 匿名 Feed（permitAll）
curl "http://localhost:8080/api/v1/knowposts/feed?page=1&size=5"   # 200（灌数前 items 为空也正常）

# 3) MySQL 初始化
docker exec -i zhiguang-mysql mysql -uzhiguang -pzhiguang123456 zhiguang -e "SHOW TABLES;" | wc -l   # ~20 张表

# 4) Cassandra 初始化
docker exec -i zhiguang-cassandra cqlsh -e "DESCRIBE TABLES IN zhiguang;"   # post_text/comment_text/feed_inbox/feed_author_feed

# 5) 认证链路（需先灌种子，见第 5 节）
curl -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"identifierType":"PHONE","identifier":"13900000001","password":"Loadtest@123"}'
```

## 5. 进入压测（灌数 → 校验 → 冒烟 → 正式压测）

```bash
cd loadtest
./run.sh seed              # 灌种子：用户/帖子/关注/推广/Cassandra/Redis 预热（docker exec 方式，容器模式同样可用）
./run.sh verify            # 合规校验：violations 全 0

docker compose restart app # 重启应用触发 ES 索引自回填（容器模式 = 原来"重启应用一次"）

# 冒烟
k6 run -e VUS=1 -e HOLD=1m scripts/mixed.js

# 正式压测（单机容器限核模式）
K6_DOCKER=1 ./run.sh baseline
K6_DOCKER=1 ./run.sh mixed
```

说明：种子灌入走 `docker exec`（mysql/cassandra/redis 均映射了端口或直接进容器），容器模式与宿主模式**零差异**；应用重启用 `docker compose restart app`。

## 6. 场景开关

| 变量 | 默认 | 用法 |
|---|---|---|
| `PROMOTION_BPRIME_ENABLED` | false | 竞价 B' 全链路：`PROMOTION_BPRIME_ENABLED=true docker compose up -d` |
| `GORSE_ENABLED` | false | 推荐混排：先 `docker compose --profile recommendation up -d gorse`，再开 GORSE_ENABLED 重启 app |
| `SINGLEFLIGHT_MODE` | distributed | 单飞协调模式（本地默认即可） |

## 7. 宿主机直跑（备选，不用 Docker 跑应用时）

```bash
docker compose up -d                              # 只起中间件（app 服务可加 --scale app=0 跳过）
mvn clean package -DskipTests                     # 需 JDK21 + Maven
java -Xmx2g -XX:+UseG1GC -jar target/zhiguang-1.0-SNAPSHOT.jar
```

注意：宿主直跑时应用连 `localhost`（application.yml 默认值）——Kafka 用 `localhost:9094`（EXTERNAL listener）或用 `docker exec` 工具；其余端口不变。

## 8. 常见问题

| 症状 | 原因 | 解决 |
|---|---|---|
| `docker compose` 命令不存在 | compose v2 插件未启用 | Docker Desktop 设置里启用 Compose V2，或装 docker-compose-plugin |
| 首次 up 卡在 build | Maven 下载依赖慢 | 等待；或配阿里云镜像加速 |
| app 反复重启、日志报 RocketMQ 连接失败 | 等 namesrv/broker healthy 的竞态（罕见） | `docker compose logs app` 确认；`docker compose restart app` 重试 |
| app 日志报 Cassandra 表不存在 | cassandra-init 未完成就启动（compose 版本不支持 service_completed_successfully） | 升级 compose ≥ 2.17；或手动 `docker compose run --rm cassandra-init` 后 `restart app` |
| app 健康检查一直 starting | 某个中间件不可达 | `docker compose logs app` 看具体连接错误 |
| 宿主机直连 Kafka 9092 失败 | 9092 的 advertised 是容器内地址 | 用 `localhost:9094` 或 `docker exec` 进容器操作 |
| ES 索引创建失败（ik_max_word） | 自定义 ES 镜像或 IK 插件下载失败 | Compose 会用 `Dockerfile.elasticsearch` 安装匹配 9.2.1 的 IK；检查网络后执行 `docker compose build --no-cache elasticsearch` |
| 想重置全部数据 | 旧 volume 残留 | `docker compose down -v && docker compose up -d --build`（**删除全部数据**，重灌种子） |
| WSL 内 Docker Engine 下 host.docker.internal 不通 | 老版本 docker | 升级 docker ≥ 20.10；或压测时 BASE_URL 用 WSL 的 eth0 IP |

## 9. 部署 ↔ 压测衔接速查

```bash
docker compose up -d --build                       # 1. 一键部署（等待 app healthy）
curl localhost:8080/actuator/health                # 2. 健康
cd loadtest
./run.sh seed && ./run.sh verify                   # 3. 灌数 + 合规校验
docker compose restart app                         # 4. ES 回填
k6 run -e VUS=1 -e HOLD=1m scripts/mixed.js        # 5. 冒烟
K6_DOCKER=1 ./run.sh baseline                      # 6. 正式压测（单机容器限核）
```
