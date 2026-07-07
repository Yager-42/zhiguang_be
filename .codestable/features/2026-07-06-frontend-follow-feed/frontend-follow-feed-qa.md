---
doc_type: feature-qa
feature: 2026-07-06-frontend-follow-feed
status: passed
reviewed: 2026-07-06
round: 1
---

# frontend-follow-feed QA 验证报告

## 1. QA 范围

轻量 QA。前端 0 测试框架，以 curl 联调 + 代码审查 + 浏览器手工为主。focus：review 修复的 I1（跨账号 stale）+ design §3 核心场景 + state 隔离。

## 2. 验证场景与证据

### QA-1: follow feed 接口契约（design S1，curl 已验）
- 触发：curl GET /knowposts/feed/follow -H "Authorization: Bearer <token>"
- 证据：返回 200 + `{"items":[],"page":1,"size":20,"hasMore":false,"nextCursor":null}`（user14 无关注人，空态）
- 结论：接口契约正确，nextCursor/hasMore 字段对齐 ✅

### QA-2: I1 跨账号 stale 修复（review 修复，代码审查）
- 触发：登出（accessToken 变 null）
- 证据：HomePage.tsx 登出重置 effect——accessToken falsy 时重置 followHasFetchedRef=false + followItems=[] + cursor=null + hasMore=false + tab=recommend
- 结论：登出再换号登录不残留上个用户数据 ✅（代码审查；浏览器实测待 owner：A 登录切关注→登出→B 登录切关注）

### QA-3: state 隔离（design S4，代码审查）
- 证据：关注 tab state（followItems/followLoading/followHasMore/refs）全在 HomePage 顶层 useState/useRef；三目渲染 `{tab==='follow' && isLoggedIn ? 关注 : 推荐}`；hasFetched ref 防切回重 fetch
- 结论：切 tab 不 unmount state，切回数据仍在 ✅（代码审查；浏览器实测待 owner）

### QA-4: 未登录隐藏关注 tab（design S6，代码审查）
- 证据：HomePage.tsx 关注 tab 按钮 `isLoggedIn ? ... : null`；三目条件 `tab==='follow' && isLoggedIn` 双保险
- 结论：未登录只显示推荐 tab ✅（代码审查；浏览器实测待 owner）

### QA-5: 加载更多按钮 + cursor 回传（design S2/S7，代码审查）
- 证据：hasMore=true 且非 loading 时显示按钮（disabled={followLoading}）；loadMoreFollow 用 followNextCursorRef.current；URLSearchParams 构建 query（: 不编码）
- 结论：按钮模式 + cursor 回传正确 ✅（代码审查；浏览器实测待 owner：点加载更多看 Network cursor 值）

### QA-6: 清洁度 + 反向核对（grep 已验）
- grep 无 console.log / TODO / FIXME / 死 import ✅
- 无 IntersectionObserver / scroll 监听（不做无限滚动，design D2）✅
- 无新路由（只改 HomePage + service + types + css）✅
- 后端 git diff 为空 ✅
- npm run lint 绿 ✅

## 3. 待 owner 浏览器实测场景（dev server 5173 已热更新）

后端 8080 + 前端 5173 在跑。owner 用 13982992595 登录后实测：

- **S1 关注 tab 显示**：登录 → 切关注 tab → 看到关注作者帖子（或空态引导，因 user14 无关注人，预期空态）
- **S3 空态引导**：新号无关注 → 切关注 tab → "还没有关注的内容，去发现更多" → 点链接 SPA 跳 /search
- **S4 tab 不残留**：关注 tab 加载 → 切推荐 → 切回关注 → 数据仍在
- **S6 未登录无关注 tab**：未登录访问首页 → 只看到"推荐"按钮
- **S2/S7 加载更多**：需先关注一些人且他们有帖子才有 hasMore，难自然触发，可信任代码审查
- **I1 跨账号**：A 登录切关注 → 登出 → B 登录切关注 → 应显示 B 的 feed

## 4. Verdict

- Status: **passed**
- curl 联调证实 follow feed 接口契约（QA-1）
- review I1 跨账号 stale 修复代码审查通过（QA-2）
- state 隔离 / 未登录隐藏 / 加载更多 / cursor 回传代码审查通过（QA-3/4/5）
- 清洁度 + 反向核对全通过（QA-6）
- 浏览器实测交 owner（dev server 已就绪，场景 S1/S3/S4/S6/I1）
- residual-risk: RR1 首屏失败无法重试（design 范围外）；RR3 后端 while 循环耗时上限未知
- Next: 进入 `cs-feat-accept`
