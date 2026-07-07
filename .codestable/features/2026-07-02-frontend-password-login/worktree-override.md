# Worktree Override

**reason**: 本 feature 为纯前端单文件改动（`LoginPage.tsx` + `LoginPage.module.css`），本地可逆、无后端改动、无数据迁移、无并发协作。attention.md 已记「本地可逆 feature 无需 worktree 隔离」。当前已在 `plan` 分支工作，无需额外 linked worktree。
**scope**: 仅 `zhiguang_fe/src/pages/LoginPage.tsx` 与 `zhiguang_fe/src/pages/LoginPage.module.css` 两个文件。
**approval**: 用户在 cs-feat 流程中口头授权进入实现（「可以」切到 cs-feat-impl）。worktree 隔离对纯前端单文件改动收益不抵成本，按 attention.md 约定跳过。
**cleanup**: 无 linked worktree 需清理；改动留在当前 `plan` 分支，由后续 commit gate + code review 把关。
