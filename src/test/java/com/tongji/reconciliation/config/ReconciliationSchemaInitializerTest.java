package com.tongji.reconciliation.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationSchemaInitializerTest {

    @Test
    void addsDedupeStatusIndexWhenMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("reconciliation_task"), eq("idx_reconciliation_task_dedupe_status"))).thenReturn(null);

        new ReconciliationSchemaInitializer(jdbcTemplate).initialize();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        assertThat(sql.getValue())
                .contains("ADD INDEX idx_reconciliation_task_dedupe_status (dedupe_scope, status)");
    }

    @Test
    void leavesMatchingDedupeStatusIndexUnchanged() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("reconciliation_task"), eq("idx_reconciliation_task_dedupe_status")))
                .thenReturn("dedupe_scope,status");

        new ReconciliationSchemaInitializer(jdbcTemplate).initialize();

        verify(jdbcTemplate, never()).execute(anyString());
    }
}
