package com.tongji.comment.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentOperationalMetricsTest {

    @Test
    void samplingFailureLeavesCachedGaugesAvailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommentOperationalMetrics metrics = new CommentOperationalMetrics(jdbcTemplate, registry,
                new CommentMetrics(registry));

        metrics.sample();

        assertThat(registry.get("comment.operational.samples")
                .tag("result", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("comment.pending.count").gauge().value()).isZero();
    }

    @Test
    void samplingOnlyScansPendingComments() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(null);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommentOperationalMetrics metrics = new CommentOperationalMetrics(jdbcTemplate, registry,
                new CommentMetrics(registry));

        metrics.sample();

        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.contains("WHERE status = 'pending'"),
                any(org.springframework.jdbc.core.RowMapper.class));
    }
}
