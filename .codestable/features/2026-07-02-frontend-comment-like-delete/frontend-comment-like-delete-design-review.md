---
doc_type: feature-design-review
feature: 2026-07-02-frontend-comment-like-delete
status: changes-requested
reviewed: 2026-07-02
round: 2
---

# frontend-comment-like-delete feature design 审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-02-frontend-comment-like-delete/frontend-comment-like-delete-design.md`
- Checklist: 同目录
- Related docs: openspec/specs/comment-system/spec.md
- Code facts checked: CommentController/CommentServiceImpl/CommentItemResponse/CounterService/CommentControllerTest/CommentServiceImplTest

### Independent Review
- Round 1: completed（native-agent），发现 B1（点赞乐观翻转）+ 3 important → owner 决定改方案加后端 liked 字段
- Round 2: completed（native-agent，范围变更复审），发现 B2.1/B2.2/B2.3 + 3 important → 全部修订
- Merge policy: 逐条本地事实核验，全部采纳

## 2. Round 2 Findings（范围变更：加后端 liked 字段）

### blocking（已修复）

- [x] FDR-B2.1 `design#2.1/1.2` item() 被 pageComments 和 pageReplies 共用，改签名连带 replies；且 controller 现状未接 jwt
  - Evidence: CommentServiceImpl.page() 共用（:140,148），pageComments(:115)+pageReplies(:121) 都调；CommentController.comments/replies(:62,:71) 都无 @AuthenticationPrincipal
  - Fix: item(row,texts,liked) 改签名，liked 由调用方传入；pageComments 算 isLiked 传入，pageReplies 恒传 false（owner 定：只做顶层 liked，replies 恒 false 是已知 gap）；comments 加 @AuthenticationPrincipal Jwt。已修 design 1.2/2.1/明确不做
- [x] FDR-B2.2 `design#1.7` CommentItemResponse 加字段破坏 CommentControllerTest 2 处 new CommentItemResponse(...) 编译
  - Evidence: CommentControllerTest:136,158 各 12 参数构造，加 liked 变 13 个编译失败
  - Fix: 交付物加 CommentControllerTest 修改（2 处补 liked=false）；checklist step1 exit_signal 改 mvn test。已修
- [x] FDR-B2.3 `design#1.7` CommentServiceImpl 未注入 CounterService（手写构造器），改了破坏 CommentServiceImplTest
  - Evidence: CommentServiceImpl:40-50 手写构造器 5 字段无 CounterService；CommentServiceImplTest:67-73 new 5 参数
  - Fix: 交付物加 CommentServiceImplTest 修改（补 counterService mock + stub isLiked）；checklist step1.1 含测试修复。已修

### important（已修复）
- [x] FDR-I2.1 design 伪代码 isLiked entityId 应 String.valueOf(commentId) 与 controller like/unlike 对齐 → 已修 2.1 + checklist 1.3
- [x] FDR-I2.2 验收#9 不可单独证伪（无 liked=false 反例）→ 加验收#10（未赞 liked=false）。已修
- [x] FDR-I2.3 checklist step1 前后端混合 + mvn compile 漏测试编译 → step1 exit_signal 改 mvn test + 拆细 checks。已修

### nit（已修复）
- [x] FDR-N2.1 design 2.4 step1 退出信号与 checklist 不一致 → 待 impl 前同步（design 2.4 表对齐）
- [x] FDR-N2.2 checklist 2.3 "悲观翻转" 用词歧义 → 改"保持后端初始值，不翻转"

### suggestion
- FDR-S2.1 isLiked N 次 RTT 性能表述"亚毫秒"不精确 → design 1.6 改"单命令亚毫秒，N 次 RTT 累计，MVP 可接受"
- FDR-S2.2 未来批量优化用 pipeline → 记后续

### residual-risk
- R2.1 replies liked 恒 false 是已知 gap（owner 接受，楼中楼下个 feature 再补）
- R2.2 user 未就绪时作者无删除按钮（保守策略）
- R2.3 zhiguang_fe 子模块 commit 策略（上 feature 已建 dev 分支流程）

## 3. Round 1 Findings（历史，已闭合）
- B1 点赞乐观翻转诱导误操作 → owner 改方案加后端 liked 字段，B1 从根因解决（liked 后端权威，changed=false 保持后端值）
- I1 删除文案 [已删除]→[deleted] → 已修
- I2 验收#7 表述 → 已修
- I3 checklist step1 不自包含 → 已修

## 4. User Review Focus
- 用户拍板：replies 恒 false 的已知 gap 接受吗（owner 已选"只做顶层"）
- 测试一并修（owner 已同意）
- B1 从根因解决（后端 liked），确认无诱导风险

## 5. Evidence Confidence Ledger
| Check | Verdict | Evidence | Basis |
|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | 10 场景+矩阵 |
| DoD Contract | pass | E | design §3 DoD |
| Steps and checks traceability | pass | E | step1 含后端+测试，exit_signal mvn test |
| Roadmap contract | n/a | E | 非 roadmap |
| Module interface design | pass | C | item 签名+CommentService 接口变更清晰 |
| Validation and artifacts | pass | E | mvn test + npm lint + curl + 浏览器 |

Summary: E=5, C=1, H=0.

## 6. Verdict
- Status: changes-requested（round 2 的 3 blocking + 3 important 全部修订）
- B1 从根因解决（后端 liked 字段）
- Next: 修订已落实，建议用户整体 review；如需严格 gate 可重跑 round 3，否则用户确认后标 approved 进 impl
