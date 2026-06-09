# add-cassandra-text-storage

## Why

当前知文正文仍按对象存储正文文件设计，评论正文尚未实现。随着评论系统、发布 pipeline、搜索索引和 RAG 预索引引入，平台需要一个稳定的文字事实源，避免把 Elasticsearch 或向量库当作正文存储。

## What

- 引入 Cassandra 作为发布正文文字和评论/回复正文的事实存储。
- 保留 MinIO 作为图片、视频、附件等媒体对象存储。
- MySQL 继续存储帖子、评论元数据、状态、作者、可见性和内容 key。
- Elasticsearch 和向量库只作为可从 MySQL + Cassandra 重建的派生索引。
- 第一版 Cassandra 只支持按 ID 精确读取，不承担 Feed、作者列表、搜索或评论分页。

## Impact

- 新增 Cassandra 本地环境、配置、客户端和健康检查。
- 发布流程需要将文字正文写入 Cassandra，再由 pipeline 解析并构建 ES/RAG。
- 评论系统需要将评论正文写入 Cassandra，MySQL 评论表只保存正文 key 和元数据。
- 数据对齐服务需要检测 MySQL 元数据与 Cassandra 正文是否缺失或不一致。

## Non-goals

- 不迁移历史数据。
- 不用 Cassandra 承担搜索、Feed、作者列表或评论分页查询。
- 不把 ES 作为正文事实源。
