---
doc_type: feature-acceptance
feature: 2026-07-06-frontend-notification
status: passed
accepted: 2026-07-06
round: 1
---

# 前端通知模块 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-06
> 关联方案：.codestable/features/2026-07-06-frontend-notification/frontend-notification-design.md

## 1. 接口契约核对

对照 design §2.1 名词层：

- [x] notificationService 4 方法（list 双游标 / unreadCount / markRead / markAllRead）→ notificationService.ts 一致
- [x] NotificationItem id/actorUserId/entityId/secondEntityId 全 string（snowflake 精度防御）→ types/notification.ts 一致
- [x] 含 windowStart/windowEnd（对齐后端契约，当前不渲染）→ 一致
- [x] 双游标 cursorCreatedAt + cursorId 成对传（URLSearchParams）→ 一致
- [x] 流程图（design §2.2 mermaid）节点在代码均有落点

**B1 修复致后端 DTO 改动**（design"后端零改动"前提修订）：
- [x] NotificationItemResponse 4 Long 字段→String + NotificationPageResponse nextCursorId Long→String + ServiceImpl String.valueOf 构造（对齐评论 feature snowflake string 先例）
- [x] NotificationControllerTest 适配 String 断言，4 测试全过

无其它偏差。

## 2. 行为与决策核对

对照 design §1 + §2.2：

- [x] D1 不显示 actor 名字（只 type+entityType 文案 + aggregateCount）→ notificationText 无 actor 查询
- [x] D2 Sidebar 通知项 + 未读徽章 → Sidebar 内联 BellIcon + badge
- [x] D3 点单条 markRead + 全部已读按钮 → handleClick + handleMarkAllRead
- [x] 跳转按 (type, entityType) 二元组 → notificationTarget
- [x] 标记已读与跳转解耦 → markRead 成功后 isRead+徽章减（无论跳否）
- [x] aggregateCount=1 无前缀，>1 前缀"{n} 人" → notificationText
- [x] like+comment 文案"赞了你的评论" → notificationText
- [x] 徽章两时机拉取（Sidebar 挂载已登录 + 事件监听同步）→ Sidebar useEffect + window event
- [x] 未登录隐藏通知项 → isLoggedIn 条件渲染
- [x] id string 精度 → 后端 DTO String + 前端类型 string

**明确不做反向核对**（grep）：
- [x] 无 actor 昵称查询
- [x] 无 WebSocket
- [x] 后端 git diff 非空（B1 修复必需，design 前提修订，非范围扩散）

**挂载点反向核对**（design §2.3）：
- [x] M1 Sidebar 通知项 + 徽章（内联，未单独建 NotificationBadge.tsx——实现自决，功能等价，design 交付物清单需更新措辞）
- [x] M2 notificationService 4 方法 → grep 确认
- [x] M3 NotificationPage → grep 确认
- [x] M4 /notifications 路由 → App.tsx 确认
- [x] 反向 grep：引用都在清单内
- [x] 拔除沙盘：删 service+页面+Sidebar 通知项+路由 → 功能消失

## 3. 验收场景核对

对照 design §3（8 场景）：

- [x] **S1 徽章**：Sidebar 通知项 + 未读红点（代码审查；浏览器待 owner）
- [x] **S2 列表 + 文案**：curl list 200；文案 type+entityType + aggregateCount 边界（代码审查；浏览器待 owner）
- [x] **S3 双游标加载更多**：成对传 cursorCreatedAt+cursorId（代码审查；浏览器 Network 待 owner）
- [x] **S4 点通知跳转 + 标记已读**：markRead 成功标记+徽章减（无论跳否），跳转按 (type, entityType)，失败不跳+显示错误（代码审查；浏览器待 owner）
- [x] **S5 全部已读**：markAllRead + 列表全 isRead + 徽章 0（代码审查；浏览器待 owner）
- [x] **S6 未登录无通知项**：Sidebar isLoggedIn 条件（代码审查；浏览器待 owner）
- [x] **S7 id string**：后端 DTO String + 前端类型 string（B1 修复；浏览器 Network 待 owner 看引号）
- [x] **S8 空态**：items=[] 显示"暂无通知"（代码审查；浏览器待 owner）

**review 修复**：B1（id 精度，后端 DTO）+ I1（切账号 ref 清）+ I4（未登录提示）全修，后端测试 4 过。

## 4. 术语一致性

- notificationService / NotificationItem / NotificationPage / NotificationBadge（内联）/ notification-read 事件全仓一致 ✓
- 防冲突：grep 无重名 ✓

## 5. 领域影响盘点

- [x] 新名词：无（通知术语后端已有）
- [x] 结构性选择：无新模块（Sidebar 内联徽章，design 2.5 评估不重构）
- [x] 流程级约束：双游标成对传 + 标记已读与跳转解耦

无领域维度变更需本轮 cs-domain。

## 6. requirement delta 回写

design frontmatter `requirement:` 空（纯前端补功能 + 后端 DTO 精度修复）。保持现状不 backfill。

## 7. roadmap 回写

design frontmatter 无 roadmap/roadmap_item → 非 roadmap 起头，跳过。

## 8. attention.md 候选盘点

- [x] 候选1：snowflake id string 化是项目级约定（评论/通知都改了 Long→String），attention 已有间接记录（compound counter-likecount-read-sds-not-mysql 的 [[snowflake-id-serialize-as-string]]）——可考虑补 attention 明确"后端 DTO Long id 字段统一 String 序列化"
- [x] 候选2：通知跳转映射基于 (type, entityType) 二元组（非单 type）——可 cs-keep 沉淀

不擅自写入，落不落由 owner 退出后定。

## 9. 遗留

- 浏览器实测（S1/S2/S4/S5/S6/S7/S8）→ owner 终审
- I2 已读防重与 design 措辞相反（保留实现，design 措辞可更新）
- I3 loading 共用 UX 小瑕疵 → 后续拆状态
- N2 createdAt 格式浏览器实测
- NotificationBadge 内联（design 交付物清单/挂载点 M1 措辞更新）
- actor 昵称显示（D1 不做）/ WebSocket（明确不做）/ like+comment 不跳（D1 不反查）→ 后续

## 10. 最终审计

- 聚合命令复验：前端 npm run lint ✓；后端 mvn test 通知 4 过 ✓；curl list/unread-count 200 ✓
- 交付物落盘：前端 6 文件（types+service+page+css+sidebar+App）+ 后端 3 文件（2 DTO+ServiceImpl+测试）+ 7 spec 文件 ✓
- diff 清洁度：无 console/TODO/死 import ✓
- 知识沉淀出口分流：snowflake id string → attention 候选；通知跳转 (type,entityType) → cs-keep 候选
- 覆盖率诚实标记：curl+后端测试+代码审查 re-verified；浏览器四态 trust-owner-实测
- 无未处理缺口

## Verdict

- Status: **passed**
- 9 节核对完成，所有 checks passed
- residual-risk：浏览器四态待 owner 终审；I3 loading UX；N2 createdAt 格式
- attention 候选2条 owner 待定（snowflake id string / 跳转二元组）
