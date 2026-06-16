package com.tongji.storage.text;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("post_text_by_post_id")
public class PostText {

    @PrimaryKey
    @Column("post_id")
    private final Long postId;

    @Column("body")
    private final String body;

    @Column("version")
    private final int version;

    @Column("sha256")
    private final String sha256;

    @Column("updated_at")
    private final Instant updatedAt;

    @PersistenceCreator
    public PostText(Long postId, String body, int version, String sha256, Instant updatedAt) {
        this.postId = postId;
        this.body = body;
        this.version = version;
        this.sha256 = sha256;
        this.updatedAt = updatedAt;
    }

    public Long getPostId() {
        return postId;
    }

    public String getBody() {
        return body;
    }

    public int getVersion() {
        return version;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
