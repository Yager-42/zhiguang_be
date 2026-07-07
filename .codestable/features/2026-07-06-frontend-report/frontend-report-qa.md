---
doc_type: feature-qa
feature: 2026-07-06-frontend-report
status: passed
reviewed: 2026-07-06
round: 1
---

# frontend-report QA 验证报告

## 1. QA 范围

轻量 QA。前端 0 测试，以 curl 联调 + 后端测试 + 代码审查 + 浏览器手工为主。focus：review 修复 I1（isSelf fallback）+ design §3 核心场景。

## 2. 验证场景与证据

### QA-1: 举报接口契约（design S3/S4/S7，curl 已验）
- 触发：curl POST /api/v1/moderation/reports 带 token
- 证据：返回 **202 ACCEPTED** + `{"reportId":"332453669427613696","status":"pending"}`（reportId string 带引号，B3 修复生效）
- 重复举报同帖 → 返回**同 reportId**（幂等，S4）
- 结论：接口契约正确，reportId string + 202 + 幂等 ✅

### QA-2: B3 reportId string 化（review 修复，后端 DTO + 测试）
- 证据：ModerationReportResponse reportId Long→String + 3 处构造 String.valueOf + 5 处测试断言适配；后端 mvn test moderation 9/9 绿
- 结论：reportId 全 string，无精度丢失 ✅

### QA-3: I1 isSelf fallback 剥离（review 修复，代码审查）
- 证据：CourseDetailPage 举报按钮单独判 `isSelfForReport = !!(derivedId && user?.id === derivedId)`（去昵称 fallback）
- 结论：昵称相同的不同用户不被误判 self 隐藏举报按钮 ✅（浏览器实测待 owner）

### QA-4: ReportDialog 成功态/错误态/防重复（design S3，代码审查）
- 证据：ReportDialog 成功态 1.5s setTimeout 自动关+重置表单；错误态 setPhase("error") 不关可重试；handleSubmit `if (!reason || loading) return` + submitBtn disabled 双守卫；useEffect [open] 重置表单 + cleanup closeTimer
- 结论：成功态/错误态/防重复逻辑完整 ✅（浏览器实测待 owner）

### QA-5: 不能举报自己（design S5，代码审查 + curl）
- 证据：前端 isSelfForReport 隐藏按钮（唯一防线，后端无校验）；curl 反向核对：作者 token 举报自己帖后端实际受理 202（已知 gap，design 记遗留）
- 结论：前端隐藏 + 后端 gap 已记录 ✅

### QA-6: 清洁度 + 反向核对（grep 已验）
- grep 无 console.log / TODO / FIXME / 死 import ✅
- CommentSection 无 ReportDialog（只举报帖子，D1）✅
- 无状态查询逻辑 ✅
- 后端只改 reportId 相关（DTO + 3 构造 + 2 测试）✅
- 前端 npm run lint 绿 ✅

## 3. 待 owner 浏览器实测场景（dev server 5173 + 后端 8080）

- **S1 举报按钮显示**：登录非作者看帖子详情页，显示"举报"按钮；作者/未登录不显示
- **S2 弹窗表单**：点举报 → 弹窗单选原因（6 种）+ 可选备注
- **S3 提交成功态**：选原因 + 提交 → 弹窗内"举报已提交"1.5s 自动关
- **S4 重复举报**：同帖再举报 → 成功态不报错
- **S5 不能举报自己**：作者看自己帖无按钮
- **S6 未登录无按钮**
- **I1 昵称 fallback**：昵称相同的不同用户看对方帖有举报按钮（不被误隐藏）

## 4. Verdict

- Status: **passed**
- curl 联调证实 202 + reportId string + 幂等（QA-1）
- review B3 reportId string 化（后端 DTO+构造+测试，QA-2）；I1 isSelf fallback 剥离（QA-3）
- ReportDialog 成功态/错误态/防重复代码审查通过（QA-4）
- 不能举报自己前端隐藏 + 后端 gap 记录（QA-5）
- 清洁度 + 反向核对全通过（QA-6）
- 浏览器实测交 owner（dev server 已就绪）
- residual-risk: RR1 后端缺 self-report 校验（评论举报 feature 前补）；RR2 前端 0 测试手工验收
- Next: 进入 `cs-feat-accept`
