package com.tongji.reconciliation;

import com.tongji.reconciliation.mapper.ReconciliationCheckpointMapper;
import com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationScanType;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;
import com.tongji.reconciliation.model.ReconciliationTaskStatus;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationTask1ContractTest {

    @Test
    void constantsAndMapperContractsMatchTask1Requirements() throws Exception {
        assertThat(ReconciliationTaskType.ALL).containsExactly(
                "es_index",
                "rag_index",
                "feed_cache_invalidate",
                "gorse_item_upsert",
                "gorse_feedback",
                "cassandra_text",
                "comment_count",
                "follow_graph",
                "follow_inbox"
        );
        assertThat(ReconciliationTargetType.ALL).containsExactly("post", "comment", "user");
        assertThat(ReconciliationScanType.ALL).containsExactly(
                "post_es",
                "post_rag",
                "post_gorse",
                "post_cassandra",
                "comment_cassandra",
                "post_comment_count",
                "comment_reply_count",
                "user_follow_graph",
                "running_timeout"
        );
        assertThat(ReconciliationTaskStatus.ALL).containsExactly("pending", "running", "succeeded", "dead");
        assertThat(ReconciliationTaskStatus.isActive("pending")).isTrue();
        assertThat(ReconciliationTaskStatus.isActive("running")).isTrue();
        assertThat(ReconciliationTaskStatus.isActive("dead")).isFalse();
        assertThat(ReconciliationTaskStatus.isActive("succeeded")).isFalse();

        assertThat(methodNames(ReconciliationTaskMapper.class)).contains(
                "pollPending",
                "markRunning",
                "markSucceeded",
                "markPendingRetry",
                "markDead",
                "resetDeadToPending",
                "findStuckRunning",
                "resetRunningToPending",
                "existsActiveTask",
                "query"
        );
        assertThat(methodNames(ReconciliationCheckpointMapper.class)).contains("findByScanType", "upsert", "updateCheckpoint");
        assertThat(methodNames(ReconciliationErrorLogMapper.class)).contains("insert");

        ReconciliationTaskQuery query = ReconciliationTaskQuery.builder()
                .status("pending")
                .targetType("post")
                .targetId(7L)
                .taskType("es_index")
                .limit(20)
                .offset(0)
                .build();
        assertThat(query.getStatus()).isEqualTo("pending");
        assertThat(query.getTargetId()).isEqualTo(7L);
    }

    @Test
    void schemaAndXmlDeclareRequiredStateSemantics() throws IOException {
        String schema = Files.readString(Path.of("db/schema.sql"));
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS reconciliation_task");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS reconciliation_checkpoint");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS reconciliation_error_log");
        assertThat(schema).contains("KEY idx_reconciliation_task_scheduled (status, next_execute_at)");
        assertThat(schema).contains("KEY idx_reconciliation_task_target (target_type, target_id, task_type)");
        assertThat(schema).contains("execution_duration_ms BIGINT NULL");
        assertThat(schema).contains("dedupe_scope VARCHAR(180) NULL");
        assertThat(schema).contains("task_payload TEXT NULL");
        assertThat(schema).contains("active_dedupe_scope");
        assertThat(schema).contains("UNIQUE KEY uk_reconciliation_task_active_dedupe_scope (active_dedupe_scope)");

        String taskXml = Files.readString(Path.of("src/main/resources/mapper/ReconciliationTaskMapper.xml"));
        assertThat(taskXml).contains("namespace=\"com.tongji.reconciliation.mapper.ReconciliationTaskMapper\"");
        assertThat(taskXml).contains("<select id=\"pollPending\"");
        assertThat(taskXml).contains("status = 'pending'");
        assertThat(taskXml).contains("next_execute_at &lt;= NOW(3)");
        assertThat(taskXml).contains("<update id=\"markRunning\"");
        assertThat(taskXml).contains("status = 'pending'");
        assertThat(taskXml).contains("<select id=\"existsActiveTask\"");
        assertThat(taskXml).contains("status IN ('pending', 'running')");
        assertThat(taskXml).contains("<update id=\"resetDeadToPending\"");
        assertThat(taskXml).contains("retry_count = 0");
        assertThat(taskXml).contains("execution_duration_ms = NULL");
        assertThat(taskXml).contains("last_error = NULL");
        assertThat(taskXml).contains("next_execute_at = NOW(3)");
        assertThat(taskXml).contains("<select id=\"findActiveByDedupeScope\"");
        assertThat(taskXml).contains("<select id=\"findStuckRunning\"");
        assertThat(taskXml).contains("updated_at &lt;= #{staleBefore}");
        assertThat(taskXml).contains("task_payload");
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
