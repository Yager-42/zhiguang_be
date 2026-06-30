---
name: zhiguang-change-playbook
description: zhiguang 跨域变更剧本 Skill。用于处理跨接口、跨事件、跨缓存、跨搜索/推荐、跨通知或跨多个业务域的改动；当需求不是单文件微调，而是需要显式检查影响面、回滚点和跨层一致性时使用。
---

# zhiguang-change-playbook

## 使用顺序

- 先看 `references/cross-domain-checklist.md`，判定当前改动覆盖哪些域。
- 回到 `zhiguang-repo-map` 与 `zhiguang-business-dictionary` 补齐入口和术语。
- 再分别进入受影响的业务 skill 执行实际修改。

## 核心原则

- 先定对象，再定契约，再定实现。
- 一旦改动跨两个以上域，就显式列影响面和回滚点。
- 搜索、推荐、通知、计数、修复任务通常是派生链路，不能只改主写链不看下游。

## 参考资料

- `references/cross-domain-checklist.md`
