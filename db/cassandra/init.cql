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

-- Follow feed (Timeline). Both are TTL'd, mostly-immutable time-series → TWCS.
-- inbox: per-follower pushed posts (push half). author_feed: per-author recent posts (pull half).
-- publish_ts captured once from the content_published event (ms), reused verbatim on replay.
-- content_id is a snowflake (ms timestamp in high bits) → authoritative DESC tiebreaker.
CREATE TABLE IF NOT EXISTS zhiguang.feed_inbox (
  user_id    bigint,
  publish_ts timestamp,
  content_id bigint,
  author_id  bigint,
  PRIMARY KEY (user_id, publish_ts, content_id)
)
WITH CLUSTERING ORDER BY (publish_ts DESC, content_id DESC)
  AND default_time_to_live = 2592000
  AND gc_grace_seconds = 0
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_unit': 'DAYS',
    'compaction_window_size': '1'
  };

CREATE TABLE IF NOT EXISTS zhiguang.feed_author_feed (
  author_id   bigint,
  publish_ts  timestamp,
  content_id  bigint,
  PRIMARY KEY (author_id, publish_ts, content_id)
)
WITH CLUSTERING ORDER BY (publish_ts DESC, content_id DESC)
  AND default_time_to_live = 2592000
  AND gc_grace_seconds = 0
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_unit': 'DAYS',
    'compaction_window_size': '1'
  };
