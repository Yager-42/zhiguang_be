# Parent Implement Plan

> 本文件描述父任务后续如何调度子任务，不是直接实现清单。

## Execution Order

1. 先完成 Child A：`06-30-skills-knowledge-structure`
2. 再完成 Child B：`06-30-skills-generated-knowledge`
3. 再完成 Child C：`06-30-skills-diff-check-loop`
4. 最后回到父任务做集成验收

## Why This Order

- 没有 A，就没有明确的 skill 边界和命名
- 没有 B，C 的 diff-check 缺乏结构化事实来源
- 只有 A/B 稳定后，C 才能做成闭环而不是猜测系统

## Parent Validation

父任务最终需要检查：

- 三个子任务的设计是否互相兼容
- ownership / routing / generated assets / diff-check 输入输出是否一致
- 是否还有遗漏的全局边界问题

## Do Not Do In Parent

- 不直接实现代码
- 不直接写真实 `skills/`
- 不直接创建自动化脚本

## Current Stop Point

父任务当前只做到：

- 子任务创建
- 子任务规划
- 总体验收口径定义
