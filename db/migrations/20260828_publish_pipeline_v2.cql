CREATE TABLE IF NOT EXISTS zhiguang.post_text_archive_by_post_id (
  post_id bigint PRIMARY KEY,
  body text,
  sha256 text,
  archived_at timestamp
);
