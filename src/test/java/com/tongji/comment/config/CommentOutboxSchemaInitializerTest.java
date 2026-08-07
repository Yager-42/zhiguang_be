package com.tongji.comment.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentOutboxSchemaInitializerTest {

    @Test
    void addsPublishedCleanupIndexWhenMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("comment_outbox"), eq("idx_comment_outbox_published"))).thenReturn(null);

        new CommentOutboxSchemaInitializer(jdbcTemplate).initialize();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(sql.capture());
        List<String> statements = sql.getAllValues();
        assertThat(statements.get(0)).contains("CREATE TABLE IF NOT EXISTS comment_outbox");
        assertThat(statements.get(1))
                .contains("ADD INDEX idx_comment_outbox_published (state, published_at, event_id)");
    }

    @Test
    void leavesMatchingPublishedCleanupIndexUnchanged() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("comment_outbox"), eq("idx_comment_outbox_published")))
                .thenReturn("state,published_at,event_id");

        new CommentOutboxSchemaInitializer(jdbcTemplate).initialize();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        assertThat(sql.getValue()).contains("CREATE TABLE IF NOT EXISTS comment_outbox");
    }
}
