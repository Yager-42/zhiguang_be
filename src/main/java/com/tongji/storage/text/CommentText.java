package com.tongji.storage.text;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("comment_text_by_comment_id")
public class CommentText {

    @PrimaryKey
    @Column("comment_id")
    private final Long commentId;

    @Column("body")
    private final String body;

    @Column("version")
    private final int version;

    @Column("updated_at")
    private final Instant updatedAt;

    @PersistenceCreator
    public CommentText(Long commentId, String body, int version, Instant updatedAt) {
        this.commentId = commentId;
        this.body = body;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public Long getCommentId() {
        return commentId;
    }

    public String getBody() {
        return body;
    }

    public int getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
