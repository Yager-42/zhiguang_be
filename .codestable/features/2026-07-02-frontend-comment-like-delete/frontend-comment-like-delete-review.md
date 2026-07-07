---
doc_type: feature-code-review
feature: 2026-07-02-frontend-comment-like-delete
status: passed
reviewer: subagent
reviewed: 2026-07-03
round: 1
---

# frontend-comment-like-delete 代码审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-02-frontend-comment-like-delete/frontend-comment-like-delete-design.md`（approved）
- Checklist: 同目录（4 步全 done）
- diff 范围：后端 9 文件（DTO id Long→String + Service/Controller liked 字段 + 2 测试）+ 前端 4 文件（types/service/CommentSection+css）
- Code facts checked: CounterService.isLiked、KnowPostDetailResponse.id String 模式、CommentTask2ContractTest 反射断言

### Independent Review
- 环节 A（独立 Task agent）：completed，native-agent（Agent 工具，无 Paseo）
- 环节 B（OCR）：not-available（ocr 未安装，降级不阻塞）
- Merge policy: 逐条本地事实核验，I1 采纳并修复，I2 转为 residual-risk

## 2. 审查结论

无 blocking。实现质量高，id 精度修复核心逻辑正确（DTO String 化、null 处理、isLiked key 对齐、changed 语义、replies gap）。1 个 important（I1 按钮 disabled）已修复，1 个 important（I2 userId 未 string 化）记 residual-risk。

## 3. Findings

### blocking
none

### important（I1 已修复，I2 记 residual-risk）

- [x] REV-I1 `CommentSection.tsx` 点赞/删除按钮无 disabled 视觉态，偏离 design §2.2「操作中禁用按钮」
  - Evidence: likingRef/deletingRef 拦了重复请求但按钮外观不变；CSS 无 :disabled
  - Impact: 请求在途时用户视觉无反馈，与 design 契约不符
  - Fix: 加 likingIds/deletingIds state（驱动渲染）+ 按钮 disabled={...} + CSS :disabled 样式。已修复，lint 通过

- [ ] REV-I2 `user.id` 仍 number，id 全 string 契约未闭环（系统性问题）
  - Evidence: AuthUserResponse.id 是 Long→前端 number；String(user?.id) 在可能丢精度的 number 上 toString
  - Impact: 当前 users.id AUTO_INCREMENT 小值不触发；但 commentId/postId 全 string 了 userId 没，契约不对称
  - 处理: 记 residual-risk，后续单独 feature 把 AuthUserResponse.id 改 String + 前端 User.id:string。本 feature 不修（跨 auth 系统，超出范围）

### nit
- REV-N1 toOptimistic creatorId 用 String(user?.id ?? 0)，user 未就绪落 "0"——乐观项不显示删除按钮，无功能损害，可接受
- REV-N2 handleLike mountedRef early-return 后 finally 仍清理 likingRef——正确（ref 随组件销毁）
- REV-N3 retry 调 loadFirst() 不传 signal——signal 可选，不传不取消，正确

### suggestion
- REV-S1 后端 item() 对 deleted 行仍查 isLiked（轻微浪费）——MVP 可接受，可加 !deleted 短路
- REV-S2 item() 把 liked 计算耦合进 DTO 组装（design 原想调用方传入）——功能等价，风格偏好

### learning
- REV-L1 String.valueOf(null) 陷阱已正确规避：rootId/parentId 显式判空，避免 "null" 字符串污染
- REV-L2 CommentTask2ContractTest 反射 assertFields 只验字段名不验类型，id 改 String 不破契约
- REV-L3 MockMvc jsonPath().value(long) 对 String JSON 做 coercion，测试仍过但无法守护 snowflake 精度（测试盲区）

### praise
- id null 处理严谨（rootId/parentId 显式判空）
- replies liked 恒 false 实现干净（pageReplies 传 0L + currentUserId>0 短路，注释清晰）
- isLiked entityId 与 controller like/unlike 对齐（String.valueOf，无 key 不一致）
- changed 语义正确（changed=true 才翻转+±1，Math.max 防负）
- 删除后状态与后端软删除一致（body=[deleted]+deleted=true+按钮禁用+不移除）
- 乐观项守卫（__optimistic 不显示操作按钮）
- 后端测试覆盖（liked 构造、isLiked stub、limit 边界、delete 作者/非作者/已删/missing）

### residual-risk
- REV-R1 id 全 string 契约未闭环（userId 仍 number）——见 I2，后续 feature 修
- REV-R2 前端 0 测试，liked/changed 边界靠手工 QA（项目现状）
- REV-R3 deleted 行 liked 仍查 Redis（见 S1，MVP 可接受）
- REV-R4 likeCount 不聚合（counter 系统 bug，已记 issue 2026-07-02-comment-likecount-not-aggregating）

## 4. Test And QA Focus（交 cs-feat-qa）

- id String 精度边界：现有测试用小 commentId + jsonPath coercion，无法守护 snowflake 精度。建议补 > 2^53 commentId 测试断言字符串精确匹配（测试盲区，QA 手工已验证联调 liked=true 保持）
- liked true/false 初始态：后端只 stub isLiked=false，建议补 true case
- changed=true/false 双分支：后端已覆盖，前端手工 QA（已赞再点 changed=false 不动）
- 按钮 disabled 态（I1 已修）：QA 验证点赞/删除在途按钮禁用
- 作者删除按钮显示：小 userId 验证（大 userId 无法测，见 I2）
- 删除后刷新一致性：手工 QA（checklist 4.2）
- 并发连点：likingRef/deletingRef + disabled 双守卫

## 5. Verdict

- Status: passed
- Reviewer: subagent（环节 A 完成，环节 B OCR not-available）
- I1 已修复（按钮 disabled），I2 记 residual-risk（userId string 化后续 feature）
- lint 通过，清洁度干净，后端 51 测试全过
- Next: 进入 `cs-feat-qa`
