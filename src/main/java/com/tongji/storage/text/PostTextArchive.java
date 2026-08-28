package com.tongji.storage.text;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * 已发布知文的不可变 Cassandra 正文归档。
 *
 * @since 2026-08-28
 */
@Table("post_text_archive_by_post_id")
public class PostTextArchive {

    @PrimaryKey
    @Column("post_id")
    private final Long postId;

    @Column("body")
    private final String body;

    @Column("sha256")
    private final String sha256;

    @Column("archived_at")
    private final Instant archivedAt;

    @PersistenceCreator
    public PostTextArchive(Long postId, String body, String sha256, Instant archivedAt) {
        this.postId = postId;
        this.body = body;
        this.sha256 = sha256;
        this.archivedAt = archivedAt;
    }

    public Long getPostId() {
        return postId;
    }

    public String getBody() {
        return body;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
