-- 发布链路 v2 Cassandra 正文离线回填。
-- 仅在获批维护窗口、应用停止写入且新表 DDL 已执行后，由 cqlsh 执行。
-- COPY 的文件路径位于运行 cqlsh 的主机/容器；旧表不会被删除，供旧镜像回滚。

COPY zhiguang.post_text_by_post_id
    (post_id, body, sha256, updated_at)
    TO '/tmp/post_text_v2.csv'
    WITH HEADER = TRUE;

COPY zhiguang.post_text_archive_by_post_id
    (post_id, body, sha256, archived_at)
    FROM '/tmp/post_text_v2.csv'
    WITH HEADER = TRUE;
