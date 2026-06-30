---
name: zhiguang-platform-domain
description: zhiguang 平台治理与商业化 Skill。用于处理推广竞价、wallet、escrow、moderation 与 reconciliation；当需求命中 `PromotionController`、`WalletController`、`ModerationReportController`、`ReconciliationController`，或涉及 promotion、auction window、wallet、hold、escrow、moderation、repair、rerun 时使用。
---

# zhiguang-platform-domain

## 使用顺序

- 先看 `references/platform-flows.md`，确认商业化、治理和修复任务的对象边界。
- ??????????????wallet / escrow ??? reconciliation ?????? `references/generated-enum-index.md`?
- 再按子域定位到 `promotion`、`wallet`、`moderation` 或 `reconciliation`。
- 如果改动向上影响 content / social / auth 的契约，切 `zhiguang-change-playbook`。

## 必守约束

- 先分清商业化主记录、wallet 状态、治理动作和修复任务。
- `escrow`、`hold`、`slot allocation`、`decision projection` 都不是一个层面的对象。
- reconciliation 是修复和重放入口，不直接替代业务主链。

## 参考资料

- `references/platform-flows.md`
- `references/generated-enum-index.md`
- `scripts/extract_enum_index.py`
