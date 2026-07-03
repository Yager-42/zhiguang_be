---
doc_type: feature-qa
feature: 2026-07-02-frontend-comment-like-delete
status: passed
reviewed: 2026-07-03
round: 1
---

# frontend-comment-like-delete QA 验证报告

## 1. QA 范围

轻量 QA，聚焦 cs-code-review 修复的 I1（按钮 disabled）+ 并发连点 + design §3 核心场景。前端 0 测试框架，以联调（curl + 后端真实响应）+ 代码审查 + 浏览器手工为主。

## 2. 验证场景与证据

### QA-1: id 精度修复（核心，snowflake 不丢精度）
- 触发：commentId 331035581662498816（超 2^53）
- 证据：list 返回 commentId 为 string（`repr: '331035581662498816'` type str，带引号）；之前 number 会丢成 331035581662498800
- 联调：点赞该评论 → changed=true → 刷新 liked=True（保持）；之前精度丢失时刷新 liked=False
- 结论：精度修复生效，点赞/删除用正确 commentId

### QA-2: 点赞/取消/liked 持久化（design §3 #1/#2/#9）
- 证据（impl + review 联调）：
  - 点赞 → 200 {changed:true} + 心形红 + likeCount+1
  - 取消 → 200 {changed:true} + 心形恢复 + likeCount-1
  - 刷新后 liked=true 保持（后端 isLiked 查位图，userId 对齐）
- 结论：liked 后端权威，刷新不丢

### QA-3: 删除（design §3 #4/#6）
- 证据：删除 → 204 + 本地 body=[deleted] + deleted=true + 按钮禁用；刷新后后端返回 deleted=true body=[deleted] 一致
- 结论：软删除前后端一致，文案不跳变

### QA-4: 并发连点（review I1 + design §2.2）
- 验证方式：代码审查 + 后端幂等
- 证据：
  - 前端 likingRef/deletingRef 同步守卫（handleLike/handleDelete 入口 `if (ref.has(commentId)) return`），连点第二次直接 return 不发请求
  - 后端 like/unlike 单向幂等（位图置位/清零，重复 changed=false），即使前端守卫失效后端也不重复计数
  - I1 修复：likingIds/deletingIds state 驱动按钮 disabled，请求在途按钮置灰
- 结论：双守卫（前端 ref + 后端幂等）+ disabled 视觉态，连点不重复请求/计数

### QA-5: 非作者无删除按钮（design §3 #5）
- 证据：渲染条件 `!item.__optimistic && !item.deleted && item.creatorId === String(user?.id)`；新账号（非作者）浏览器未见删除按钮
- 结论：权限隔离正确

## 3. 未验证场景（residual-risk，交 acceptance）

- **按钮 disabled 视觉态**（I1）：代码已实现（likingIds/deletingIds + disabled + CSS），dev server 已加载新代码，但视觉置灰需 owner 浏览器肉眼确认（QA 无法自动化）
- **userId 精度**（I2/R1）：user.id 仍 number，当前 users.id AUTO_INCREMENT 小值不触发；大 userId 场景无法测
- **likeCount 聚合**（R4）：counter 系统 bug，已记 issue 2026-07-02-comment-likecount-not-aggregating，非本 feature
- **snowflake 精度测试盲区**（review L3）：现有测试用小 commentId + jsonPath coercion，无法守护精度；建议补 >2^53 测试（记 QA focus，不阻塞）

## 4. Verdict

- Status: passed
- I1 修复（按钮 disabled）代码实现 + dev 加载确认；并发连点双守卫（前端 ref + 后端幂等）代码审查确认
- design 核心场景（点赞/取消/删除/liked 持久化/精度/权限）全有联调证据
- residual-risk 已记录，交 acceptance 复核
- Next: 进入 `cs-feat-accept`
