# worktree override

- reason: main workspace contains unrelated dirty files and implementation must proceed in a linked execution worktree.
- scope: create and use a CodeStable quarantine worktree for `2026-07-06-distributed-singleflight`; do not move or revert unrelated main-workspace changes.
- approval: user confirmed "确认" and then requested "继续" after the approved design; main agent records this as approval to continue implementation with isolated worktree setup.
