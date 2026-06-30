---
name: zhiguang-debug-playbook
description: zhiguang 排障剧本 Skill。用于处理入口不清、现象先于需求、状态不一致、重复消费、恢复失败、补偿、重试、重建等问题；当需要先按故障现象分流，而不是先改业务功能时使用。
---

# zhiguang-debug-playbook

## 使用顺序

- 先看 `references/failure-routing.md`，按症状选择第一批入口。
- 再切回对应业务 skill，确认主记录、派生结果和运行态的边界。
- 故障若明显跨域，补用 `zhiguang-change-playbook` 列影响面。

## 核心原则

- 先确认真相源，再确认哪个视图脏了。
- 先区分主写失败、异步消费失败、派生重建失败、缓存脏读，再决定如何修。
- 不用模糊兜底兼容隐藏未查清的问题。

## 参考资料

- `references/failure-routing.md`
