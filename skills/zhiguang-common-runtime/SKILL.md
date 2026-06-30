---
name: zhiguang-common-runtime
description: zhiguang 共享运行时 Skill。用于处理全局异常、错误码、ID 生成、共享配置和基础设施接线；当需求命中 `BusinessException`、`ErrorCode`、`GlobalExceptionHandler`、`IdService`、线程池、Redisson、Elasticsearch、共享 Spring 配置或共用 infra 契约时使用。
---

# zhiguang-common-runtime

## 使用顺序

- 先看 `references/shared-runtime.md`，确认当前改动属于异常契约、ID 契约还是基础设施接线。
- ????????????owner skill ??????? `references/generated-config-index.md`?
- 再定位到 `common`、`config`、`id` 或相关共享实现。
- 如果变更会改变具体业务域 DTO / 事件 / 状态，再切 `zhiguang-change-playbook`。

## 必守约束

- 共享运行时负责公共约束，不直接替代业务语义定义。
- 错误码、异常处理、ID 生成和共享配置改动要先看影响面。
- 不把 infra 接线改动误当成业务流程改动；也不反过来把业务语义塞进 common。

## 参考资料

- `references/shared-runtime.md`
- `references/generated-config-index.md`
- `scripts/extract_config_index.py`
