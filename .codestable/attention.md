# Attention

本文件是 CodeStable 技能启动必读的项目注意事项入口。所有 CodeStable 子技能开始工作前必须读取它。

## 报告语言

CodeStable 所有落盘产出的正文用**中文**：plan / design、plan review / design-review、code review、QA、验收、issue（report / analysis / fix-note）、refactor、roadmap、goal、沉淀（compound）等所有人读报告都用中文表达。机器状态（YAML / JSON / `state.yaml` / frontmatter 字段）保持机读格式不翻译。如需改默认语言，改这一节。

## 项目碎片知识

<!-- cs-note managed: 用 cs-note 维护，新条目按下面分节追加 -->

### 编译与构建

- 前端 `zhiguang_fe/`：`npm run lint`（= `tsc --noEmit`）是类型检查主命令；`npm run dev` 起 vite（5173）；`npm run build` = `tsc && vite build`。前端 0 测试文件，无单测框架。

### 运行与本地起服务

- 前后端联调：后端 `mvn spring-boot:run`（8080）+ 前端 `npm run dev`（5173），vite proxy `/api` → 8080。
- 后端依赖 6 容器：mysql/redis/kafka/elasticsearch/minio/cassandra（docker-compose up）。RocketMQ/Canal/Gorse 默认 enabled=false 不需要。
- 前端 LoginPage 支持验证码登录（默认 tab）+ 密码登录（密码 tab 支持手机号/邮箱）。验证码不真发短信，`LoggingCodeSender` 打日志 + 存 Redis，key 格式 `auth:code:{scene}:{identifier}`，hash 字段 `code`。脚本可 `docker exec zhiguang-redis redis-cli HGET "auth:code:LOGIN:{phone}" code` 抠码。

### 测试

- 前端 0 测试文件，QA 以手工浏览器 + curl 联调为主。
- 后端 110 个测试文件，MockMvc standalone 风格（mock 依赖、不起真实容器），不验证真实 HTTP 连通/鉴权/DB。

### 命令与脚本陷阱

- **CodeStable 工具需 Python 3.10+**：`.codestable/tools/validate-yaml.py`、`codestable-worktree-gate.py` 等用了 `dict | None` 语法，本环境 `python3` 是 3.9.6 跑不了。替代：yaml 校验用 node js-yaml 或纯文本核对；worktree gate 跳过（本地可逆 feature 无需 worktree 隔离）。详见 `.codestable/compound/codestable-python-version-req.md`。
- MySQL volume 若是旧数据，`schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 不会更新已存在的旧表，会缺列（如 `know_posts.publish_attempt_id`）。重新部署需手动 ALTER 或清 volume。

### 路径与目录约定

### 数据与约定

- **counter likeCount 读 SDS 不读 MySQL**：点赞聚合计数走 Kafka→SDS（`cnt:v1:{etype}:{eid}`），各实体 likeCount 读取必须用 `counterService.getCounts` 读 SDS，不能读 MySQL 的 `like_count`（无代码更新，永远 0）。liked 读位图（`isLiked`）。eid 用 `String.valueOf`。详见 `.codestable/compound/counter-likecount-read-sds-not-mysql.md`。

### 环境变量与凭证

### 其他
