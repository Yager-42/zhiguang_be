---
name: zhiguang-content-domain
description: zhiguang 内容域 Skill。用于处理 `knowpost` 内容草稿、发布、可见性、详情、feed、对象存储、搜索与推荐；当需求命中 `KnowPostController`、`StorageController`、`SearchController`、`publish`、`feed`、`visible`、`draft`、MinIO、Cassandra、搜索或推荐链路时使用。
---

# zhiguang-content-domain

## 使用顺序

- 先看 `references/content-lifecycle.md`，确认内容生命周期、可见性、feed 语义。
- 再看 `references/storage-and-distribution.md`，确认对象存储、文本存储、搜索、推荐与缓存边界。
- 如果改动同时影响 comment / relation / notification / promotion，切 `zhiguang-change-playbook`。

## 必守约束

- 先分清主记录、内容对象、搜索视图、推荐视图和 feed 视图。
- `status`、`visible`、fanout 规则先对齐词典，再改代码。
- 不把搜索索引或推荐候选当成内容主记录。

## 参考资料

- `references/content-lifecycle.md`
- `references/storage-and-distribution.md`
