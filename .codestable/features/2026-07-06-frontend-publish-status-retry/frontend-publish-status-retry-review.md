---
doc_type: feature-review
feature: 2026-07-06-frontend-publish-status-retry
status: passed
reviewed: 2026-07-06
round: 1
reviewer: subagent
source: cs-feat-impl
---

# frontend-publish-status-retry 代码审查报告

## 1. 范围与输入

- 来源：cs-feat-impl（4 step 全 done）
- Design: .codestable/features/2026-07-06-frontend-publish-status-retry/frontend-publish-status-retry-design.md（approved）
- Checklist: 同目录（4 step done / 18 check passed）
- 改动文件：types/knowpost.ts、services/knowpostService.ts、hooks/usePublishStatus.ts（新建）、pages/CreatePage.tsx；后端零改动

### 独立审查

- 环节 A（独立 Task agent）：completed。general-purpose subagent（agentId a69496a3cffebc553），对抗式审查
- 环节 B（OCR）：not-available（ocr CLI 未安装，非阻塞）
- reviewer: subagent

## 2. 改动摘要

- types：新增 PublishAcceptedResponse / PublishStatusResponse
- service：修 publish 双 bug（漏 token + 漏 body）+ 加 publishStatus / retryPublish
- hook：usePublishStatus 轮询（2s/30 次 60s）+ phase 四态 + cleanup + attemptId 闭包持有
- CreatePage：接入 hook + 四态 UI + 重试/我的知文出口 + 发布中禁用

## 3. Findings

### blocking
none。

### important

#### I1 publish/retry 失败时 phase 卡 publishing 锁死按钮（已修）
- Evidence: `usePublishStatus.ts` start:93 先 setPhase(publishing)，:94 await publish 抛错无 try/catch → 错误冒泡到 CreatePage catch 只 setError，phase 卡 publishing → 按钮 `disabled={submitting || phase==="publishing"}`（CreatePage.tsx:373）锁死。retry:105 同问题。
- Impact: 真实生产路径——token 过期 401 / 后端 400 / 网关 5xx 在 publish 阶段发生时，CreatePage 双锁死（发布按钮灰 + 重试按钮不可见），用户只能刷新。
- 处置：**已修**。start 加 try/catch 失败复位 phase=idle 并 rethrow；retry 加 try/catch 失败复位 phase=failed（保留重试按钮可点）。顺手采纳 S1（retry 用 attemptId 入参而非返回值，更表意"复用同一 id"）。lint 绿。

#### I2 pollOnce 网络抖动 catch 计入 timeout 配额（follow-up，不阻塞）
- Evidence: `usePublishStatus.ts:65-74` catch 把网络错/ApiError 当一次轮询计 pollCount，30 次连续网络错→timeout。token 过期 401 持续轮询 60s 后显示 timeout 而非登录失效。
- Impact: 60s 内 30 次全失败概率低，但 token 过期是确定路径。design 2.2 节"D2 timeout 有意取舍"留了余地。
- 处置：**不阻塞 merge，记 follow-up**。建议 catch 区分 401/403→直接 failed 不 retryable 提示重新登录；其他网络错用独立 errorCount 上限。acceptance residual-risk 记录。

### nit

- N1 `failedStepText` 第三分支 `step ? "发布失败" : "发布失败"` 死逻辑——两支同值，design 说 null 不显示但实现显示"发布失败"更合理。可简化为 `return "发布失败"`，非阻塞。
- N2 knowpostService 三个新方法末尾悬空逗号+空行格式与文件内 setTop 风格略不一致。lint 不报，非阻塞。
- N3 publishStatus 省略 `method: "GET"` 靠默认值——design 2.1 写了显式 GET，建议补 `method: "GET"` 与 design 字面对齐。非阻塞。

### suggestion

- S1 retry 用 startPolling(attemptId) 而非返回值——**已采纳**（I1 修复时一并改）。
- S2 mountedRef 初值 true + effect 重复置 true 冗余但严格模式正确，建议不动。
- S3 CreatePage token 缺失分支 setSubmitting/setError 冗余（finally 会处理），可简化，非阻塞。

### learning

- L1 attemptId 全程 string 精度防御正确（后端 String.valueOf + 前端 string 类型 + path variable Spring 自动转 long），符合 attention `[[snowflake-id-serialize-as-string]]` 约定。
- L2 apiFetch token 回退逻辑（undefined 回退 localStorage，null 不带 Authorization）——本 feature 三方法都传 string 走显式带 token，正确；未来调用方传 null 会变公开请求 401，值得 attention 记一笔。

### praise

- P1 cleanup 三处清 interval 全落实（start/retry 开头清 + startPolling 内清 + reset 清 + unmount effect 清），retry 重启双清防双轮询。design R2 缓解完全落实。
- P2 publish 双 bug 修复彻底，与 setTop/setVisibility/remove 风格对齐。
- P3 phase 与 attemptStatus 命名分离是好领域建模（前端多 idle/timeout 编排态）。
- P4 四态 UI + retryable 分支正确，S3 不可重试态代码审查通过。

### residual-risk

- RR1 刷新即丢 attemptId（design D3 有意取舍）：刷新后 phase 回 idle，UI 不渲染态块；用户重复点发布→新 idempotentKey→后端状态守卫返"当前状态不可发布"→前端 catch 显示错误。链路安全，QA 验证。
- RR2 getPublishStatus @Transactional 触发 recoverStuckPublishingIfNeeded：正常只读检查，2s 间隔 30 次无写库副作用，design 已评估。
- RR3 后端 git diff 为空：review 在前端仓库，QA 需在后端仓库 `git diff --stat` 确认。

## 4. Test And QA Focus

QA 必须复核：
1. **publish 失败不复锁死（I1 修复后）**：mock publish 401/500 → phase 复位 idle/failed，按钮可再点
2. **S1 成功路径**：完整发布→succeeded→"发布成功 ✅" + `/post/{postId}` 链接可点（postId 非空）
3. **S2 失败可重试**：status 返 failed+retryable → 重试按钮 → Network 看 retry 请求 path attemptId 与 publish 一致 → 重新轮询
4. **S4 超时**：临时缩短 POLL_MAX_TIMES 测 → timeout + 重试/我的知文出口
5. **S8 unmount 清理**：publishing 态导航离开 → console 无 setState 警告
6. **S9 禁用 + 重复点击**：publishing 态发布按钮 disabled；重试中 submitting=true 按钮变"重试中…"
7. **后端 git diff 为空**（RR3）
8. **token 过期中途（I2）**：轮询中 token 过期 → 当前会 60s 后 timeout（非登录失效提示），QA 确认是否接受，记 follow-up

## 5. Verdict

- Status: **passed**
- 无 blocking；I1（死锁）已修，I2 记 follow-up 不阻塞；N1-N3 / S2-S3 非阻塞可后续清理。
- reviewer: subagent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 进入 `cs-feat-qa`
