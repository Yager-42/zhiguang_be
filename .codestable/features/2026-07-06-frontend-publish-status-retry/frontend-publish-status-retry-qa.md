---
doc_type: feature-qa
feature: 2026-07-06-frontend-publish-status-retry
status: passed
reviewed: 2026-07-06
round: 1
---

# frontend-publish-status-retry QA 验证报告

## 1. QA 范围

轻量 QA。前端 0 测试框架，以 curl 联调（后端真实响应）+ 代码审查 + 浏览器手工为主。focus：review 修复的 I1（死锁）+ design §3 核心场景 + cleanup。

## 2. 验证场景与证据

### QA-1: publish 双 bug 修复 + 接口契约（design S6/S7，curl 已验）
- 触发：curl POST /knowposts/{id}/publish 带 Bearer token + idempotentKey
- 证据：返回 202 + `{"publishAttemptId":"332401761111379968"}`（不再 401/400）
- 同 idempotentKey 再 publish → 同 attemptId `332401761111379968`（S7 去重，不重复触发发布）
- 结论：publish 双 bug（漏 token + 漏 body）修复 ✓

### QA-2: publishStatus / retryPublish 接口契约（curl 已验）
- publishStatus GET → 200 `{"publishAttemptId":"...","attemptStatus":"failed","postStatus":"publish_failed","failedStep":"critical_publish","retryable":true}`（与 design 1.4 枚举完全一致）
- retryPublish POST → 202 + **同一 attemptId** `332401761111379968`（B1 事实坐实：retry 复用同一 id 原地重启）
- 结论：三接口契约正确 ✓

### QA-3: I1 死锁修复（review 修复，代码审查 + 逻辑验证）
- 触发：publish/retry 抛错（401/500/网络断）
- 证据：`usePublishStatus.ts` start:94-103 / retry:108-119 加 try/catch，失败复位 phase（start→idle，retry→failed 保留重试按钮可点）并 rethrow
- 结论：publish 失败不再卡 publishing 锁死按钮 ✓（代码审查确认；浏览器实测待 owner 用 Network throttle Offline 触发）

### QA-4: S3 失败不可重试（代码审查，design 明确后端不可达此态）
- 证据：`CreatePage.tsx:383` `publishStatus.retryable ?` 守卫，retryable===false 不渲染重试按钮
- 后端 isRetryable = failed && publish_failed，所有 fail 路径成对设，retryable 恒 true（review 核验）
- 结论：防御性 UI 分支存在 ✓（以代码审查为准）

### QA-5: unmount cleanup（design S8，代码审查）
- 证据：`usePublishStatus.ts` mountedRef + useEffect return clearTimer（:124-129）+ start/retry/reset/unmount 四处清 interval
- 结论：无泄漏 ✓（代码审查；浏览器实测待 owner：publishing 态导航离开看 console）

### QA-6: 清洁度 + 反向核对（grep 已验）
- grep 无 console.log / TODO / FIXME / 死 import ✓
- 无 localStorage 存 publishAttemptId（不做刷新恢复，design D3）✓
- 无 navigate 到详情（用 `<a href>`，design D2 停留）✓
- 后端 git diff 为空 ✓
- npm run lint 绿 ✓

## 3. 待 owner 浏览器实测场景（dev server 5173 已热更新）

后端 8080 + 前端 5173 在跑。owner 用 13982992595 验证码登录后实测：

- **S1 成功路径**：发布一篇 → "发布中…" → 轮询到 succeeded → "发布成功 ✅" + `/post/{postId}` 链接可点
- **S2 失败可重试**：若发布失败（如正文空触发 critical_publish）→ 显示 failedStep + 重试按钮 → 点重试 → Network 看 retry path 的 attemptId 与 publish 一致 → 重新轮询
- **S4 超时**：难自然触发（后端通常 <60s），可信任 design 取舍
- **S5 不假成功**：发布后先"发布中…"，不再立即"发布成功 ✅"
- **S9 禁用**：publishing 态发布按钮 disabled

## 4. Verdict

- Status: **passed**
- curl 联调证实三接口契约 + publish 双 bug 修复 + retry 同 attemptId + 去重（QA-1/2）
- review I1 死锁修复代码审查通过（QA-3）
- S3/S8 代码审查通过（QA-4/5）
- 清洁度 + 反向核对全通过（QA-6）
- 浏览器四态实测交 owner（dev server 已就绪，场景 S1/S2/S5/S9）
- residual-risk: I2（token 过期中途轮询 60s 后 timeout 而非登录失效）记 follow-up
- Next: 进入 `cs-feat-accept`
