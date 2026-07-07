---
doc_type: feature-review
feature: 2026-07-06-frontend-notification
status: passed
reviewed: 2026-07-06
round: 1
reviewer: subagent
source: cs-feat-impl
---

# frontend-notification 代码审查报告

## 1. 范围与输入

- 来源：cs-feat-impl（3 step 全 done）
- Design: .codestable/features/2026-07-06-frontend-notification/frontend-notification-design.md（approved）
- Checklist: 同目录（3 step done / 13 check passed）
- 改动文件：types/notification.ts、services/notificationService.ts、pages/NotificationPage.tsx+.module.css、components/layout/Sidebar.tsx+.module.css、App.tsx；**后端 3 文件**（NotificationItemResponse/NotificationPageResponse DTO + NotificationServiceImpl，B1 修复必需）
- 后端零改动前提被 B1 打破（design 遗漏，见 B1）

### 独立审查

- 环节 A（独立 Task agent）：completed。general-purpose subagent（agentId aa7ed1ae202313321），对抗式审查
- 环节 B（OCR）：not-available
- reviewer: subagent

## 2. 改动摘要

- types/service：NotificationItem（id 全 string）+ notificationService（4 方法，双游标 URLSearchParams）
- NotificationPage：列表 + 双游标加载更多 + type+entityType 文案 + aggregateCount 边界 + 跳转映射 + 标记已读解耦 + 空态
- Sidebar：useAuth + 通知导航项 + 内联 BellIcon + 未读徽章 + 事件监听同步（徽章内联，未单独建 NotificationBadge 组件）
- App.tsx：/notifications 路由
- 后端 DTO：NotificationItemResponse id/actorUserId/entityId/secondEntityId Long→String；NotificationPageResponse nextCursorId Long→String；ServiceImpl String.valueOf 构造

## 3. Findings

### blocking

#### B1 id 精度丢失：后端 Long 直传，前端 type 声明 string 是谎言（已修）
- Evidence: 后端 NotificationItemResponse id/actorUserId/entityId/secondEntityId 全是 Long（裸数字 JSON），全仓无 Long→String 序列化配置。DB 通知 id 是 snowflake（如 330965121696403456，>2^53）。前端 type 声明 string 但 apiFetch `as TResponse` 运行时是 number。markRead 拼 `${id}/read` 用 Number.toString() 丢精度 → 后端 findById 返回 null → 400。entityId 跳转也错。design"后端零改动"前提错误——既要 string 精度又要零改不可能。
- Impact: snowflake id 通知点击必失败（400），跳转错，徽章永不减。S4/S7 不成立。
- 处置：**已修**。后端 DTO 4 字段 Long→String + nextCursorId Long→String + ServiceImpl String.valueOf 构造（对齐评论 feature 先例）。mvn compile 通过。design"后端零改动"前提修订（见 §4）。

### important

#### I1 切账号 ref 残留污染（已修）
- Evidence: NotificationPage loadMore 读 nextCursorRef，loadFirst 失败/切账号时 ref 不清 → loadMore 用旧 cursor 拉旧账号数据混入新账号列表。
- 处置：**已修**。loadFirst 开头清 nextCursorCreatedAtRef/nextCursorIdRef=null + setHasMore(false)。

#### I2 已读防重与 design 措辞相反（非阻塞，保留实现）
- Evidence: handleClick `if (!item.isRead)` 防重，design 2.2 说"点击即标记无需防重"。
- 处置:保留实现（更保守省请求），design 措辞后续可更新。非阻塞。

#### I3 loading 共用导致按钮互相阻塞（非阻塞，记 residual）
- Evidence: loadFirst/loadMore/markAllRead 共用 loading。全部已读时加载更多按钮闪烁，加载更多时全部已读按钮灰。
- 处置:UX 小瑕疵，记 residual，后续可拆 listLoading/actionLoading。

#### I4 未登录直访 /notifications 显示空态（已修）
- Evidence: 未登录 loadFirst 静默 return → 显示"暂无通知"误导。
- 处置:**已修**。未登录时 setError("请先登录查看通知")。

### nit

- N1 unreadCount catch 静默吞错 → 可接受（徽章不阻塞），dev 模式可加 warn。非阻塞。
- N2 createdAt ISO 格式假设 → 后端 LocalDateTime Jackson 序列化格式需 QA 浏览器实测（residual）。
- N3 moderation_action/report_processed 无 aggregateCount 前缀 → 这两类恒 1，行为正确。非阻塞。
- N4 like+其它 entityType 文案 → 当前只有 knowpost/comment，OK。residual。

### suggestion

- S1 NotificationBadge 内联进 Sidebar（未单独建组件）→ 功能等价，design 交付物清单/挂载点 M1 grep 需更新。owner 接受内联（Sidebar 唯一使用方）。
- S2 `if (item.entityId)` 对 0 falsy → snowflake 不可能 0，OK。语义 `!= null` 更准，非阻塞。
- S3 事件名 string 字面量无类型约束 → 可抽常量，非阻塞（已 grep 三处拼写一致）。

### learning

- L1 design"后端零改动"前提在涉及 snowflake id 时需核实后端 DTO 字段类型——评论 feature 改了 Long→String，通知 feature 也得改。这是项目级约定（attention 已记 snowflake id string）。
- L2 window.dispatchEvent CustomEvent 跨组件通信在 SPA 内可靠，但事件名无类型约束（S3）。

### praise

- P1 handleClick 标记与跳转解耦正确：markRead 成功后无论 target 是否 null 都先 setItems isRead + dispatchEvent 再判跳转，失败 return 不跳不标记。
- P2 双游标首屏 (null,null) + 加载更多成对传 ref，与后端 mapper `and` 条件匹配。
- P3 Sidebar 监听 effect 有完整 cleanup，徽章 Math.max(0, prev-count) 防负数。
- P4 未登录 isLoggedIn false 时 setUnread(0) + 不渲染通知项，S6 正确。
- P5 aggregateCount 边界 + like+comment 文案 + follow 恒 1 无前缀，全对。

### residual-risk

- RR1 后端 LocalDateTime 序列化格式未浏览器实测（N2）→ QA 看 createdAt 渲染
- RR2 loading 共用 UX 小瑕疵（I3）→ 后续拆状态
- RR3 多 tab 场景 dispatchEvent 只本 tab 生效（design 不做 WebSocket）→ 可接受
- RR4 NotificationBadge 内联（S1）→ design 交付物清单/挂载点 M1 需更新措辞

## 4. Test And QA Focus

QA 必须复核：
1. **id 精度（B1 修复后）**：浏览器 Network 看 list 响应 id 带引号（string）；点通知 markRead path id 末位无舍入；响应 204 非 400
2. **nextCursorId 精度**：通知 >20 触发加载更多，cursorId 末位一致
3. **markRead 成功目标 null（like+comment）**：点 like+comment 通知 → markRead 204 + 标记已读 + 徽章减 + 不跳转
4. **markRead 失败**：断网点通知 → actionError + 保持未读 + 不跳
5. **已读再点**：不发 markRead + 直接跳转
6. **全部已读**：markAllRead 204 + 列表全灰 + 徽章 0
7. **切账号（I1 修复后）**：A 翻第 2 页 → logout+login B → 进通知页看列表纯 B
8. **未登录直访（I4 修复后）**：敲 /notifications 显示"请先登录"
9. **createdAt 渲染（N2）**：浏览器看时间列正常
10. **Sidebar 徽章两时机**：登入停留首页徽章有值 + 点已读回首页徽章减

## 5. Verdict

- Status: **passed**
- B1（id 精度）已修（后端 DTO Long→String + ServiceImpl String.valueOf）；I1（切账号 ref 清）已修；I4（未登录提示）已修。I2/I3/N1-N4/S1-S3 非阻塞。
- reviewer: subagent（环节 A 完成，OCR 不可用跳过环节 B）
- design"后端零改动"前提因 B1 修订：后端 DTO 字段类型改动是 snowflake id string 化必需（对齐评论先例），非范围扩散。
- Next: 进入 `cs-feat-qa`
