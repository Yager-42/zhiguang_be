package com.tongji.storage.text;

import org.springframework.data.cassandra.repository.CassandraRepository;

/**
 * 读取和删除已发布知文正文归档；创建操作由 LWT 语句完成。
 *
 * @since 2026-08-28
 */
public interface PostTextArchiveRepository extends CassandraRepository<PostTextArchive, Long> {
}
