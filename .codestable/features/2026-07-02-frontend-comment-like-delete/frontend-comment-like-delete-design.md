---
doc_type: design
feature: 2026-07-02-frontend-comment-like-delete
status: approved
summary: 在评论列表项上加点赞按钮 + 作者删除按钮，接入评论点赞/取消点赞/删除 3 接口
tags: [frontend, comment, like, delete]
requirement: comment-system
---

# 前端评论点赞 + 删除设计

## 0. 需求摘要与决策

**用户目标**：评论列表项能点赞/取消点赞，作者能删除自己的评论。

**核心行为**：
- 每条评论项渲染点赞按钮（心形 + likeCount），点击切换点赞/取消
- 作者的评论项额外渲染删除按钮，点击后该条变 `[deleted]`
- 非作者评论项无删除按钮

**成功标准**：
- 登录用户点评论点赞 → 接口返回 `{changed:true}` → likeCount +1、心形高亮
- 重复点同一评论 → `{changed:false}` → likeCount 不变
- 作者点删除 → 204 → 该条 body 变 `[deleted]`、点赞/删除按钮禁用
- 非作者评论项无删除按钮（grep/肉眼确认）
- 联调：3 个后端接口真实打通

**明确不做**（反向可核对）：
- 不做"我赞过哪些"的批量查询持久化（liked 由后端列表字段提供，刷新不丢）
- 不接评论回复楼中楼 `GET /comments/{id}/replies`（前端未接，后续 feature）
- **replies 接口的 liked 恒 false**（只做顶层评论 liked，replies 路径 item 恒传 false，是已知 gap）
- 不做删除的二次确认弹窗之外的撤销机制
- 不做点赞/删除的乐观渲染（等接口返回再更新，因点赞 changed 语义需确认）
- 不做 list 查询 liked 的批量优化（MVP 逐条 isLiked，一页 20 次 Redis GETBIT，单命令亚毫秒，N 次 RTT 累计可接受）

**复杂度档位**：走默认档位。

**owner 已定决策**：
- liked 初始：**后端 CommentItemResponse 加 liked 字段**，list 查询用 `CounterService.isLiked` 逐条填充当前用户点赞态，前端初始渲染即正确、刷新不丢
- 删除展示：删除成功后本地标 `[deleted]` 不从列表移除（与后端软删除一致，保留线程结构）
- 删除按钮：只对作者显示（creatorId === user.id）
- 性能：MVP 逐条 isLiked，不做批量位图查询

**spec 落点**：后端仓库 `.codestable/features/`，实现在前端子模块 `zhiguang_fe`。依赖既有 `openspec/specs/comment-system/spec.md`。

---

## 1. 决策与约束、风险与证据

### 1.1 结构归属

评论点赞/删除是评论列表项的交互，归 `zhiguang_fe/src/components/comment/`，在 CommentSection 的列表项 item 上扩展。不新建文件——点赞/删除按钮直接加在 CommentSection.tsx 的 item 渲染里，service 方法加在 commentService.ts，类型扩展加在 types/comment.ts。理由：MVP 已建 CommentSection，本次是其列表项的功能增强，不另起组件。

### 1.2 关键契约（design 必读）

**id 序列化契约（snowflake 精度，本 feature 必须修）**：
- 评论 commentId 是 snowflake long，超过 JS `Number.MAX_SAFE_INTEGER`（2^53）。后端若序列化成 number，前端 JSON.parse 丢精度（如 `331035581662498816` → `331035581662498800`），导致点赞写错 commentId 的位图、刷新查正确 commentId 对不上 → liked 丢失、删除 404
- 项目既有模式：`KnowPostDetailResponse` 把 id/authorId 声明为 `String`（后端 `String.valueOf` 转），JSON 带引号，前端不丢精度
- 本 feature 修复：`CommentItemResponse` 的 commentId/postId/rootId/parentId/creatorId 改 `String`（对齐 KnowPostDetailResponse）；`CommentSubmitResponse.pendingCommentId` 改 `String`；前端 types/comment.ts 对应字段改 string；commentService.like/unlike/delete 接收 string commentId
- 这是评论 MVP 遗留的 id 类型 bug，点赞/删除要用 commentId 调接口才暴露

**评论点赞返回结构与帖子点赞不同**（已核对后端 `CommentController.like`）：
- 帖子点赞 `POST /action/like` → 返回 `{changed, liked}`
- **评论点赞 `POST /comments/{id}/like` → 只返回 `{changed}`，无 liked 字段**
- **评论取消点赞 `DELETE /comments/{id}/like` → 只返回 `{changed}`，无 liked 字段**

**liked 初始状态由后端列表提供**（本 feature 新增后端改动）：
- 后端 `CommentItemResponse` 加 `boolean liked` 字段
- `CommentController.comments` 加 `@AuthenticationPrincipal Jwt`，提取 userId 传给 `commentService.pageComments(..., userId)`
- `CommentServiceImpl.pageComments` 用 `CounterService.isLiked("comment", String.valueOf(commentId), userId)` 逐条算 liked，传入 `item(row, texts, liked)`
- 鉴权事实：controller 方法现状未接 jwt，但 SecurityConfig `anyRequest().authenticated()` 在 controller 前拦截，未登录 401（MVP 已验证）；登录用户访问时 controller 接 jwt 拿 userId
- 前端初始渲染用后端 liked（权威），刷新不丢

**replies 路径处理**（本 feature 明确范围）：
- `item()` 被 `pageComments` 和 `pageReplies` 共用。本 feature 只做**顶层评论**的 liked
- `item()` 改签名为 `item(row, texts, liked)`，liked 由调用方传入：`pageComments` 算出 liked 传入，`pageReplies` 恒传 `false`
- replies 接口不加 jwt（前端未接 replies，楼中楼是后续 feature），其 liked 恒 false 是已知 gap

因此前端本地 `liked` 初始值来自后端，点赞/取消后用 `changed` 更新：**changed=true 翻转本地 liked + ±1 计数；changed=false 不动**（后端单向幂等，changed=false 说明状态没变，本地保持后端初始值即可，无诱导误操作风险）。

### 1.3 Top 3 风险

1. **liked 字段后端填充性能**（最易验收遗漏）：list 每条评论调一次 `isLiked`（Redis GETBIT），一页 20 条 = 20 次 Redis 查询。缓解：Redis GETBIT 亚毫秒，MVP 可接受；若后续评论量大再加批量位图查询。
2. **删除按钮权限误显**（最易实现偏）：若 `user.id` 未加载（token 有但 user null），`creatorId === user.id` 判断失败，作者也看不到删除按钮。缓解：用 `user?.id`，user 未就绪时不显示删除按钮（保守）。
3. **点赞/删除并发**（最难回滚）：用户快速连点点赞，多个请求在途，`changed` 返回顺序乱导致计数错。缓解：用 loadingRef 同步守卫（上一个 feature I2 的成熟模式），点赞中禁用按钮。

### 1.4 非显然依赖

- 后端删除 `commentService.delete(creatorId, commentId)` 校验作者，非作者/不存在抛 `badRequest("comment not found or not owned by user")` → 400。前端删除按钮只对作者显示，正常不会触发 400；但若评论作者刚换账号/数据漂移，删除可能 400，前端 catch 显示错误。
- 后端删除是软删除（`softDelete`），列表里 deleted=true 的 body 显示 `[deleted]`。前端删除成功后本地把 body 改 `[deleted]` + deleted=true，刷新后后端也返回一致状态。
- `CommentItem.likeCount` MVP 已透传，点赞后本地 ±1 更新。

### 1.5 关键假设

- 假设：后端 `counterService.like("comment", id, userId)` 是单向幂等置位（已赞再 like 返回 changed=false 不翻转，未赞返回 changed=true +1）；`unlike` 单向清零。已核对 `CounterServiceImpl.like` 注释"仅当状态从未点赞→已点赞时返回 true"。
- 假设：`crypto.randomUUID` 可用（上 feature 已验证，安全上下文）。

### 1.6 必跑验证命令与基线风险

- `cd zhiguang_fe && npm run lint`（tsc --noEmit）—— 基线绿（上 feature 验证）
- 前后端联调：5173 + 8080
- 浏览器手工：点赞/取消/删除三个操作
- 基线风险：前端 0 测试，QA 手工为主

### 1.7 交付物清单

后端（本 feature 新增）：
- 修改 `CommentItemResponse.java`（加 `boolean liked` 字段）
- 修改 `CommentServiceImpl.java`（注入 CounterService 手写构造器加参数；`item(row, texts, liked)` 改签名；`pageComments` 接 userId 逐条 `isLiked`；`pageReplies` item 恒传 false）
- 修改 `CommentService.java` 接口（`pageComments` 签名加 userId 参数）
- 修改 `CommentController.java`（`comments` 加 `@AuthenticationPrincipal Jwt` 提取 userId 传 service）
- 修改 `CommentControllerTest.java`（2 处 `new CommentItemResponse(...)` 补 liked 参数，给 false）
- 修改 `CommentServiceImplTest.java`（构造器补 counterService + `@Mock CounterService` + pageComments 测试 stub `isLiked` 返回 false）

前端：
- 修改 `zhiguang_fe/src/types/comment.ts`（CommentItem 加 `liked: boolean`；CommentLikeResponse 类型）
- 修改 `zhiguang_fe/src/services/commentService.ts`（加 like/unlike/delete 方法）
- 修改 `zhiguang_fe/src/components/comment/CommentSection.tsx`（item 加点赞按钮 + 作者删除按钮 + 本地状态管理）
- 修改 `zhiguang_fe/src/components/comment/CommentSection.module.css`（按钮样式）
- 不新增配置、不新增 schema

### 1.8 清洁度规则

- 无 console.log/TODO/FIXME
- 无注释掉代码
- 无用 import 清理

---

## 2. 名词层与编排层

### 2.1 名词层

**现状**（指向代码位置）：
- `types/comment.ts`：CommentItem 已含 `likeCount: number`、`deleted: boolean`、`creatorId: number`，**无 liked 字段**
- `services/commentService.ts`：已有 list/submit，无 like/unlike/delete
- `CommentSection.tsx`：item 渲染 itemBody + itemMeta，无操作按钮
- `LikeFavBar.tsx`：帖子点赞/收藏参考模式（用 `resp.liked` 校正）
- 后端 `CommentController.like/unlike` 返回 `Map.of("changed", changed)`，`delete` 返回 204
- 后端 `CommentItemResponse`：无 liked 字段；`CommentServiceImpl.item(row, texts)` 构造响应，未注入 userId
- 后端 `CounterService.isLiked(entityType, entityId, userId)` 已存在（Redis GETBIT）

**变化**——后端 DTO 加字段 + 前端类型/service 扩展：

后端（本 feature 新增改动）：
```java
// CommentItemResponse 加 liked 字段
public record CommentItemResponse(
    // ...既有字段
    boolean liked  // 新增：当前用户是否已赞
) {}

// CommentServiceImpl.item 改签名接 liked（由调用方算好传入）
private CommentItemResponse item(Comment row, Map<Long,String> texts, boolean liked) {
    // ... 构造 CommentItemResponse 时填 liked
}

// pageComments 接 userId，逐条算 liked
public CommentPageResponse pageComments(long postId, ..., long currentUserId) {
    // page() 内 .map(row -> item(row, texts,
    //     counterService.isLiked("comment", String.valueOf(row.getCommentId()), currentUserId)))
}
// pageReplies 不接 userId，item 恒传 false（replies liked 是已知 gap）
// CommentServiceImpl 注入 CounterService（手写构造器加第 6 个参数）
// CommentController.comments 加 @AuthenticationPrincipal Jwt，提取 userId 传 pageComments
```

前端：
```ts
// types/comment.ts 扩展
export type CommentItem = {
  // ...既有字段
  liked: boolean;  // 后端返回，初始即正确
};
export type CommentLikeResponse = { changed: boolean };
```

```ts
// services/commentService.ts 扩展
like: (commentId, accessToken) =>
  apiFetch<CommentLikeResponse>(`${PREFIX}/comments/${commentId}/like`, { method: "POST", accessToken }),
unlike: (commentId, accessToken) =>
  apiFetch<CommentLikeResponse>(`${PREFIX}/comments/${commentId}/like`, { method: "DELETE", accessToken }),
delete: (commentId, accessToken) =>
  apiFetch<void>(`${PREFIX}/comments/${commentId}`, { method: "DELETE", accessToken }),
```

接口行为示例：
- `list` → items 每条含 `liked`（当前用户是否赞过）
- `like(commentId=99)` → `200 {changed: true}`（首次赞）或 `{changed: false}`（已赞过）
- `delete(commentId=99)` → `204`（作者）或 `400`（非作者/不存在）

### 2.2 编排层

主流程（点赞 + 删除）：

```mermaid
flowchart LR
  A[列表项渲染] --> B{作者?}
  B -->|是| C[显示删除按钮]
  B -->|否| D[只显示点赞]
  D --> E{点点赞?}
  E -->|当前未赞| F[like→changed]
  E -->|当前已赞| G[unlike→changed]
  F --> H{changed?}
  G --> H
  H -->|true| I[翻转liked+计数±1]
  H -->|false| J[不动]
  C --> K{点删除?}
  K -->|是| L[delete→204]
  L --> M[本地body=[deleted]+禁用按钮]
```

**关键编排约束**：
- **错误语义**：like/unlike/delete 失败（网络/非2xx）→ ApiError 捕获，按钮恢复可点，不显示 toast（MVP 静默失败，与 LikeFavBar 一致）；删除 400（非作者）→ 该条已不是作者可见状态，静默忽略。
- **幂等性**：后端 like/unlike 是单向幂等（like 仅置位、unlike 仅清零，重复操作 changed=false）。前端 `liked` 初始值来自后端列表字段（权威），点赞/取消后按 `changed` 更新：`changed=true` 翻转本地 liked + ±1 计数；`changed=false` 不动（本地保持后端初始值，无诱导误操作风险，因初始态准确）。
- **并发**：点赞/删除用 loadingRef 同步守卫，操作中禁用按钮，防止连点竞态。
- **删除后状态**：delete 204 后本地 `setItems` 把该条 body 改 `[deleted]`（与后端 `DELETED_BODY` 一致，避免刷新后文案跳变）、deleted=true、点赞/删除按钮禁用（不卸载，保留线程结构）。乐观占位项（__optimistic）不显示删除按钮（pendingCommentId 未落库，删除无意义）。

### 2.3 挂载点清单（删了它 feature 是否消失）

- CommentSection.tsx item 内的点赞按钮 + 删除按钮渲染逻辑 → 删了交互消失 ✅
- commentService.ts 的 like/unlike/delete 方法 → 删了接口调用消失 ✅
- types/comment.ts 的 CommentLikeResponse + CommentItem.liked → 删了类型消失 ✅

共 3 条，在正常区间。无新增挂载点（复用 MVP 的 CommentSection）。

### 2.4 推进策略

| step | 切片 | 退出信号 |
|---|---|---|
| 1 | 类型 + service（CommentLikeResponse、CommentItem.liked、like/unlike/delete 方法） | npm run lint 通过；service 导出 like/unlike/delete |
| 2 | CommentSection item 加点赞按钮（本地 liked 状态 + likeCount ±1 + loadingRef 守卫） | 点赞/取消点赞浏览器可见，计数正确，连点不乱 |
| 3 | CommentSection item 加作者删除按钮（creatorId===user.id 显示 + 删除后本地标[deleted]） | 作者可见删除按钮、非作者不可见、删除后变[deleted] |
| 4 | 联调 + harden（3 接口真实打通 + 清洁度） | 浏览器三操作验证 + Network 见 200/204 + 无 console/TODO |

### 2.5 结构健康度与微重构

**文件级评估**：CommentSection.tsx 当前约 205 行（上 feature 加了守卫逻辑），本次再加点赞/删除按钮 + 状态管理，预计到 280-300 行。偏胖但仍在可接受范围，且点赞/删除逻辑与列表渲染强耦合（共享 item 数据、items state），拆出去反而要传一堆 props。**本次不做微重构**，原因：拆 CommentItem 子组件需透传 liked/loading/delete 等状态，props 膨胀，收益不抵复杂度。若未来加回复楼中楼再拆。

**目录级评估**：`components/comment/` 上 feature 新建，只有 CommentSection，不拥挤。

**结论**：不做微重构。

**超出范围的观察**（提示，不阻塞）：CommentSection 280+ 行后，若再加回复/编辑功能应拆 `CommentItem` 子组件，建议后续走 `cs-refactor`。

---

## 3. 验收契约

| # | 场景 | 触发 | 期望 | 证据 |
|---|---|---|---|---|
| 1 | 点赞成功 | 登录用户点未赞评论的心形 | 200 {changed:true} + likeCount+1 + 心形高亮 | 浏览器+Network |
| 2 | 取消点赞 | 点已赞评论的心形 | 200 {changed:true} + likeCount-1 + 心形恢复 | 浏览器+Network |
| 3 | 重复点赞 | 已赞再点（本地态）| changed:false + 计数不变 + 本地 liked 保持后端值 | 浏览器 |
| 4 | 作者删除 | 作者点自己评论的删除 | 204 + 该条 body变[deleted] + 按钮禁用 | 浏览器+Network |
| 5 | 非作者无删除 | 看别人的评论项 | 无删除按钮 | 浏览器 |
| 6 | 删除后刷新 | 场景4后刷新页面 | 该条仍显示[deleted]（后端软删除一致） | 浏览器 |
| 7 | 未登录场景 | 未登录（!tokens.accessToken）访问详情页 | 渲染"登录后查看评论"，无列表项 | 代码分支 |
| 8 | 明确不做核对 | grep 前端代码 | 无 /comments/{id}/replies 调用 | grep |
| 9 | liked 初始正确 | 赞一条评论后刷新详情页 | 该条心形高亮（后端 liked=true，刷新不丢） | 浏览器+Network |
| 10 | 未赞 liked=false | 看一条未赞的评论 | 心形不高亮（后端 liked=false） | 浏览器+Network |

### Acceptance Coverage Matrix

| 场景 | 正常 | 边界 | 错误 |
|---|---|---|---|
| 点赞 | #1,#2 | #3 | （静默，#7不触发） |
| 删除 | #4,#6 | #5 | （400静默） |

### DoD Contract

- [ ] 3 个后端接口联调通（Network 见 200/204）
- [ ] 点赞 likeCount ±1 正确，changed=false 不动
- [ ] 作者删除变[deleted]，非作者无按钮
- [ ] 刷新后删除态与后端一致
- [ ] grep 无 /replies 调用
- [ ] npm run lint 通过
- [ ] 无 console/TODO/死 import

---

## 4. 后续衔接

- 实现走 `cs-feat-impl`，按 checklist 4 步
- commit 前走 `cs-code-review`
- QA 走 `cs-feat-qa`（手工浏览器）
- 验收走 `cs-feat-accept`
- 回复楼中楼 + 异步状态轮询作为后续 feature
