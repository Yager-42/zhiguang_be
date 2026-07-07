---
doc_type: feature-acceptance
feature: 2026-07-02-frontend-comment-like-delete
status: passed
accepted: 2026-07-03
round: 1
---

# 前端评论点赞 + 删除 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-03
> 关联方案 doc：.codestable/features/2026-07-02-frontend-comment-like-delete/frontend-comment-like-delete-design.md

## 1. 接口契约核对

对照 design §2.1 名词层，以最终工作区为准：

- [x] commentService.like/unlike/delete 接收 string commentId → commentService.ts:31/37/43 一致
- [x] CommentItemResponse：commentId/postId/rootId/parentId/creatorId 是 String、liked 是 boolean → DTO 一致
- [x] CommentSubmitResponse.pendingCommentId 是 String → 一致
- [x] id 序列化契约（design §1.2 修订）：snowflake long 全 string，对齐 KnowPostDetailResponse.id 模式 → 后端 DTO + 前端类型一致
- [x] 流程图（design §2.2 mermaid）节点在代码均有落点：点赞/取消/删除/liked 翻转

无偏差。

## 2. 行为与决策核对

对照 design §1 + §2.2：

- [x] 点赞按钮：changed=true 翻转 liked + ±1，changed=false 不动 → handleLike 实现
- [x] 删除按钮：仅 creatorId===user.id 显示 → 渲染条件 `item.creatorId === String(user?.id)`
- [x] 删除后本地 body=[deleted] + deleted=true + 按钮禁用，不移除 → handleDelete 实现
- [x] liked 后端权威：pageComments 用 isLiked 填充 → CommentServiceImpl.item 实现
- [x] replies 恒 false：pageReplies 传 0L + currentUserId>0 短路 → 实现一致
- [x] 按钮 disabled（I1 修复）：likingIds/deletingIds state 驱动 → review 已修
- [x] 明确不做（反向核对）：无 /replies 调用（grep 确认）、replies liked 恒 false

**挂载点反向核对（design §2.3）**：
- [x] M1 CourseDetailPage `<CommentSection>` 引用 → grep 确认仅 2 处（import+渲染），无清单外引用
- [x] M2 CommentSection 组件 + commentService.like/unlike/delete + CommentLikeResponse → 均存在
- [x] 反向 grep：所有引用落在清单内
- [x] 拔除沙盘推演：删引用+组件+service+types → 功能消失无残留

## 3. 验收场景核对

对照 design §3（10 场景），证据来自 qa.md + 联调：

- [x] S1 点赞成功：200 {changed:true} + likeCount+1 + 心形红 → 联调证实
- [x] S2 取消点赞：200 {changed:true} + likeCount-1 → 联调证实
- [x] S3 重复点赞：changed:false 不动 → 代码 if(resp.changed) 守卫
- [x] S4 作者删除：204 + [deleted] + 按钮禁用 → 联调证实
- [x] S5 非作者无删除：渲染条件 creatorId===user.id → 浏览器确认（新账号无删除按钮）
- [x] S6 删除后刷新：deleted=true body=[deleted] 一致 → 联调证实
- [x] S7 未登录：!accessToken 引导态 → 代码分支
- [x] S8 无 /replies：grep 确认
- [x] S9 liked 初始正确：赞后刷新 liked=true → 联调证实（精度修复后保持）
- [x] S10 未赞 liked=false：联调证实

**功能性前端浏览器验证**：owner 已浏览器实测——点赞心形红+刷新保持、删除变[deleted]、非作者无删除按钮。I1 按钮 disabled 视觉态代码已实现+dev加载，owner 确认效果。

**review/QA residual risk 处理**：
- [x] R1 userId 未 string 化 → 记遗留（后续 feature）
- [x] R2 前端 0 测试 → 项目现状
- [x] R4 likeCount 不聚合 → counter 系统 bug，已记 issue

## 4. 术语一致性

- CommentItem/liked/CommentLikeResponse：代码 18 处命名全一致 ✓
- 防冲突：无与既有 types 重名 ✓

## 5. 领域影响盘点

- [x] 新名词：无（评论术语后端已有）
- [x] 结构性选择：**snowflake id 全 string 序列化**是对齐 KnowPostDetailResponse 的既有模式，非新决策；但本次把评论 DTO 纳入该模式，是 id 序列化统一的一步。建议后续 userId 也 string 化时走 cs-domain 记 ADR（id 序列化约定）
- [x] 流程级约束：replies liked 恒 false 是已知 gap（非稳定约束，后续 feature 补）

无领域维度变更需本轮 cs-domain。

## 6. requirement delta 回写

design frontmatter `requirement: comment-system`，`.codestable/requirements/` 空（owner 既有选择不迁文档，openspec 作权威）。与上 feature 一致，保持现状不 backfill，避免与 openspec 重叠。

## 7. roadmap 回写

design frontmatter 无 roadmap/roadmap_item → 非 roadmap 起头，跳过。

## 8. attention.md 候选盘点

- [x] 候选1：snowflake id 必须用 string 序列化（防 JS 精度丢失），项目里 KnowPostDetailResponse 已用，评论 DTO 本次对齐——这是**每个涉及 id 的前端 feature 都会撞**的约定，建议加 attention.md
- [x] 候选2：mvn test 与 spring-boot:run 同时运行会偶发 class 锁 BUILD FAILURE，重跑即过——环境坑，建议加 attention.md

不擅自写入，落不落由 owner 在退出后定。

## 9. 遗留

- R1 userId 未 string 化（id 全 string 契约未闭环）→ 后续 feature
- R4 likeCount 不聚合（counter 系统 bug）→ issue 2026-07-02-comment-likecount-not-aggregating
- 评论回复楼中楼 → 后续 feature
- snowflake 精度测试盲区（review L3）→ 建议补 >2^53 commentId 测试
- replies liked 恒 false → 楼中楼 feature 时补

## 10. 最终审计

- 聚合命令复验：前端 npm run lint ✓（re-verified）；后端 mvn test 51过 ✓（re-verified，偶发 class 锁重跑通过）
- 交付物落盘：后端 9 文件 + 前端 4 文件 + issue 1 文件 ✓
- diff 清洁度：无 console/TODO/System.out ✓
- 知识沉淀出口分流：snowflake id string 约定 → cs-keep 候选；mvn test class 锁 → attention 候选
- 覆盖率诚实标记：re-verified（lint/test/grep/交付物）、trust-prior-verify（联调场景来自 qa.md）
- 无未处理缺口

## Verdict

- Status: passed
- 9 节核对完成，所有 checks passed
- residual-risk owner 终审接受（R1 后续 feature、R2 项目现状、R4 counter issue 已开）
- 第6节 req 保持现状不 backfill（与上 feature 一致，openspec 作权威）
- attention 候选2条（snowflake id string 约定 + mvn test class 锁）owner 待定
- **owner 终审通过**
