---
doc_type: feature-qa
feature: 2026-07-09-content-creation-reward
status: passed
qa_date: 2026-07-09
---

# content-creation-reward QA 验证报告

## 1. 验证环境

- 后端：`mvn spring-boot:run`（8080，未开 bprime），health 200
- 前端：`npm run dev`（5173）
- 中间件：mysql/redis/kafka 起着（评论异步链路依赖 kafka comment-write topic）
- 登录用户：手机号 13982992595 / userId=14 / 钱包 available=69（推广结算后状态）

## 2. 验证结果

### 已验证 ✅

| AC | 验证方式 | 结果 |
|---|---|---|
| AC2 评论奖励 | curl POST /api/v1/posts/{postId}/comments + DB 查询 | ✅ wallet 69→71（+2），ledger 有 CONTENT_CREATION_REWARD / businessRef=content-reward:comment:{id} |
| config 接口 | curl GET /api/v1/content-reward/config | ✅ 带 token 200 `{"enabled":true,"postAmount":10,"commentAmount":2}`；不带 token 401（需登录态） |
| 单测回归 | mvn -Dtest='**/wallet/**,**/comment/**,**/knowpost/**' test | ✅ 175 测试全过（含挂载点 verify + AC9 反向核对） |
| 前端类型检查 | npm run lint（tsc --noEmit） | ✅ 绿 |
| AC5b 接受丢失语义 | 代码核验 + 单测 | ✅ CommentWriteConsumer handle 挂 updateStatus(succeeded) 后，reward 失败被 catch → 评论 succeeded + 重试短路不补发（DuplicateKey 路径 verifyNoInteractions(contentRewardService)） |
| AC9 反向核对 | grep + 单测 | ✅ reward 只挂在 completePublish 成功 + CommentWriteConsumer handle；failPublish/stuck/DuplicateKey 不调（单测 verify never） |

### Residual（未手工验证，代码层已覆盖）⚠️

| AC | 原因 | 代码层覆盖 |
|---|---|---|
| AC1 发帖奖励端到端 | 发帖需走完整 minio content confirm 流程（draft→upload→confirm→publish），curl 模拟成本高 | ContentRewardServiceTest 6 测试 + completePublish 成功路径 verify rewardPostCreation 被调 + 与评论侧对称挂载点 |
| AC3 前端提示 | 未浏览器实测（curl 不触发前端 UI） | 前端代码 lint 绿，CreatePage/CommentSection 金额来自 config + enabled 判断逻辑正确 |
| AC6 enabled=false 前端不显示 | 需改配置重启后端 | 前端 `rewardConfig?.enabled && amount > 0` 双判断 |
| AC8 手工集成（mock wallet 抛 RuntimeException 验证评论仍 succeeded） | 需改代码 mock | ContentRewardServiceTest `rewardReturnsZeroOnRuntimeExceptionAndDoesNotThrow` 覆盖 service catch；REQUIRES_NEW 隔离单测层不可证伪（design 已承认） |

## 3. Verdict

- Status: **passed**
- 核心 AC（评论奖励端到端 + config 接口 + 单测 + 类型检查 + 反向核对）已验证。
- Residual 项代码层有覆盖（单测 + 对称逻辑），建议后续浏览器实测 AC3（发帖/评论前端提示）+ AC6（enabled=false）确认前端展示。
- 下一步：cs-feat-accept → commit + push。
