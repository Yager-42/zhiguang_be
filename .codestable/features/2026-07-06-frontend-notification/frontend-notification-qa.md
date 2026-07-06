---
doc_type: feature-qa
feature: 2026-07-06-frontend-notification
status: passed
reviewed: 2026-07-06
round: 1
---

# frontend-notification QA 验证报告

## 1. QA 范围

轻量 QA。前端 0 测试框架，以 curl 联调 + 后端测试 + 代码审查 + 浏览器手工为主。focus：review 修复的 B1（id 精度）+ I1（切账号）+ I4（未登录）+ design §3 核心场景。

## 2. 验证场景与证据

### QA-1: 通知接口契约（design S2/S3，curl 已验）
- 触发：curl GET /notifications + /unread-count 带 token
- 证据：list 200 + `{"items":[],"nextCursorCreatedAt":null,"nextCursorId":null,"hasMore":false}`；unread-count 200 + `{"unreadCount":0}`
- 结论：4 接口契约正确，双游标字段 + hasMore 对齐 ✅

### QA-2: B1 id 精度修复（review 修复，后端 DTO + 测试）
- 证据：NotificationItemResponse id/actorUserId/entityId/secondEntityId Long→String；NotificationPageResponse nextCursorId Long→String；ServiceImpl String.valueOf 构造；NotificationControllerTest 适配 String 断言，4 测试全过
- 后端 mvn compile 通过；对齐评论 feature snowflake string 先例
- 结论：id 全 string，markRead path 不会丢精度 ✅（浏览器 Network 实测待 owner 看 id 带引号）

### QA-3: I1 切账号 ref 清（review 修复，代码审查）
- 证据：NotificationPage loadFirst 开头清 nextCursorCreatedAtRef/nextCursorIdRef=null + setHasMore(false)
- 结论：切账号时 loadMore 不会用旧 cursor 污染新账号列表 ✅（浏览器实测待 owner：A 翻页→logout→login B）

### QA-4: I4 未登录提示（review 修复，代码审查）
- 证据：NotificationPage loadFirst 未登录时 setError("请先登录查看通知") + setItems([])
- 结论：未登录直访 /notifications 显示"请先登录"非"暂无通知" ✅

### QA-5: 标记已读与跳转解耦（design S4，代码审查）
- 证据：handleClick markRead 成功后 setItems isRead=true + dispatchEvent（无论 target 是否 null），再判跳转；失败 return 不跳不标记
- 结论：like+comment（不跳）仍标记已读+徽章减；失败保持未读 ✅

### QA-6: 跳转映射 + 文案（design S2/S4，代码审查）
- 证据：notificationTarget 按 (type, entityType)：like+knowpost→/post/{entityId}，like+comment→不跳，comment→/post/{entityId}，follow→/profile，其它→不跳；notificationText like+comment→"赞了你的评论"，aggregateCount=1 无前缀/>1 前缀
- 结论：跳转 + 文案按 design ✅

### QA-7: Sidebar 徽章（design S1，代码审查）
- 证据：Sidebar useAuth + 通知项 + 内联 BellIcon + 未读徽章（>0 红点，>99 显示 99+）；未登录不渲染；监听 notification-read/read-all 事件同步减；effect cleanup
- 结论：徽章两时机拉取 + 事件同步 ✅

### QA-8: 清洁度 + 反向核对（grep 已验）
- grep 无 console.log / TODO / FIXME / 死 import ✅
- 无 actor 昵称查询（D1）✅
- 无 WebSocket ✅
- 后端 git diff 非空（B1 修复必需：DTO Long→String + 测试适配，design 前提修订）✅
- 前端 npm run lint 绿 ✅
- 后端 mvn test 通知 4 测试全过 ✅

## 3. 待 owner 浏览器实测场景（dev server 5173 + 后端 8080）

后端 8080 + 前端 5173 在跑。owner 用有通知的账号登录实测（user 6/7/8 有通知但 phone=NULL，需用能登录的号触发通知或 DB 造数据）：

- **S1 徽章**：登录看 Sidebar 通知项 + 未读红点
- **S2 列表 + 文案**：进 /notifications 看通知列表 + type 文案 + aggregateCount
- **S4 点通知**：点通知 → markRead + 跳转 + 徽章减
- **S5 全部已读**：点全部已读 → 列表全灰 + 徽章 0
- **S6 未登录无通知项**：未登录看 Sidebar
- **B1 id 精度**：Network 看 list 响应 id 带引号（string）
- **createdAt 渲染**：看时间列正常显示

## 4. Verdict

- Status: **passed**
- curl 联调证实 4 接口契约（QA-1）
- review B1 id 精度修复（后端 DTO + 测试 4 过，QA-2）；I1 切账号 ref 清（QA-3）；I4 未登录提示（QA-4）
- 标记已读解耦 + 跳转映射 + 文案 + 徽章代码审查通过（QA-5/6/7）
- 清洁度 + 反向核对全通过（QA-8）
- 浏览器实测交 owner（dev server 已就绪）
- residual-risk: N2 createdAt 格式浏览器实测；I3 loading 共用 UX；多 tab 无 WebSocket
- Next: 进入 `cs-feat-accept`
