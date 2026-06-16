package com.tongji.storage.text;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface PostTextRepository extends CassandraRepository<PostText, Long> {
}
