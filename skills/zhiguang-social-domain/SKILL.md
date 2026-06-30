---
name: zhiguang-social-domain
description: zhiguang 社交互动域 Skill。用于处理关注关系、评论、点赞收藏、计数与通知；当需求命中 `RelationController`、`CommentController`、`ActionController`、`CounterController`、`NotificationController`，或涉及 follow / comment / like / fav / unread-count 时使用。
---

# zhiguang-social-domain

## 使用顺序

- 先看 `references/interaction-flows.md`，确认关系、评论、计数和通知的边界。
- 再定位到具体 controller、consumer、mapper 或 manager。
- 如果改动同时影响内容发布、推荐或平台治理，切 `zhiguang-change-playbook`。

## 必守约束

- 先分清主记录、异步事件、计数聚合和通知侧效果。
- 评论写链路、关系 outbox 链路、计数重建逻辑不要混成单层同步写。
- 通知是副作用结果，不是互动主记录。

## 参考资料

- `references/interaction-flows.md`
