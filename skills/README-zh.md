# Skills 索引

`skills/` 是 `zhiguang_be` 的业务知识层，只覆盖 `src/main/java/com/tongji/*` 主线；`.trellis/spec/` 继续只承载工程规范。

`CONTEXT.md` 的有效术语已经迁到 `zhiguang-business-dictionary`，后续不再把根目录 `CONTEXT.md` 作为业务真相源。

## 阅读顺序

1. `zhiguang-repo-map`
   - 先路由，先判断当前需求属于哪个业务域、哪个入口、哪批文件。
   - 这里同时提供 controller 入口、skill ownership 和 generated references 的定位入口。
2. `zhiguang-business-dictionary`
   - 先统一名词、对象层级、真相源，再进入具体 skill。
3. 进入具体业务域 skill
   - `zhiguang-auth-user`：`auth`、`user`、`profile`
   - `zhiguang-content-domain`：`knowpost`、`storage`、`search`、`recommendation`、`cache`
   - `zhiguang-social-domain`：`relation`、`comment`、`counter`、`notification`
   - `zhiguang-platform-domain`：`promotion`、`wallet`、`moderation`、`reconciliation`
   - `zhiguang-common-runtime`：`common`、`config`、`id`、`llm`
4. 跨两个以上域时切 `zhiguang-change-playbook`
5. 现象不清、先排障时切 `zhiguang-debug-playbook`

## 统一读法

先问三个问题：

1. 这次改的是主记录、运行态、派生结果，还是外部契约？
2. 这个对象的真相源在哪：MySQL、Redis、Kafka、Cassandra、MinIO、Elasticsearch，还是 Java 常量 / 配置？
3. 当前任务是在改业务语义、流程规则、接口契约，还是只在排障？

这三个问题没有答清前，不直接改代码。

## 快速路由

- 看到 `AuthController`、`ProfileController`、JWT、验证码、刷新 token、当前用户：先看 `zhiguang-auth-user`
- 看到 `KnowPostController`、draft / publish / visibility / feed、对象存储、搜索、推荐：先看 `zhiguang-content-domain`
- 看到 follow / unfollow、comment、like、fav、counter、notification：先看 `zhiguang-social-domain`
- 看到 promotion、auction window、wallet、escrow、moderation、reconciliation：先看 `zhiguang-platform-domain`
- 看到 `BusinessException`、`ErrorCode`、`GlobalExceptionHandler`、`IdService`、Spring 配置、共享 infra：先看 `zhiguang-common-runtime`
- 同时影响两个以上域：切 `zhiguang-change-playbook`
- 已经出现异常、重复消费、状态不一致、补偿、恢复问题：切 `zhiguang-debug-playbook`

## 当前 9 个 Skills

| Skill | 负责范围 | 对应包 |
| --- | --- | --- |
| `zhiguang-repo-map` | 仓库路由与入口定位 | 全仓库主线 |
| `zhiguang-business-dictionary` | 跨域术语、对象层级、真相源 | 全仓库主线 |
| `zhiguang-auth-user` | 身份、认证、用户资料 | `auth` `user` `profile` |
| `zhiguang-content-domain` | 内容发布、详情、feed、搜索、推荐、内容存储 | `knowpost` `storage` `search` `recommendation` `cache` |
| `zhiguang-social-domain` | 关注、评论、点赞收藏、计数、通知 | `relation` `comment` `counter` `notification` |
| `zhiguang-platform-domain` | 商业化、钱包、治理、修复任务 | `promotion` `wallet` `moderation` `reconciliation` |
| `zhiguang-common-runtime` | 共享异常、ID、配置与基础设施契约 | `common` `config` `id` `llm` |
| `zhiguang-change-playbook` | 跨域改动检查清单 | 全仓库主线 |
| `zhiguang-debug-playbook` | 排障入口与故障分流 | 全仓库主线 |

## 更新规则

- 改 controller / DTO / 状态枚举 / 关键术语时，先更新对应 skill，再改代码。
- 跨域名词先落 `zhiguang-business-dictionary`，再落具体业务 skill。
- 下列 generated references 属于自动生成资产：`generated-api-index.md`、`generated-skill-routing-map.md`、`generated-config-index.md`、`generated-enum-index.md`。
- generated references 只记录源码/配置可直接提取的事实，不承载人工总结的业务语义；人工语义继续写在普通 `references/` 文档。
- 改动命中对应事实源后，执行 `python scripts/skills/refresh_generated_knowledge.py` 刷新自动生成资产。
- 长说明放 `references/`，`SKILL.md` 只保留路由、入口、硬约束。
- 每个 skill 内索引与条目后续采用统一字段：`category`、`keywords`、`date`、`ref`、`confidence`、`conflict-marker`、`conflict-note`。
- `references/` 文档类型前缀统一为：`RCP-`、`TIP-`、`DCS-`、`AST-`、`REF-`、`DOC-`。
- 词典条目后续采用统一 schema：`id`、`canonical`、`aliases`、`definition`、`relationships`、`keywords`、`tier`、`status`、`source`。
- 知识页遵守 append-only：优先新增条目，不整页覆写；长内容转 `references/`，索引页只保留摘要和 `ref`。

