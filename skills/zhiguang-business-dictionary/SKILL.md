---
name: zhiguang-business-dictionary
description: zhiguang 跨域业务词典 Skill。用于统一名词、对象层级、真相源、状态语义和跨域概念；当需求涉及 `status`、`visible`、`inbox`、`author_feed`、`escrow`、`auction window`、`bounty`、`hold` 等高频术语，或在改接口、流程、配置前需要先对齐对象语义时使用。
---

# zhiguang-business-dictionary

这个 skill 不直接负责改某个业务域，它先解决“你到底在改什么对象”。

## 使用顺序

- 先看 `references/object-dictionary.md`，统一对象层级和真相源。
- 再判断当前对象属于 auth/content/social/platform/common 的哪个域。
- 语义已经清楚后，再切到对应领域 skill 做具体修改。

## 必守约束

- 先判定真相源，再改实现。
- 不把运行态缓存、派生结果、DTO 视图误当成主记录。
- 同一个名词跨域复用时，必须写清上下文，不默认同义。

## 参考资料

- `references/object-dictionary.md`
