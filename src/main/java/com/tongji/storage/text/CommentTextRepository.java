package com.tongji.storage.text;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface CommentTextRepository extends CassandraRepository<CommentText, Long> {
}
