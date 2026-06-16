CREATE KEYSPACE IF NOT EXISTS zhiguang
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 1
};

CREATE TABLE IF NOT EXISTS zhiguang.post_text_by_post_id (
  post_id bigint PRIMARY KEY,
  body text,
  version int,
  sha256 text,
  updated_at timestamp
);

CREATE TABLE IF NOT EXISTS zhiguang.comment_text_by_comment_id (
  comment_id bigint PRIMARY KEY,
  body text,
  version int,
  updated_at timestamp
);
